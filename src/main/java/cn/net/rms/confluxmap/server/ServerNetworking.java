package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Dimensions;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.BiomeLangReqC2S;
import cn.net.rms.confluxmap.core.net.BiomeLangS2C;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.FeatureRestrictionsS2C;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapFullSyncManifestS2C;
import cn.net.rms.confluxmap.core.net.MapSyncCapability;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncProtocol;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.NegotiatedMapSync;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.PlayerPositionsS2C;
import cn.net.rms.confluxmap.core.net.ServerInstanceS2C;
import cn.net.rms.confluxmap.core.net.ServerViewDistanceS2C;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.net.QuadraLayoutS2C;
import cn.net.rms.confluxmap.core.predict.QuadrantLayout;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.Identifier;

/**
 * Server-side channel handler for {@link Proto#CHANNEL_ID}. Owns the receiver registrations and
 * the per-player state map; defers everything else to {@link ConfluxMapCompanion}.
 *
 * <p>HELLO_C2S is answered with HELLO_POLICY immediately. MAP_VIEW_REQ is registered so the
 * channel is wired, but its handler just logs-and-drops each packet in S3 - the patch-serving
 * implementation lands in S4 ({@code PatchBuilder}) and the request-planning client side in S5.
 */
public final class ServerNetworking {
    public static final Identifier CHANNEL = Ids.of(Proto.CHANNEL_ID);

    private final ConfluxMapCompanion companion;
    private final ConcurrentMap<UUID, Integer> malformedStrikes = new ConcurrentHashMap<>();
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final ConcurrentMap<UUID, NegotiatedMapSync> peerSessions =
        new ConcurrentHashMap<>();
    /** Last BIOME_LANG answer time per player; one disk scan per interval is plenty. */
    private final ConcurrentMap<UUID, Long> biomeLangRequests = new ConcurrentHashMap<>();
    private static final long BIOME_LANG_REQUEST_INTERVAL_NANOS = 5_000_000_000L;
    /** Full-sync region scans run off the server thread; pages are sent back on it. */
    private final ExecutorService fullSyncWorker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ConfluxMap Full Sync");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicInteger fullSyncSyncIds = new AtomicInteger();
    private boolean registered;

    public ServerNetworking(final ConfluxMapCompanion companion) {
        this.companion = companion;
    }

