package cn.net.rms.confluxmap.server.waypointimage;

import cn.net.rms.confluxmap.core.net.image.WaypointImageCodec;
import cn.net.rms.confluxmap.core.net.image.WaypointImageProto;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageFile;
import cn.net.rms.confluxmap.core.waypoint.image.WaypointImageStore;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.apache.logging.log4j.Logger;

/**
 * Server-side upload sessions and quota accounting for waypoint images. The
 * server never decodes image bytes; it stores opaque containers keyed by UUID.
 * BEGIN is idempotent for the same owner (safe against reconnect retries) and
 * refuses to let a second owner overwrite an existing image, so nobody can
 * poison an image another player's waypoint references.
 */
public final class SharedWaypointImageStore {
    public static final int SESSION_TIMEOUT_MS = 30_000;
    public static final int MAX_PENDING_SESSIONS_PER_PLAYER = 4;
    public static final int MAX_PENDING_BYTES_PER_PLAYER = 128 * 1024;

    public record Limits(long maxBytesPerWorld, long maxBytesPerPlayer, int uploadsPerMinute) {
        public Limits {
            if (maxBytesPerWorld < WaypointImageProto.MAX_IMAGE_BYTES) {
                maxBytesPerWorld = WaypointImageProto.MAX_IMAGE_BYTES;
            }
            maxBytesPerPlayer = Math.min(maxBytesPerPlayer, maxBytesPerWorld);
        }
    }

    private final WaypointImageStore imageStore;
    private final Limits limits;
    private final Logger logger;
    private final Map<UUID, PlayerAccounting> accounting = new HashMap<>();
    private final Map<UUID, List<PendingUpload>> pending = new HashMap<>();
    private long storedBytesPerWorld;

