package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
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
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import cn.net.rms.confluxmap.server.CompanionPolicy;
import cn.net.rms.confluxmap.server.ServerConfig;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Bukkit plugin-messaging adapter for the existing binary map-sync protocol. */
final class PaperNetworking implements PluginMessageListener {
    static final String CHANNEL = Proto.CHANNEL_ID;

    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private final PaperPluginMessageDispatcher messages;
    private final Map<UUID, Integer> malformedStrikes = new ConcurrentHashMap<>();
    private final Set<UUID> mutedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, NegotiatedMapSync> sessions =
        new ConcurrentHashMap<>();

    PaperNetworking(
        final ConfluxMapPaperPlugin plugin,
        final PaperCompanion companion,
        final PaperPluginMessageDispatcher messages
    ) {
        this.plugin = plugin;
        this.companion = companion;
        this.messages = messages;
    }

    void register() {
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void unregister() {
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        malformedStrikes.clear();
        mutedPlayers.clear();
        sessions.clear();
    }

    @Override
    public void onPluginMessageReceived(
        final String channel,
        final Player player,
        final byte[] payload
    ) {
        if (!CHANNEL.equals(channel) || !companion.isEnabled()
            || mutedPlayers.contains(player.getUniqueId())) {
            return;
        }
        final byte[] stablePayload = payload.clone();
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(
                plugin,
                () -> receive(player.getUniqueId(), stablePayload)
            );
            return;
        }
        receive(player.getUniqueId(), stablePayload);
    }

    void disconnect(final UUID playerId) {
        malformedStrikes.remove(playerId);
        mutedPlayers.remove(playerId);
        sessions.remove(playerId);
        companion.corrections().remove(playerId);
        final PaperChunkLoadStateService loadStates = companion.chunkLoadStates();
        if (loadStates != null) {
            loadStates.remove(playerId);
        }
    }

