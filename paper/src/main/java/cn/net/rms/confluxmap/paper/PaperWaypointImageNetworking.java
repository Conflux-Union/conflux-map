package cn.net.rms.confluxmap.paper;

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
import cn.net.rms.confluxmap.server.shared.SharedWaypointSessionHandler;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import cn.net.rms.confluxmap.server.waypointimage.SharedWaypointImageGc;
import cn.net.rms.confluxmap.server.waypointimage.SharedWaypointImageSessions;
import cn.net.rms.confluxmap.server.waypointimage.SharedWaypointImageStore;
import java.nio.file.Path;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Paper plugin-messaging adapter for the independent waypoint-image channel. */
final class PaperWaypointImageNetworking implements PluginMessageListener {
    private static final Logger IMAGE_LOGGER = LogManager.getLogger("ConfluxMap-Paper");
    static final String CHANNEL = WaypointImageProto.CHANNEL_ID;
    private static final int GC_INTERVAL_TICKS = 400;

    private final ConfluxMapPaperPlugin plugin;
    private final PaperCompanion companion;
    private final PaperSharedWaypointNetworking sharedNetworking;
    private final PaperPluginMessageDispatcher messages;
    private final SharedWaypointImageSessions rateLimits =
        new SharedWaypointImageSessions(10, 0L);
    private volatile SharedWaypointImageStore imageStore;
    private int gcCountdown;
    private boolean registered;

    PaperWaypointImageNetworking(
        final ConfluxMapPaperPlugin plugin,
        final PaperCompanion companion,
        final PaperSharedWaypointNetworking sharedNetworking,
        final PaperPluginMessageDispatcher messages
    ) {
        this.plugin = plugin;
        this.companion = companion;
        this.sharedNetworking = sharedNetworking;
        this.messages = messages;
    }

    void register() {
        if (registered) {
            return;
        }
        registered = true;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    void unregister() {
        if (!registered) {
            return;
        }
        registered = false;
        plugin.getServer().getMessenger().unregisterIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, CHANNEL);
        imageStore = null;
    }

    void resetWorldState() {
        imageStore = null;
    }

    @Override
    public void onPluginMessageReceived(final String channel, final Player player, final byte[] payload) {
        if (!CHANNEL.equals(channel) || !companion.isEnabled()
            || !companion.sharedWaypointsEnabled()
            || sharedNetworking.isMuted(player.getUniqueId())) {
            return;
        }
        if (sharedNetworking.negotiatedMinor(player.getUniqueId())
            < SharedWaypointProto.PROTO_MINOR_WAYPOINT_DESCRIPTION) {
            return;
        }
        final byte[] stable = payload.clone();
        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> receive(player.getUniqueId(), stable));
            return;
        }
        receive(player.getUniqueId(), stable);
    }

    /** Runs on the primary thread; mirrors the shared channel's receive contract. */
    private void receive(final UUID playerId, final byte[] payload) {
        final Player player = Bukkit.getPlayer(playerId);
        if (player == null || !player.isOnline()
            || !companion.isEnabled() || !companion.sharedWaypointsEnabled()) {
            return;
        }
        if (payload.length < 1 || payload.length > WaypointImageProto.MAX_C2S_PAYLOAD) {
            malformed(player, payload.length, "payload size outside cap");
            return;
        }
        final WaypointImageMessage.C2S message;
        try {
            message = WaypointImageCodec.decodeC2S(payload);
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            malformed(player, payload.length, e.getMessage());
            return;
        }
        handle(player, message, System.currentTimeMillis());
    }

    private void handle(
        final Player player,
        final WaypointImageMessage.C2S message,
        final long now
    ) {
        final UUID playerId = player.getUniqueId();
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

    void tick() {
        final SharedWaypointImageStore store = imageStore;
        if (!companion.isEnabled() || store == null || ++gcCountdown < GC_INTERVAL_TICKS) {
            return;
        }
        gcCountdown = 0;
        final Path waypointsFile = sharedWaypointsFile();
        if (waypointsFile == null) {
            return;
        }
        new SharedWaypointImageGc(
            store.imageStore(),
            () -> java.util.Optional.of(waypointsFile),
            IMAGE_LOGGER
        ).collect(System.currentTimeMillis());
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
                        new WaypointImageStore(imagesDir()), limits, IMAGE_LOGGER
                    );
                    built.rebuildOwnerTable();
                    imageStore = current = built;
                }
            }
        }
        return current;
    }

    private Path imagesDir() {
        return primaryWorldRoot().resolve("confluxmap").resolve("images");
    }

    private Path sharedWaypointsFile() {
        return primaryWorldRoot().resolve("confluxmap").resolve("shared_waypoints.json");
    }

    private Path primaryWorldRoot() {
        return PaperWorldDirectory.levelRoot(
            Bukkit.getWorlds().isEmpty()
                ? plugin.getServer().getWorldContainer().toPath()
                : Bukkit.getWorlds().get(0).getWorldFolder().toPath()
        );
    }

    private void send(final Player player, final WaypointImageMessage.S2C message) {
        try {
            messages.send(
                PaperPluginMessageDispatcher.recipient(plugin, player),
                CHANNEL,
                WaypointImageCodec.encodeS2C(message)
            );
        } catch (final SharedWaypointProtocolException | RuntimeException e) {
            IMAGE_LOGGER.error("Failed to encode {} for {}",
                message.getClass().getSimpleName(), player.getName(), e);
        }
    }

    private void malformed(final Player player, final int bytes, final String reason) {
        final SharedWaypointSessionHandler.MalformedOutcome outcome =
            sharedNetworking.recordMalformed(player.getUniqueId());
        IMAGE_LOGGER.warn(
            "Dropped malformed waypoint-image payload from {} (bytes={}, strike={}/{}, reason={})",
            player.getName(), bytes, outcome.strikes(),
            SharedWaypointSessionHandler.MAX_MALFORMED_STRIKES,
            reason == null ? "decode failure" : reason
        );
    }
}
