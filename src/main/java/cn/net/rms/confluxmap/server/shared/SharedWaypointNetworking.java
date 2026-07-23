package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointCodec;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointMessage;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.server.ConfluxMapCompanion;
import cn.net.rms.confluxmap.server.ServerConfig;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/** Fabric transport adapter for the independent shared-waypoint protocol channel. */
public final class SharedWaypointNetworking {
    public static final Identifier CHANNEL = Ids.of(SharedWaypointProto.CHANNEL_ID);

    private final ConfluxMapCompanion companion;
    private final SharedWaypointSessionHandler sessions = new SharedWaypointSessionHandler();
    private boolean registered;

    public SharedWaypointNetworking(final ConfluxMapCompanion companion) {
        this.companion = companion;
    }

    /** Global receivers live for the process lifetime and therefore must be registered only once. */
    public synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PlayNetworking.registerServer(CHANNEL, this::onReceive);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            final UUID playerId = handler.getPlayer().getUuid();
            sessions.disconnect(playerId);
            // Service-owned idempotency results deliberately survive reconnects.
        });
    }

    private void onReceive(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final byte[] payload
    ) {
        // Master-disabled companions never answer either protocol channel.
        if (!companion.isEnabled()) {
            return;
        }
        if (sessions.isMuted(player.getUuid())) {
            return;
        }
        final int readable = payload.length;
        if (readable < 1 || readable > SharedWaypointProto.MAX_C2S_PAYLOAD) {
            recordMalformed(player, readable, "payload size outside cap");
            return;
        }
        final SharedWaypointMessage message;
        try {
            message = SharedWaypointCodec.decodeC2S(payload);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            recordMalformed(player, readable, e.getMessage());
            return;
        }
        server.execute(() -> handle(server, player, message));
    }

    private void handle(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final SharedWaypointMessage message
    ) {
        if (!companion.isEnabled() || server.getPlayerManager().getPlayer(player.getUuid()) != player) {
            return;
        }
        final SharedWaypointSessionHandler.Dispatch dispatch = sessions.handle(
            peer(player),
            message,
            environment(server)
        );
        for (final SharedWaypointMessage direct : dispatch.direct()) {
            send(player, direct);
        }
        if (dispatch.broadcast() != null) {
            broadcast(server, dispatch.broadcast());
        }
    }

    private void broadcast(final MinecraftServer server, final SharedWaypointMessage message) {
        for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (sessions.isSubscribed(player.getUuid())) {
                send(player, message);
            }
        }
    }

    private void onServerTick(final MinecraftServer server) {
        if (!companion.isEnabled()) {
            return;
        }
        SharedWaypointSessionHandler.Environment environment = null;
        for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            final SharedWaypointSessionHandler.Peer peer = peer(player);
            if (!sessions.updateOperator(peer)) {
                continue;
            }
            if (environment == null) {
                environment = environment(server);
            }
            send(player, sessions.status(peer, environment));
        }
    }

    private void recordMalformed(
        final ServerPlayerEntity player,
        final int payloadBytes,
        final String reason
    ) {
        final SharedWaypointSessionHandler.MalformedOutcome outcome = sessions.recordMalformed(player.getUuid());
        ConfluxMapMod.LOGGER.warn(
            "shared-waypoint: dropped malformed {}-byte payload from {} (strike {}/{}, reason={})",
            payloadBytes,
            player.getEntityName(),
            outcome.strikes(),
            SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            reason == null ? "decode failure" : reason
        );
        if (outcome.newlyMuted()) {
            ConfluxMapMod.LOGGER.warn(
                "shared-waypoint: muted malformed packets from {} until disconnect",
                player.getEntityName()
            );
        }
    }

    /** Sends a fresh capability status after an operator changes the runtime feature switch. */
    public void onFeatureStateChanged(final MinecraftServer server) {
        if (!companion.sharedWaypointsEnabled()) {
            sessions.clearSubscriptions();
        }
        if (!companion.isEnabled()) {
            return;
        }
        final SharedWaypointSessionHandler.Environment environment = environment(server);
        for (final ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            if (sessions.isCompatible(player.getUuid())) {
                send(player, sessions.status(peer(player), environment));
            }
        }
    }

    public void onServerStopping() {
        sessions.clear();
    }

    private SharedWaypointSessionHandler.Environment environment(final MinecraftServer server) {
        final ServerConfig config = companion.config();
        return new SharedWaypointSessionHandler.Environment(
            companion.sharedWaypointsEnabled(),
            companion.worldIds().get(server).toString(),
            config.maxSharedWaypointsPerWorld,
            config.maxSharedWaypointsPerPlayer,
            companion.sharedWaypoints()
        );
    }

    private static SharedWaypointSessionHandler.Peer peer(final ServerPlayerEntity player) {
        return new SharedWaypointSessionHandler.Peer(
            player.getUuid(),
            player.getEntityName(),
            player.hasPermissionLevel(2)
        );
    }

    private static void send(
        final ServerPlayerEntity player,
        final SharedWaypointMessage message
    ) {
        // canSend prevents an unknown custom payload from reaching unmodded/older clients.
        if (!ServerPlayNetworking.canSend(player, CHANNEL)) {
            return;
        }
        final byte[] payload;
        try {
            payload = SharedWaypointCodec.encode(message);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            ConfluxMapMod.LOGGER.error(
                "shared-waypoint: failed to encode {} for {}",
                message.getClass().getSimpleName(),
                player.getEntityName(),
                e
            );
            return;
        }
        PlayNetworking.sendServer(player, CHANNEL, payload);
    }
}
