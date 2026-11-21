package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.PlayNetworking;
import cn.net.rms.confluxmap.core.net.image.ImageDataS2C;
import cn.net.rms.confluxmap.core.net.image.ImageFetchC2S;
import cn.net.rms.confluxmap.core.net.image.ImageMissingS2C;
import cn.net.rms.confluxmap.core.net.image.ImageUploadBeginC2S;
import cn.net.rms.confluxmap.core.net.image.ImageUploadChunkC2S;
import cn.net.rms.confluxmap.core.net.image.ImageUploadResultS2C;
import cn.net.rms.confluxmap.core.net.image.WaypointImageCodec;
import cn.net.rms.confluxmap.core.net.image.WaypointImageMessage;
import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProto;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointProtocolException;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageStore;
import cn.net.rms.confluxmap.server.ConfluxMapCompanion;
import cn.net.rms.confluxmap.server.waypointimage.SharedWaypointImageGc;
import cn.net.rms.confluxmap.server.waypointimage.SharedWaypointImageSessions;
import cn.net.rms.confluxmap.server.waypointimage.SharedWaypointImageStore;
import java.nio.file.Path;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * Fabric transport adapter for the independent waypoint-image channel. The
 * channel only talks to peers whose shared-waypoint negotiation reached the
 * description minor; malformed payloads strike the same trust domain as the
 * shared-waypoint channel so a hostile client pays for both with one bucket.
 */
public final class WaypointImageNetworking {
    public static final Identifier CHANNEL = Ids.of(WaypointImageProto.CHANNEL_ID);
    /** Collection runs periodically; the 24h grace makes precise timing irrelevant. */
    private static final int GC_INTERVAL_TICKS = 400;

    private final ConfluxMapCompanion companion;
    private final SharedWaypointNetworking sharedNetworking;
    private volatile SharedWaypointImageStore imageStore;
    private final SharedWaypointImageSessions rateLimits =
        new SharedWaypointImageSessions(10);
    private int gcCountdown;
    private boolean registered;

    public WaypointImageNetworking(
        final ConfluxMapCompanion companion,
        final SharedWaypointNetworking sharedNetworking
    ) {
        this.companion = companion;
        this.sharedNetworking = sharedNetworking;
    }

