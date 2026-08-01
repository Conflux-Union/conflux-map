package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.TokenBucket;

/**
 * Per-player request and bandwidth budget. Queue-occupancy limits live in
 * {@link PatchDispatcher}; this class owns only request spacing and the byte token bucket.
 */
public final class PlayerBudget {
    /** Compressed patch body plus the fixed MAP_PATCH envelope. */
    static final int MAX_PATCH_WIRE_BYTES = PatchCodec.MAX_COMPRESSED_BYTES + 64;

    private final TokenBucket bytes;
    private final long minIntervalNanos;
    private long lastRequestNanos = Long.MIN_VALUE;

    public PlayerBudget(final int bytesPerSecond, final int minRequestIntervalMs) {
        final int refillRate = Math.max(1, bytesPerSecond);
        // A rate bucket must be large enough for one legal atomic packet. Otherwise a raw residual
        // larger than one second's allowance can never leave the FIFO, regardless of wait time.
        this.bytes = new TokenBucket(Math.max(refillRate, MAX_PATCH_WIRE_BYTES), refillRate);
        this.minIntervalNanos = Math.max(0L, minRequestIntervalMs) * 1_000_000L;
    }

    public synchronized boolean beginRequest(final long nowNanos) {
        if (lastRequestNanos != Long.MIN_VALUE && nowNanos - lastRequestNanos < minIntervalNanos) {
            return false;
        }
        lastRequestNanos = nowNanos;
        return true;
    }

    public boolean allowBytes(final int amount, final long nowNanos) {
        return bytes.tryConsume(amount, nowNanos);
    }
}