    public synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PlayNetworking.registerServer(CHANNEL, this::onReceive);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            final UUID uuid = handler.getPlayer().getUuid();
            malformedStrikes.remove(uuid);
            mutedPlayers.remove(uuid);
            peerSessions.remove(uuid);
            biomeLangRequests.remove(uuid);
            final RegionSummaryService summaries = companion.summaries();
            if (summaries != null) {
                summaries.remove(uuid);
            }
            final ChunkLoadStateService loadStates = companion.chunkLoadStates();
            if (loadStates != null) {
                loadStates.remove(uuid);
            }
        });
    }

    /** Stops the full-sync scan worker; safe to call from server stop on any thread. */
    public void shutdown() {
        fullSyncWorker.shutdownNow();
    }

    private void onReceive(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final byte[] payload
    ) {
        if (!companion.isEnabled()) {
            return;
        }
        if (mutedPlayers.contains(player.getUuid())) {
            return;
        }
        try {
            validatePayload(payload);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] companion: dropping malformed payload from {} ({})",
                MinecraftAccess.playerName(player),
                e.getMessage()
            );
            recordMalformed(player);
            return;
        }
        try {
            final NegotiatedMapSync session = peerSessions.get(player.getUuid());
            final Message msg = session == null
                ? MapSyncProtocol.decodeServerbound(payload)
                : session.decodeInbound(payload);
            if (msg instanceof final HelloC2S hello) {
                handleHello(server, player, hello);
            } else if (msg instanceof final MapViewReqC2S req) {
                handleMapViewReq(server, player, req, payload.length);
            } else if (msg instanceof final LoadStateSubscribeC2S req) {
                handleLoadStateSubscribe(server, player, req);
            } else if (msg instanceof final MapSyncSubscribeC2S req) {
                handleMapSyncSubscribe(server, player, req);
            } else if (msg instanceof final MapRegionViewReqC2S req) {
                handleMapRegionViewReq(server, player, req, payload.length);
            } else if (msg instanceof final MapRegionSyncSubscribeC2S req) {
                handleMapRegionSyncSubscribe(server, player, req);
            } else if (msg instanceof final BiomeLangReqC2S req) {
                handleBiomeLangReq(server, player, req);
            } else {
                ConfluxMapMod.LOGGER.warn(
                    "[ConfluxMap] companion: unexpected {} from {} (server-side handlers expect C2S only)",
                    msg.getClass().getSimpleName(), MinecraftAccess.playerName(player)
                );
            }
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("[ConfluxMap] companion: undecodable {}-byte payload from {} ({})",
                payload.length, MinecraftAccess.playerName(player), e.getMessage());
            recordMalformed(player);
        }
    }

    private void recordMalformed(final ServerPlayerEntity player) {
        final int strikes = malformedStrikes.merge(player.getUuid(), 1, Integer::sum);
        send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "malformed companion payload (strike " + strikes + ")"));
        if (strikes >= 3) {
            mutedPlayers.add(player.getUuid());
        }
    }

    /**
     * Answers the client's biome-name request from the level's data packs. The scan runs on the
     * lang service's worker; the reply hops back to the server thread because that is where the
     * other sends happen and where the player's connection state is trustworthy.
     */
    private void handleBiomeLangReq(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final BiomeLangReqC2S req
    ) {
        final NegotiatedMapSync session = peerSession(player);
        if (session == null || !session.supports(MapSyncCapability.DATAPACK_BIOME_LANGS)) {
            // Only a hostile or buggy peer can get here: the codec already rejects the frame
            // for sessions without the capability, so this is defense in depth.
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] companion: biome-lang request without the negotiated capability from {}",
                MinecraftAccess.playerName(player)
            );
            return;
        }
        final DatapackLangService langs = companion.datapackLangs();
        if (langs == null) {
            return;
        }
        final long now = System.nanoTime();
        final long last = biomeLangRequests.getOrDefault(player.getUuid(), 0L);
        if (now - last < BIOME_LANG_REQUEST_INTERVAL_NANOS) {
            return;
        }
        biomeLangRequests.put(player.getUuid(), now);
        langs.requestBiomeNames(req.locale(), names ->
            server.execute(() -> {
                if (player.isDisconnected()) {
                    return;
                }
                sendNegotiated(player, session, new BiomeLangS2C(names));
            })
        );
    }

    /** Whether this player negotiated a session that may receive full-sync manifests. */
    public boolean canFullSync(final ServerPlayerEntity player) {
        final NegotiatedMapSync session = peerSession(player);
        return session != null && session.correctionsEnabled()
            && session.supports(MapSyncCapability.FULL_SYNC);
    }

    /**
     * Scans the player's current dimension off-thread and streams the manifest pages to the
     * player, nearest regions first. Status lines are reported on the server thread through
     * {@code onStatus} so the invoking command can address its own source.
     */
    public void startFullSync(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final java.util.function.Consumer<String> onStatus
    ) {
        final NegotiatedMapSync session = peerSession(player);
        if (session == null || !session.correctionsEnabled()
            || !session.supports(MapSyncCapability.FULL_SYNC)) {
            return;
        }
        final DimensionId dimension = Dimensions.of(player.getServerWorld());
        final String dimId = dimension.toString();
        final int dimIndex = Dimensions.indexOf(server, dimension);
        if (dimIndex < 0) {
            onStatus.accept("[ConfluxMap] Unable to resolve the dimension index for " + dimId + ".");
            return;
        }
        final double playerX = player.getX();
        final double playerZ = player.getZ();
        final java.nio.file.Path regionDir = RegionStoragePaths.regionDirectory(
            server.getSavePath(net.minecraft.util.WorldSavePath.ROOT), dimId
        );
        final int syncId = fullSyncSyncIds.incrementAndGet() & 0xFFFF;
        fullSyncWorker.execute(() -> {
            final RegionManifestScanner.ScanResult result;
            try {
                result = RegionManifestScanner.scan(regionDir);
            } catch (final java.io.IOException e) {
                ConfluxMapMod.LOGGER.warn(
                    "[ConfluxMap] failed to list region directory for full sync: {}", regionDir, e
                );
                server.execute(() -> {
                    if (!player.isDisconnected()) {
                        onStatus.accept("[ConfluxMap] Full sync failed: the region directory is unreadable.");
                    }
                });
                return;
            }
            RegionManifestScanner.sortByDistance(result.regions(), playerX, playerZ);
            final List<List<MapFullSyncManifestS2C.RegionCoord>> pages =
                RegionManifestScanner.paginate(result.regions());
            final int pageCount = Math.max(1, pages.size());
            server.execute(() -> {
                if (player.isDisconnected()) {
                    return;
                }
                for (int i = 0; i < pageCount; i++) {
                    final List<MapFullSyncManifestS2C.RegionCoord> page =
                        pages.isEmpty() ? List.of() : pages.get(i);
                    sendNegotiated(player, session, new MapFullSyncManifestS2C(
                        syncId, dimIndex, 0, i, pageCount, false, page
                    ));
                }
                if (result.truncated()) {
                    onStatus.accept("[ConfluxMap] Full sync truncated to the " + result.regions().size()
                        + " regions nearest to you; the world exceeds the manifest cap.");
                } else {
                    onStatus.accept("[ConfluxMap] Full sync started: " + result.regions().size()
                        + " regions queued.");
                }
            });
        });
    }

    /** Asks the player's client to stop the running full sync. */
    public boolean cancelFullSync(final ServerPlayerEntity player) {
        final NegotiatedMapSync session = peerSession(player);
        if (session == null || !session.supports(MapSyncCapability.FULL_SYNC)) {
            return false;
        }
        sendNegotiated(player, session, MapFullSyncManifestS2C.cancel(0));
        return true;
    }

    private void handleHello(final MinecraftServer server, final ServerPlayerEntity player, final HelloC2S hello) {
        if (!companion.isEnabled()) {
            return;
        }
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            hello, ConfluxMapMod.getVersion(), PredictorVersion.full()
        );
        final NegotiatedMapSync session = handshake.session();
        companion.summaries().remove(player.getUuid());
        peerSessions.put(player.getUuid(), session);
        if (handshake.selection() != null) {
            send(player, handshake.selection());
        }
        // FLAT_BASELINE goes out before policy: the client activates its session on HELLO_POLICY, so the
        // flat surfaces must already be stored by then. Pre-minor-2 clients log and ignore it.
        final List<FlatBaselineS2C.Entry> flatEntries = buildFlatBaselines(server);
        if (!flatEntries.isEmpty()
            && session.supports(MapSyncCapability.FLAT_BASELINE)) {
            sendNegotiated(player, session, new FlatBaselineS2C(flatEntries));
        }
        // QUADRA_LAYOUT goes out in the same pre-policy slot so quadrant-masked prediction is
        // already active when the session opens; peers without the capability keep unmasked
        // prediction and their residual diffs fall back to absolute mode via PredictorVersion.
        if (session.supports(MapSyncCapability.QUADRA_LAYOUT)) {
            final List<QuadraLayoutS2C.Entry> quadraEntries = buildQuadraLayouts(server);
            if (!quadraEntries.isEmpty()) {
                sendNegotiated(player, session, new QuadraLayoutS2C(quadraEntries));
            }
        }
        if (session.supports(MapSyncCapability.SERVER_VIEW_DISTANCE)) {
            sendNegotiated(player, session, ServerViewDistanceS2C.bounded(
                server.getPlayerManager().getViewDistance()
            ));
        }
        // Precedes HELLO_POLICY for the same reason FLAT_BASELINE does: the client opens its
        // session on the policy frame and must already know which namespace the data belongs to.
        if (session.supports(MapSyncCapability.SERVER_INSTANCE)) {
            sendNegotiated(
                player, session, new ServerInstanceS2C(companion.instanceId().toString())
            );
        }
        // Pre-policy so the gates apply before the session opens; older clients drop the frame.
        final FeatureRestrictionsS2C restrictions =
            CompanionPolicy.configuredRestrictions(companion.config());
        if (!restrictions.isEmpty()) {
            send(player, restrictions);
        }
        final HelloPolicyS2C policy = buildPolicy(server, session);
        send(player, policy);
        ConfluxMapMod.LOGGER.info(
            "[ConfluxMap] companion: replied HELLO_POLICY to {} (modVersion={} predictorVersion={} corrections={} absolute={} negotiated={} worldId={} seedGranted={})",
            MinecraftAccess.playerName(player), hello.modVersion(), hello.predictorVersion(),
            policy.flags().correctionsEnabled(), session.forceAbsolute(),
            handshake.selection() != null, policy.worldId(), policy.flags().seedGranted()
        );
    }

    private void handleMapViewReq(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapViewReqC2S req,
        final int payloadBytes
    ) {
        final NegotiatedMapSync session = peerSession(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        companion.summaries().request(
            server, player, req, payloadBytes, session.forceAbsolute(),
            senderFor(player, session)
        );
    }

    private void handleMapRegionViewReq(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapRegionViewReqC2S request,
        final int payloadBytes
    ) {
        final NegotiatedMapSync session = peerSession(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        companion.summaries().requestRegions(
            server, player, request, payloadBytes, session.forceAbsolute(),
            session.correctionProfile(),
            senderFor(player, session)
        );
    }

    private void handleLoadStateSubscribe(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final LoadStateSubscribeC2S request
    ) {
        final ChunkLoadStateService service = companion.chunkLoadStates();
        if (!companion.chunkLoadStatesEnabled() || service == null) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "chunk load state is disabled"));
            return;
        }
        if (!service.subscribe(
            server, player.getUuid(), request,
            delta -> sendNegotiated(player, peerSession(player), delta)
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid load-state dimension"));
        }
    }

    private void handleMapSyncSubscribe(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapSyncSubscribeC2S request
    ) {
        if (!companion.config().shareCorrections || !peerSession(player).correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        if (!companion.summaries().subscribe(
            server, player.getUuid(), request,
            senderFor(player, peerSession(player))
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid map-sync viewport"));
        }
    }

    private void handleMapRegionSyncSubscribe(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapRegionSyncSubscribeC2S request
    ) {
        if (!companion.config().shareCorrections || !peerSession(player).correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        if (!companion.summaries().subscribeRegions(
            server, player.getUuid(), request,
            senderFor(player, peerSession(player))
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid region-sync viewport"));
        }
    }

    private NegotiatedMapSync peerSession(final ServerPlayerEntity player) {
        return peerSessions.getOrDefault(player.getUuid(), disabledSession());
    }

    void broadcastPlayerPositions(final MinecraftServer server) {
        if (!companion.config().allowEntityRadar) {
            return;
        }
        final List<PlayerPositionsS2C.Entry> entries = new ArrayList<>();
        for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            entries.add(new PlayerPositionsS2C.Entry(
                player.getUuid(),
                MinecraftAccess.playerName(player),
                Dimensions.of(player.getServerWorld()).toString(),
                player.getX(),
                player.getY(),
                player.getZ(),
                player.isSpectator()
            ));
        }
        final PlayerPositionsS2C snapshot = new PlayerPositionsS2C(entries);
        for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            final NegotiatedMapSync session = peerSessions.get(player.getUuid());
            if (session != null && session.supports(MapSyncCapability.PLAYER_POSITIONS)) {
                sendNegotiated(player, session, snapshot);
            }
        }
    }

    private HelloPolicyS2C buildPolicy(
        final MinecraftServer server,
        final NegotiatedMapSync session
    ) {
        final ServerConfig cfg = companion.config();
        final HelloPolicyS2C.Flags configured = policyFlags(cfg);
        final HelloPolicyS2C.Flags flags = compatibleFlags(configured, session);
        final UUID worldId = companion.worldIds().get(server);
        final HelloPolicyS2C.Budgets budgets = new HelloPolicyS2C.Budgets(
            cfg.maxBytesPerSecondPerPlayer,
            cfg.maxTilesPerRequest,
            cfg.minRequestIntervalMs,
            Proto.DEFAULT_MAX_PATCH_LOD
        );
        final List<HelloPolicyS2C.DimDescriptor> dims = buildDimDescriptors(server, flags.seedGranted());
        return new HelloPolicyS2C(flags, worldId.toString(), WORLDGEN_VERSION, budgets, dims);
    }

    static HelloPolicyS2C.Flags compatibleFlags(
        final HelloPolicyS2C.Flags configured,
        final NegotiatedMapSync session
    ) {
        return CompanionPolicy.compatibleFlags(configured, session);
    }

    static HelloPolicyS2C.Flags policyFlags(final ServerConfig cfg) {
        return CompanionPolicy.configuredFlags(cfg);
    }

    private static List<HelloPolicyS2C.DimDescriptor> buildDimDescriptors(final MinecraftServer server, final boolean shareSeed) {
        // Vanilla seed is identical across dimensions (see research R6). Read once from the overworld.
        final long seed = server.getOverworld().getSeed();
        final List<HelloPolicyS2C.DimDescriptor> dims = new ArrayList<>(2);
        for (final ServerWorld sw : server.getWorlds()) {
            final DimensionId dimension = Dimensions.of(sw);
            final WorldPreset preset = WorldPresetDetector.detect(sw);
            // predictable=false also withholds the seed on pre-preset clients (their seedFor
            // checks it), so superflat/custom dims degrade correctly across versions.
            final boolean predictable = PredictionDimensions.supported(dimension)
                && preset.predictable();
            // The server always knows the seed; we just don't always share it.
            final boolean hasSeed = shareSeed;
            final long seedToSend = shareSeed ? seed : 0L;
            dims.add(new HelloPolicyS2C.DimDescriptor(
                dimension.toString(), dimension.path(), predictable, hasSeed, seedToSend, preset
            ));
        }
        return dims;
    }

    /** One entry per superflat dimension, indexed like {@link #buildDimDescriptors}'s list. */
    private static List<FlatBaselineS2C.Entry> buildFlatBaselines(final MinecraftServer server) {
        final List<FlatBaselineS2C.Entry> entries = new ArrayList<>(1);
        int dimIndex = 0;
        for (final ServerWorld sw : server.getWorlds()) {
            if (WorldPresetDetector.detect(sw) == WorldPreset.FLAT) {
                final int index = dimIndex;
                FlatWorldBaseline.of(sw).ifPresent(
                    baseline -> entries.add(new FlatBaselineS2C.Entry(index, baseline))
                );
            }
            dimIndex++;
        }
        return entries;
    }

    /** One entry per quadra-gen-managed dimension, indexed like {@link #buildDimDescriptors}'s list. */
    private List<QuadraLayoutS2C.Entry> buildQuadraLayouts(final MinecraftServer server) {
        final Map<DimensionId, QuadrantLayout> layouts = companion.quadraLayouts();
        final List<QuadraLayoutS2C.Entry> entries = new ArrayList<>(layouts.size());
        int dimIndex = 0;
        for (final ServerWorld sw : server.getWorlds()) {
            final QuadrantLayout layout = layouts.get(Dimensions.of(sw));
            if (layout != null) {
                entries.add(new QuadraLayoutS2C.Entry(dimIndex, layout));
            }
            dimIndex++;
        }
        return entries;
    }

    private static void send(final ServerPlayerEntity player, final Message msg) {
        final byte[] payload;
        try {
            payload = MsgCodec.encode(msg);
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.error("[ConfluxMap] companion: failed to serialize {}: {}", msg.getClass().getSimpleName(), e.getMessage());
            return;
        }
        send(player, payload);
    }

    private static void send(final ServerPlayerEntity player, final byte[] payload) {
        PlayNetworking.sendServer(player, CHANNEL, payload);
    }

    private static void sendNegotiated(
        final ServerPlayerEntity player,
        final NegotiatedMapSync session,
        final Message message
    ) {
        try {
            send(player, session.encodeOutbound(message));
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] companion: could not encode {} ({})",
                message.getClass().getSimpleName(), e.getMessage()
            );
        }
    }

    private static RegionSummaryService.MessageSender senderFor(
        final ServerPlayerEntity player,
        final NegotiatedMapSync session
    ) {
        return new RegionSummaryService.MessageSender() {
            @Override
            public void send(final Message message) {
                ServerNetworking.sendNegotiated(player, session, message);
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                if (session.correctionProfile().carriesSourceMetadata()) {
                    ServerNetworking.send(player, payload);
                } else {
                    ServerNetworking.sendNegotiated(player, session, message);
                }
            }
        };
    }

    private static NegotiatedMapSync disabledSession() {
        return NegotiatedMapSync.server(
            CorrectionProfile.SOURCE_LIGHT_V2,
            NegotiatedMapSync.CorrectionMode.DISABLED,
            "",
            Map.of()
        );
    }

    private static void validatePayload(final byte[] payload) throws ProtoException {
        final int readable = payload.length;
        if (readable < 1) {
            throw new ProtoException("empty payload");
        }
        if (readable > Proto.MAX_C2S_PAYLOAD) {
            throw new ProtoException("C2S payload " + readable + " above cap " + Proto.MAX_C2S_PAYLOAD);
        }
    }

    /** Vanilla worldgen version this server jar speaks; the client maps it to a cubiomes {@code MCVersion}. */
    private static final String WORLDGEN_VERSION = MinecraftVersion.current();
}
