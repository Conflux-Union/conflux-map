package cn.net.rms.confluxmap.server.waypointimage;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Per-player rate limiting for the image channel. Buckets are deliberately
 * separate from the shared-waypoint mutation bucket: image traffic must never
 * be able to starve waypoint operations, and each player carries one upload
 * bucket (integers count whole images, not chunks) and one fetch bucket.
 */
public final class SharedWaypointImageSessions {
    public static final int FETCHES_PER_MINUTE = 60;
    public static final int UPLOAD_BURST = 10;
    public static final int FETCH_BURST = 60;

    private final int uploadsPerMinute;
    private final Map<UUID, PlayerBuckets> players = new HashMap<>();

    public SharedWaypointImageSessions(final int uploadsPerMinute) {
        if (uploadsPerMinute < 1) {
            throw new IllegalArgumentException("uploadsPerMinute must be positive");
        }
        this.uploadsPerMinute = uploadsPerMinute;
    }

    /** Returns true when one more whole-image upload is allowed now. */
    public boolean tryUpload(final UUID playerId, final long nowEpochMs) {
        return buckets(playerId).uploads.tryConsume(nowEpochMs);
    }

    /** Returns true when one more image fetch is allowed now. */
    public boolean tryFetch(final UUID playerId, final long nowEpochMs) {
        return buckets(playerId).fetches.tryConsume(nowEpochMs);
    }

    public void forget(final UUID playerId) {
        players.remove(playerId);
    }

    private PlayerBuckets buckets(final UUID playerId) {
        Objects.requireNonNull(playerId, "playerId");
        return players.computeIfAbsent(playerId, key -> new PlayerBuckets(uploadsPerMinute));
    }

    private static final class PlayerBuckets {
        private final TokenBucket uploads;
        private final TokenBucket fetches;

        private PlayerBuckets(final int uploadsPerMinute) {
            uploads = new TokenBucket(UPLOAD_BURST, uploadsPerMinute);
            fetches = new TokenBucket(FETCH_BURST, FETCHES_PER_MINUTE);
        }
    }

    private static final class TokenBucket {
        private final double capacity;
        private final double refillPerMillisecond;
        private double tokens;
        private long lastEpochMs;

        private TokenBucket(final int capacity, final int refillPerMinute) {
            this.capacity = capacity;
            refillPerMillisecond = (double) refillPerMinute / 60_000d;
            tokens = capacity;
        }

        private boolean tryConsume(final long nowEpochMs) {
            if (nowEpochMs > lastEpochMs) {
                tokens = Math.min(capacity, tokens + (nowEpochMs - lastEpochMs) * refillPerMillisecond);
                lastEpochMs = nowEpochMs;
            }
            if (tokens < 1d) {
                return false;
            }
            tokens -= 1d;
            return true;
        }
    }
}