    public SharedWaypointImageStore(
        final WaypointImageStore imageStore,
        final Limits limits,
        final Logger logger
    ) {
        this.imageStore = Objects.requireNonNull(imageStore, "imageStore");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public WaypointImageStore imageStore() {
        return imageStore;
    }

    /** Rebuilds per-player/world byte accounting and the owner map from file headers. */
    public void rebuildOwnerTable() {
        accounting.clear();
        storedBytesPerWorld = 0;
        if (!Files.isDirectory(imageStore.directory())) {
            return;
        }
        try (DirectoryStream<Path> files = Files.newDirectoryStream(
            imageStore.directory(), "*" + WaypointImageStore.EXTENSION
        )) {
            for (final Path file : files) {
                final UUID imageId = imageIdFromFile(file);
                if (imageId == null) {
                    logger.warn("Ignoring unreadable waypoint image name {}", file.getFileName());
                    continue;
                }
                final Optional<WaypointImageFile.Header> header = imageStore.readHeader(imageId);
                if (header.isEmpty()) {
                    logger.warn("Ignoring unreadable waypoint image header {}", file.getFileName());
                    continue;
                }
                final WaypointImageFile.Header h = header.get();
                accounting.computeIfAbsent(h.owner(), key -> new PlayerAccounting())
                    .storedBytes += h.payloadBytes();
                storedBytesPerWorld += h.payloadBytes();
            }
        } catch (final IOException e) {
            logger.warn("Failed to scan waypoint image directory for owner accounting", e);
        }
    }

    /**
     * Starts (or idempotently re-acknowledges) one upload. Returns the status
     * to send back; {@code STATUS_OK} also covers an image that already exists
     * under the same owner.
     */
    public int begin(
        final UUID playerId,
        final UUID imageId,
        final int formatTag,
        final int width,
        final int height,
        final int totalBytes,
        final long nowEpochMs
    ) {
        if (!WaypointImageCodec.isKnownFormat(formatTag)) {
            return WaypointImageProto.STATUS_INVALID_FORMAT;
        }
        if (totalBytes < 1 || totalBytes > WaypointImageProto.MAX_IMAGE_BYTES) {
            return WaypointImageProto.STATUS_TOO_LARGE;
        }
        final Optional<WaypointImageFile.Header> existing = imageStore.readHeader(imageId);
        if (existing.isPresent()) {
            return existing.get().owner().equals(playerId)
                ? WaypointImageProto.STATUS_OK
                : WaypointImageProto.STATUS_DUPLICATE_OWNER_CONFLICT;
        }

        final List<PendingUpload> sessions = pendingSessions(playerId);
        sessions.removeIf(upload -> nowEpochMs - upload.lastActivityEpochMs > SESSION_TIMEOUT_MS);
        if (sessions.size() >= MAX_PENDING_SESSIONS_PER_PLAYER) {
            return WaypointImageProto.STATUS_RATE_LIMITED;
        }
        final PlayerAccounting player = accounting.computeIfAbsent(playerId, key -> new PlayerAccounting());
        if (pendingBytes(sessions) + totalBytes > MAX_PENDING_BYTES_PER_PLAYER
            || player.storedBytes + totalBytes > limits.maxBytesPerPlayer()
            || storedBytesPerWorld + totalBytes > limits.maxBytesPerWorld()) {
            return WaypointImageProto.STATUS_QUOTA_EXCEEDED;
        }
        sessions.add(new PendingUpload(imageId, formatTag, width, height, totalBytes, nowEpochMs));
        return WaypointImageProto.STATUS_OK;
    }

    /**
     * Accepts one chunk of an in-flight upload. Intermediary chunks get no
     * reply ({@code null}); the completing chunk returns {@code STATUS_OK}.
     * Stray or out-of-order chunks are silently ignored so a client that lost
     * its session simply re-BEGINs.
     */
    public Integer chunk(
        final UUID playerId,
        final UUID imageId,
        final int seq,
        final byte[] bytes,
        final long nowEpochMs
    ) {
        final List<PendingUpload> sessions = pending.get(playerId);
        if (sessions == null) {
            return null;
        }
        sessions.removeIf(upload -> nowEpochMs - upload.lastActivityEpochMs > SESSION_TIMEOUT_MS);
        PendingUpload target = null;
        for (final PendingUpload upload : sessions) {
            if (upload.imageId.equals(imageId)) {
                target = upload;
                break;
            }
        }
        if (target == null || seq != target.chunks.size()) {
            return null;
        }
        if (target.receivedBytes + bytes.length > target.totalBytes) {
            sessions.remove(target);
            return WaypointImageProto.STATUS_TOO_LARGE;
        }
        target.chunks.add(bytes);
        target.receivedBytes += bytes.length;
        target.lastActivityEpochMs = nowEpochMs;
        if (target.receivedBytes < target.totalBytes) {
            return null;
        }
        sessions.remove(target);
        final byte[] full = new byte[target.totalBytes];
        int offset = 0;
        for (final byte[] chunk : target.chunks) {
            System.arraycopy(chunk, 0, full, offset, chunk.length);
            offset += chunk.length;
        }
        try {
            imageStore.save(imageId, playerId, target.formatTag, target.width, target.height, full);
        } catch (final IOException e) {
            logger.error("Failed to persist waypoint image {}", imageId, e);
            return WaypointImageProto.STATUS_STORAGE_ERROR;
        }
        final PlayerAccounting player = accounting.computeIfAbsent(playerId, key -> new PlayerAccounting());
        player.storedBytes += target.totalBytes;
        storedBytesPerWorld += target.totalBytes;
        return WaypointImageProto.STATUS_OK;
    }

    /** Frees the accounting a GC deletion just released (no-op for unknown owners). */
    public void onImageDeleted(final UUID owner, final long bytes) {
        final PlayerAccounting player = accounting.get(owner);
        if (player != null) {
            player.storedBytes = Math.max(0L, player.storedBytes - bytes);
        }
        storedBytesPerWorld = Math.max(0L, storedBytesPerWorld - bytes);
    }

    public long storedBytesBy(final UUID playerId) {
        final PlayerAccounting player = accounting.get(playerId);
        return player == null ? 0L : player.storedBytes;
    }

    public long storedBytesTotal() {
        return storedBytesPerWorld;
    }

    private List<PendingUpload> pendingSessions(final UUID playerId) {
        return pending.computeIfAbsent(playerId, key -> new ArrayList<>());
    }

    private long pendingBytes(final List<PendingUpload> sessions) {
        long total = 0;
        for (final PendingUpload session : sessions) {
            total += session.totalBytes;
        }
        return total;
    }

    private static UUID imageIdFromFile(final Path file) {
        final String name = file.getFileName().toString();
        if (!name.endsWith(WaypointImageStore.EXTENSION)) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - WaypointImageStore.EXTENSION.length()));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static final class PendingUpload {
        private final UUID imageId;
        private final int formatTag;
        private final int width;
        private final int height;
        private final int totalBytes;
        private final List<byte[]> chunks = new ArrayList<>();
        private long receivedBytes;
        private long lastActivityEpochMs;

        private PendingUpload(
            final UUID imageId,
            final int formatTag,
            final int width,
            final int height,
            final int totalBytes,
            final long nowEpochMs
        ) {
            this.imageId = imageId;
            this.formatTag = formatTag;
            this.width = width;
            this.height = height;
            this.totalBytes = totalBytes;
            this.lastActivityEpochMs = nowEpochMs;
        }
    }

    private static final class PlayerAccounting {
        private long storedBytes;
    }
}