    public synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;
        PlayNetworking.registerServer(CHANNEL, this::onReceive);
        ServerTickEvents.END_SERVER_TICK.register(this::onServerTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
            rateLimits.forget(handler.getPlayer().getUuid())
        );
    }

    /** Drops per-world state so a later world rebuilds its own store. */
    public void onWorldChanged() {
        imageStore = null;
    }

    private void onReceive(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final byte[] payload
    ) {
        if (!companion.isEnabled() || !companion.sharedWaypointsEnabled()) {
            return;
        }
        final UUID playerId = player.getUuid();
        if (sharedNetworking.isMuted(playerId)) {
            return;
        }
        if (sharedNetworking.negotiatedMinor(playerId)
            < SharedWaypointProto.PROTO_MINOR_WAYPOINT_DESCRIPTION) {
            return;
        }
        final int readable = payload.length;
        if (readable < 1 || readable > WaypointImageProto.MAX_C2S_PAYLOAD) {
            recordMalformed(player, readable, "payload size outside cap");
            return;
        }
        final WaypointImageMessage.C2S message;
        try {
            message = WaypointImageCodec.decodeC2S(payload);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            recordMalformed(player, readable, e.getMessage());
            return;
        }
        server.execute(() -> handle(player, message));
    }

    private void handle(final ServerPlayerEntity player, final WaypointImageMessage.C2S message) {
        if (!companion.isEnabled() || !companion.sharedWaypointsEnabled()) {
            return;
        }
        final UUID playerId = player.getUuid();
        final long now = System.currentTimeMillis();
        if (message instanceof final ImageUploadBeginC2S begin) {
            if (!rateLimits.tryUpload(playerId, now)) {
                send(player, new ImageUploadResultS2C(
                    begin.imageId(), WaypointImageProto.STATUS_RATE_LIMITED
                ));
                return;
            }
            send(player, new ImageUploadResultS2C(
                begin.imageId(),
                imageStore().begin(
                    playerId, begin.imageId(), begin.formatTag(),
                    begin.width(), begin.height(), begin.totalBytes(), now
                )
            ));
        } else if (message instanceof final ImageUploadChunkC2S chunk) {
            final Integer status = imageStore().chunk(
                playerId, chunk.imageId(), chunk.seq(), chunk.bytes(), now
            );
            if (status != null) {
                send(player, new ImageUploadResultS2C(chunk.imageId(), status));
            }
        } else if (message instanceof final ImageFetchC2S fetch) {
            if (!rateLimits.tryFetch(playerId, now)) {
                return;
            }
            imageStore().imageStore().load(fetch.imageId()).ifPresentOrElse(
                image -> send(player, new ImageDataS2C(
                    fetch.imageId(), image.formatTag(), image.png()
                )),
                () -> send(player, new ImageMissingS2C(fetch.imageId()))
            );
        }
    }

    private SharedWaypointImageStore imageStore() {
        SharedWaypointImageStore current = imageStore;
        if (current == null) {
            synchronized (this) {
                current = imageStore;
                if (current == null) {
                    final SharedWaypointImageStore.Limits limits =
                        new SharedWaypointImageStore.Limits(
                            companion.config().maxWaypointImageBytesPerWorld,
                            companion.config().maxWaypointImageBytesPerPlayer,
                            companion.config().waypointImageUploadsPerMinute
                        );
                    final SharedWaypointImageStore built = new SharedWaypointImageStore(
                        new WaypointImageStore(imagesDir()), limits, ConfluxMapMod.LOGGER
                    );
                    built.rebuildOwnerTable();
                    imageStore = current = built;
                }
            }
        }
        return current;
    }

    /** Mirrors SharedWaypointIo's file resolution: <world>/confluxmap/images. */
    private Path imagesDir() {
        final Path worldRoot = companion.sharedWorldRoot();
        if (worldRoot == null) {
            throw new IllegalStateException("waypoint images requested outside a world session");
        }
        return worldRoot.resolve("confluxmap").resolve("images");
    }

    private void onServerTick(final MinecraftServer server) {
        final SharedWaypointImageStore store = imageStore;
        if (!companion.isEnabled() || store == null || ++gcCountdown < GC_INTERVAL_TICKS) {
            return;
        }
        gcCountdown = 0;
        final Path waypointsFile = waypointsFile();
        if (waypointsFile == null) {
            return;
        }
        new SharedWaypointImageGc(
            store.imageStore(),
            () -> java.util.Optional.of(waypointsFile),
            ConfluxMapMod.LOGGER
        ).collect(System.currentTimeMillis(), store::onImageDeleted);
    }

    private Path waypointsFile() {
        final Path worldRoot = companion.sharedWorldRoot();
        if (worldRoot == null) {
            return null;
        }
        return worldRoot.resolve("confluxmap").resolve("shared_waypoints.json");
    }

    private void send(final ServerPlayerEntity player, final WaypointImageMessage.S2C message) {
        if (!ServerPlayNetworking.canSend(player, CHANNEL)) {
            return;
        }
        try {
            PlayNetworking.sendServer(player, CHANNEL, WaypointImageCodec.encodeS2C(message));
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            ConfluxMapMod.LOGGER.error(
                "[ConfluxMap] waypoint-image: failed to encode {} for {}",
                message.getClass().getSimpleName(),
                MinecraftAccess.playerName(player),
                e
            );
        }
    }

    private void recordMalformed(
        final ServerPlayerEntity player,
        final int payloadBytes,
        final String reason
    ) {
        final SharedWaypointSessionHandler.MalformedOutcome outcome =
            sharedNetworking.recordMalformed(player.getUuid());
        ConfluxMapMod.LOGGER.warn(
            "[ConfluxMap] waypoint-image: dropped malformed {}-byte payload from {} (strike {}/{}, reason={})",
            payloadBytes,
            MinecraftAccess.playerName(player),
            outcome.strikes(),
            SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            reason == null ? "decode failure" : reason
        );
        if (outcome.newlyMuted()) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] waypoint-image: muted malformed payloads from {} until disconnect",
                MinecraftAccess.playerName(player)
            );
        }
    }
}