    private void receive(final UUID playerId, final byte[] payload) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || !companion.isEnabled()) {
            return;
        }
        if (payload.length < 1 || payload.length > Proto.MAX_C2S_PAYLOAD) {
            malformed(player, "payload size outside cap");
            return;
        }
        try {
            final NegotiatedMapSync session = sessions.get(player.getUniqueId());
            final Message message = session == null
                ? MapSyncProtocol.decodeServerbound(payload)
                : session.decodeInbound(payload);
            if (message instanceof final HelloC2S hello) {
                hello(player, hello);
            } else if (message instanceof final MapViewReqC2S request) {
                requestTiles(player, request, payload.length);
            } else if (message instanceof final MapRegionViewReqC2S request) {
                requestRegions(player, request, payload.length);
            } else if (message instanceof final MapSyncSubscribeC2S request) {
                subscribe(player, request);
            } else if (message instanceof final MapRegionSyncSubscribeC2S request) {
                subscribeRegions(player, request);
            } else if (message instanceof final LoadStateSubscribeC2S request) {
                subscribeLoadStates(player, request);
            } else {
                plugin.getSLF4JLogger().warn(
                    "Unexpected {} from {} on the serverbound map-sync channel",
                    message.getClass().getSimpleName(), player.getName()
                );
            }
        } catch (final ProtoException e) {
            malformed(player, e.getMessage());
        } catch (final RuntimeException e) {
            plugin.getSLF4JLogger().error(
                "Could not handle Conflux Map payload from {}", player.getName(), e
            );
        }
    }

    private void hello(final Player player, final HelloC2S hello) {
        final MapSyncProtocol.ServerHandshake handshake = MapSyncProtocol.acceptClient(
            hello, plugin.getPluginMeta().getVersion(), PredictorVersion.full()
        );
        final NegotiatedMapSync session = handshake.session();
        sessions.put(player.getUniqueId(), session);
        companion.corrections().remove(player.getUniqueId());
        if (handshake.selection() != null) {
            send(player, handshake.selection());
        }
        final List<FlatBaselineS2C.Entry> flat = companion.flatBaselines();
        if (!flat.isEmpty()
            && session.supports(MapSyncCapability.FLAT_BASELINE)) {
            sendNegotiated(player, session, new FlatBaselineS2C(flat));
        }
        if (session.supports(MapSyncCapability.SERVER_VIEW_DISTANCE)) {
            final int playerSendDistance = player.getSendViewDistance();
            sendNegotiated(player, session, ServerViewDistanceS2C.bounded(
                playerSendDistance >= 0
                    ? playerSendDistance : plugin.getServer().getViewDistance()
            ));
        }
        // Precedes HELLO_POLICY for the same reason FLAT_BASELINE does: the client opens its
        // session on the policy frame and must already know which namespace the data belongs to.
        if (session.supports(MapSyncCapability.SERVER_INSTANCE)) {
            sendNegotiated(
                player, session, new ServerInstanceS2C(companion.instanceId().toString())
            );
        }
        final HelloPolicyS2C policy = policy(session);
        send(player, policy);
        plugin.getSLF4JLogger().info(
            "Replied to Conflux Map hello from {} (client={}, corrections={}, absolute={}, seed={})",
            player.getName(), hello.modVersion(), policy.flags().correctionsEnabled(),
            session.forceAbsolute(), policy.flags().seedGranted()
        );
    }

    private void requestTiles(
        final Player player,
        final MapViewReqC2S request,
        final int requestBytes
    ) {
        final NegotiatedMapSync session = session(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(
                ErrorS2C.ERR_COMPANION_DISABLED,
                "map corrections are disabled"
            ));
            return;
        }
        companion.corrections().requestTiles(
            player.getUniqueId(), request, requestBytes, session.forceAbsolute(),
            sender(player.getUniqueId())
        );
    }

    private void requestRegions(
        final Player player,
        final MapRegionViewReqC2S request,
        final int requestBytes
    ) {
        final NegotiatedMapSync session = session(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(
                ErrorS2C.ERR_COMPANION_DISABLED,
                "map corrections are disabled"
            ));
            return;
        }
        companion.corrections().requestRegions(
            player.getUniqueId(), request, requestBytes, session.forceAbsolute(),
            session.correctionProfile(),
            sender(player.getUniqueId())
        );
    }

    private void subscribe(final Player player, final MapSyncSubscribeC2S request) {
        final NegotiatedMapSync session = session(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        if (!companion.corrections().subscribe(
            player.getUniqueId(), request, sender(player.getUniqueId())
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid map-sync viewport"));
        }
    }

    private void subscribeRegions(
        final Player player,
        final MapRegionSyncSubscribeC2S request
    ) {
        final NegotiatedMapSync session = session(player);
        if (!companion.config().shareCorrections || !session.correctionsEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "map corrections are disabled"));
            return;
        }
        if (!companion.corrections().subscribeRegions(
            player.getUniqueId(), request, sender(player.getUniqueId())
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid region-sync viewport"));
        }
    }

    private void subscribeLoadStates(final Player player, final LoadStateSubscribeC2S request) {
        final PaperChunkLoadStateService service = companion.chunkLoadStates();
        if (service == null || !companion.chunkLoadStatesEnabled()) {
            send(player, new ErrorS2C(ErrorS2C.ERR_COMPANION_DISABLED, "chunk load state is disabled"));
            return;
        }
        if (!service.subscribe(
            player.getUniqueId(), request, companion.worlds(),
            delta -> sendNegotiated(player, session(player), delta)
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid load-state dimension"));
        }
    }

    private HelloPolicyS2C policy(final NegotiatedMapSync session) {
        final ServerConfig config = companion.config();
        final HelloPolicyS2C.Flags flags = CompanionPolicy.compatibleFlags(
            CompanionPolicy.configuredFlags(config), session
        );
        final boolean seed = flags.seedGranted();
        final HelloPolicyS2C.Budgets budgets = new HelloPolicyS2C.Budgets(
            config.maxBytesPerSecondPerPlayer,
            config.maxTilesPerRequest,
            config.minRequestIntervalMs,
            Proto.DEFAULT_MAX_PATCH_LOD
        );
        final List<HelloPolicyS2C.DimDescriptor> dimensions = new ArrayList<>();
        for (final PaperWorldDirectory.Entry entry : companion.worlds().entries()) {
            dimensions.add(new HelloPolicyS2C.DimDescriptor(
                entry.dimensionId(),
                entry.dimensionType(),
                PredictionDimensions.supported(entry.parsedDimensionId())
                    && entry.preset().predictable(),
                seed,
                seed ? companion.worldSeed() : 0L,
                entry.preset()
            ));
        }
        return new HelloPolicyS2C(
            flags,
            companion.worldId().toString(),
            Bukkit.getMinecraftVersion(),
            budgets,
            dimensions
        );
    }

    private NegotiatedMapSync session(final Player player) {
        return sessions.getOrDefault(player.getUniqueId(), disabledSession());
    }

    void broadcastPlayerPositions() {
        if (!companion.config().allowEntityRadar) {
            return;
        }
        final List<PlayerPositionsS2C.Entry> entries = new ArrayList<>();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final Location location = player.getLocation();
            entries.add(new PlayerPositionsS2C.Entry(
                player.getUniqueId(),
                player.getName(),
                player.getWorld().getKey().toString(),
                location.getX(),
                location.getY(),
                location.getZ(),
                player.getGameMode() == GameMode.SPECTATOR
            ));
        }
        final PlayerPositionsS2C snapshot = new PlayerPositionsS2C(entries);
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final NegotiatedMapSync session = sessions.get(player.getUniqueId());
            if (session != null && session.supports(MapSyncCapability.PLAYER_POSITIONS)) {
                sendNegotiated(player, session, snapshot);
            }
        }
    }

    private PaperCorrectionService.MessageSender sender(final UUID playerId) {
        return new PaperCorrectionService.MessageSender() {
            @Override
            public void send(final Message message) {
                final Player current = Bukkit.getPlayer(playerId);
                if (current != null && current.isOnline()) {
                    PaperNetworking.this.sendNegotiated(current, session(), message);
                }
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                final Player current = Bukkit.getPlayer(playerId);
                if (current != null && current.isOnline()) {
                    if (session().correctionProfile().carriesSourceMetadata()) {
                        PaperNetworking.this.send(current, payload);
                    } else {
                        PaperNetworking.this.sendNegotiated(current, session(), message);
                    }
                }
            }

            private NegotiatedMapSync session() {
                return sessions.getOrDefault(playerId, disabledSession());
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

    private void malformed(final Player player, final String reason) {
        final int strikes = malformedStrikes.merge(player.getUniqueId(), 1, Integer::sum);
        plugin.getSLF4JLogger().warn(
            "Dropped malformed Conflux Map payload from {} (strike {}/3, reason={})",
            player.getName(), strikes, reason == null ? "decode failure" : reason
        );
        send(player, new ErrorS2C(
            ErrorS2C.ERR_MALFORMED_REQUEST,
            "malformed companion payload (strike " + strikes + ")"
        ));
        if (strikes >= 3) {
            mutedPlayers.add(player.getUniqueId());
        }
    }

    private void send(final Player player, final Message message) {
        try {
            send(player, MsgCodec.encode(message));
        } catch (final ProtoException e) {
            plugin.getSLF4JLogger().error(
                "Failed to encode {}", message.getClass().getSimpleName(), e
            );
        }
    }

    private void send(final Player player, final byte[] payload) {
        messages.send(PaperPluginMessageDispatcher.recipient(plugin, player), CHANNEL, payload);
    }

    private void sendNegotiated(
        final Player player,
        final NegotiatedMapSync session,
        final Message message
    ) {
        try {
            send(player, session.encodeOutbound(message));
        } catch (final ProtoException e) {
            plugin.getSLF4JLogger().warn(
                "Could not encode {} for negotiated peer: {}",
                message.getClass().getSimpleName(), e.getMessage()
            );
        }
    }
}
