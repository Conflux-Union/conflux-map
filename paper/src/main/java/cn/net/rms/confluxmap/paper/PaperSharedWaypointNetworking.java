package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.shared.SharedWaypointCodec;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointFraming;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.shared.SharedWaypointSessionHandler;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Paper plugin-messaging adapter for the independent shared-waypoint protocol. */
final class PaperSharedWaypointNetworking implements PluginMessageListener {
    static final String CHANNEL = SharedWaypointProto.CHANNEL_ID;

    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private final PaperPluginMessageDispatcher messages;
    private final SharedWaypointSessionHandler sessions = new SharedWaypointSessionHandler();

    PaperSharedWaypointNetworking(
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
        sessions.clear();
    }

    @Override
    public void onPluginMessageReceived(
        final String channel,
        final Player player,
        final byte[] payload
    ) {
        if (!CHANNEL.equals(channel) || !companion.isEnabled()
            || sessions.isMuted(player.getUniqueId())) {
            return;
        }
        final byte[] stable = payload.clone();
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> receive(player.getUniqueId(), stable));
            return;
        }
        receive(player.getUniqueId(), stable);
    }

    void tick() {
        if (!companion.isEnabled()) {
            return;
        }
        for (final Player player : Bukkit.getOnlinePlayers()) {
            final SharedWaypointSessionHandler.Peer peer = peer(player);
            if (sessions.updateOperator(peer)) {
                send(player, sessions.status(peer, environment()));
            }
        }
    }

    void disconnect(final UUID playerId) {
        sessions.disconnect(playerId);
    }

    /** Session view for companion channels that share the waypoints trust domain. */
    boolean isMuted(final UUID playerId) {
        return sessions.isMuted(playerId);
    }

    int negotiatedMinor(final UUID playerId) {
        return sessions.negotiatedMinor(playerId);
    }

    SharedWaypointSessionHandler.MalformedOutcome recordMalformed(final UUID playerId) {
        return sessions.recordMalformed(playerId);
    }

    void featureStateChanged() {
        if (!companion.sharedWaypointsEnabled()) {
            sessions.clearSubscriptions();
        }
        if (!companion.isEnabled()) {
            return;
        }
        final SharedWaypointSessionHandler.Environment environment = environment();
        for (final Player player : Bukkit.getOnlinePlayers()) {
            if (sessions.isCompatible(player.getUniqueId())) {
                send(player, sessions.status(peer(player), environment));
            }
        }
    }

    void commandMutation(final cn.net.rms.confluxmap.server.shared.SharedWaypointService.MutationResult mutation) {
        for (final SharedWaypointMessage delta : SharedWaypointSessionHandler.deltaMessages(mutation)) {
            broadcastSubscribed(delta);
        }
    }

    /** Sends one delta to every subscriber that negotiated a wire minor able to carry it. */
    private void broadcastSubscribed(final SharedWaypointMessage message) {
        for (final Player recipient : Bukkit.getOnlinePlayers()) {
            // Group deltas have no minor-3 representation; older modded clients keep their
            // previous revision and resync on the next snapshot instead of failing to decode.
            if (sessions.isSubscribed(recipient.getUniqueId())
                && SharedWaypointCodec.encodableAt(
                    message, sessions.negotiatedMinor(recipient.getUniqueId())
                )) {
                send(recipient, message);
            }
        }
    }

    private void receive(final UUID playerId, final byte[] payload) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline() || !companion.isEnabled()) {
            return;
        }
        if (payload.length < 1 || payload.length > SharedWaypointProto.MAX_C2S_PAYLOAD) {
            malformed(player, payload.length, "payload size outside cap");
            return;
        }
        final SharedWaypointMessage message;
        try {
            message = SharedWaypointCodec.decodeC2S(
                payload,
                sessions.negotiatedMinor(player.getUniqueId())
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            malformed(player, payload.length, e.getMessage());
            return;
        }
        final SharedWaypointSessionHandler.Dispatch dispatch = sessions.handle(
            peer(player), message, environment()
        );
        for (final SharedWaypointMessage direct : dispatch.direct()) {
            send(player, direct);
        }
        for (final SharedWaypointMessage delta : dispatch.broadcasts()) {
            broadcastSubscribed(delta);
        }
    }

    private SharedWaypointSessionHandler.Environment environment() {
        final ServerConfig config = companion.config();
        return new SharedWaypointSessionHandler.Environment(
            companion.sharedWaypointsEnabled(),
            companion.worldId().toString(),
            config.maxSharedWaypointsPerWorld,
            config.maxSharedWaypointsPerPlayer,
            companion.sharedWaypoints()
        );
    }

    private static SharedWaypointSessionHandler.Peer peer(final Player player) {
        return new SharedWaypointSessionHandler.Peer(
            player.getUniqueId(),
            player.getName(),
            player.hasPermission("confluxmap.admin")
        );
    }

    private void malformed(final Player player, final int bytes, final String reason) {
        final SharedWaypointSessionHandler.MalformedOutcome outcome =
            sessions.recordMalformed(player.getUniqueId());
        plugin.getSLF4JLogger().warn(
            "Dropped malformed shared-waypoint payload from {} (bytes={}, strike={}/{}, reason={})",
            player.getName(), bytes, outcome.strikes(),
            SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            reason == null ? "decode failure" : reason
        );
        if (outcome.newlyMuted()) {
            plugin.getSLF4JLogger().warn(
                "Muted malformed shared-waypoint payloads from {} until disconnect",
                player.getName()
            );
        }
    }

    private void send(final Player player, final SharedWaypointMessage message) {
        final byte[] payload;
        try {
            payload = SharedWaypointCodec.encode(
                message,
                sessions.negotiatedMinor(player.getUniqueId())
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            plugin.getSLF4JLogger().error(
                "Failed to encode {} for {}",
                message.getClass().getSimpleName(), player.getName(), e
            );
            return;
        }
        final var recipient = PaperPluginMessageDispatcher.recipient(plugin, player);
        for (final byte[] frame : SharedWaypointFraming.outgoing(
            payload, sessions.negotiatedMinor(player.getUniqueId())
        )) {
            messages.send(recipient, CHANNEL, frame);
        }
    }
}
