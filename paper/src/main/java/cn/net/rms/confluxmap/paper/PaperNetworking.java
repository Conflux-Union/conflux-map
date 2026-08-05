package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.FlatBaselineS2C;
import cn.net.rms.confluxmap.core.net.HelloC2S;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapCompatibilityS2C;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
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
    private final Map<UUID, MapSyncCompatibility.ServerSelection> profiles =
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
        profiles.clear();
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
        profiles.remove(playerId);
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
            final Message message = MsgCodec.decode(payload);
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
        final MapSyncCompatibility.ClientHello clientHello =
            MapSyncCompatibility.parseClientHello(hello.predictorVersion());
        final MapSyncCompatibility.ServerSelection selection =
            MapSyncCompatibility.selectServer(clientHello, PredictorVersion.full());
        profiles.put(player.getUniqueId(), selection);
        companion.corrections().remove(player.getUniqueId());
        if (selection.sendCompatibility()) {
            send(player, compatibility(selection));
        }
        final List<FlatBaselineS2C.Entry> flat = companion.flatBaselines();
        if (!flat.isEmpty()) {
            send(player, new FlatBaselineS2C(flat));
        }
        final HelloPolicyS2C policy = policy(selection);
        send(player, policy);
        plugin.getSLF4JLogger().info(
            "Replied to Conflux Map hello from {} (client={}, corrections={}, absolute={}, seed={})",
            player.getName(), hello.modVersion(), policy.flags().correctionsEnabled(),
            selection.forceAbsolute(), policy.flags().seedGranted()
        );
    }

    private void requestTiles(
        final Player player,
        final MapViewReqC2S request,
        final int requestBytes
    ) {
        final MapSyncCompatibility.ServerSelection profile = profile(player);
        if (!companion.config().shareCorrections || !profile.correctionsEnabled()) {
            send(player, new ErrorS2C(
                ErrorS2C.ERR_COMPANION_DISABLED,
                "map corrections are disabled"
            ));
            return;
        }
        companion.corrections().requestTiles(
            player.getUniqueId(), request, requestBytes, profile.forceAbsolute(),
            sender(player.getUniqueId())
        );
    }

    private void requestRegions(
        final Player player,
        final MapRegionViewReqC2S request,
        final int requestBytes
    ) {
        final MapSyncCompatibility.ServerSelection profile = profile(player);
        if (!companion.config().shareCorrections || !profile.correctionsEnabled()) {
            send(player, new ErrorS2C(
                ErrorS2C.ERR_COMPANION_DISABLED,
                "map corrections are disabled"
            ));
            return;
        }
        companion.corrections().requestRegions(
            player.getUniqueId(), request, requestBytes, profile.forceAbsolute(),
            sender(player.getUniqueId())
        );
    }

    private void subscribe(final Player player, final MapSyncSubscribeC2S request) {
        final MapSyncCompatibility.ServerSelection profile = profile(player);
        if (!companion.config().shareCorrections || !profile.correctionsEnabled()) {
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
        final MapSyncCompatibility.ServerSelection profile = profile(player);
        if (!companion.config().shareCorrections || !profile.correctionsEnabled()) {
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
            player.getUniqueId(), request, companion.worlds(), delta -> send(player, delta)
        )) {
            send(player, new ErrorS2C(ErrorS2C.ERR_MALFORMED_REQUEST, "invalid load-state dimension"));
        }
    }

    private HelloPolicyS2C policy(final MapSyncCompatibility.ServerSelection selection) {
        final ServerConfig config = companion.config();
        final HelloPolicyS2C.Flags flags = CompanionPolicy.compatibleFlags(
            CompanionPolicy.configuredFlags(config), selection
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

    private MapSyncCompatibility.ServerSelection profile(final Player player) {
        return profiles.getOrDefault(
            player.getUniqueId(),
            new MapSyncCompatibility.ServerSelection(false, false, false, "")
        );
    }

    private MapCompatibilityS2C compatibility(
        final MapSyncCompatibility.ServerSelection selection
    ) {
        final int mode = !selection.correctionsEnabled()
            ? MapCompatibilityS2C.MODE_DISABLED
            : selection.forceAbsolute()
                ? MapCompatibilityS2C.MODE_ABSOLUTE : MapCompatibilityS2C.MODE_RESIDUAL;
        final int reason = !selection.correctionsEnabled()
            ? MapCompatibilityS2C.REASON_NO_COMMON_WIRE
            : selection.forceAbsolute()
                ? MapCompatibilityS2C.REASON_BASELINE_MISMATCH : MapCompatibilityS2C.REASON_NONE;
        return new MapCompatibilityS2C(
            MapSyncCompatibility.NEGOTIATION_VERSION,
            plugin.getPluginMeta().getVersion(),
            Proto.PROTO_MAJOR,
            Proto.PROTO_MINOR,
            PatchCodec.FORMAT_VERSION,
            ChunkPatchCodec.FORMAT_VERSION,
            PredictorVersion.full(),
            mode,
            reason
        );
    }

    private PaperCorrectionService.MessageSender sender(final UUID playerId) {
        return new PaperCorrectionService.MessageSender() {
            @Override
            public void send(final Message message) {
                final Player current = Bukkit.getPlayer(playerId);
                if (current != null && current.isOnline()) {
                    PaperNetworking.this.send(current, message);
                }
            }

            @Override
            public void sendEncoded(final Message message, final byte[] payload) {
                final Player current = Bukkit.getPlayer(playerId);
                if (current != null && current.isOnline()) {
                    PaperNetworking.this.send(current, payload);
                }
            }
        };
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
}
