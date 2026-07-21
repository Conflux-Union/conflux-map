package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.TokenBucket;

/**
 * Per-player request and bandwidth budget. Queue-occupancy limits live in
 * {@link PatchDispatcher}; this class owns only request spacing and the byte token bucket.
 */
public final class PlayerBudget {
    private final TokenBucket bytes;
    private final long minIntervalNanos;
    private long lastRequestNanos = Long.MIN_VALUE;

    public PlayerBudget(final int bytesPerSecond, final int minRequestIntervalMs) {
        this.bytes = new TokenBucket(Math.max(1, bytesPerSecond), Math.max(1, bytesPerSecond));
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
