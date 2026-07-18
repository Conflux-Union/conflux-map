package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.TokenBucket;

/** Per-player request and bandwidth budget. */
public final class PlayerBudget {
    private final TokenBucket bytes;
    private final int maxPending;
    private final long minIntervalNanos;
    private int pending;
    private long lastRequestNanos = Long.MIN_VALUE;

    public PlayerBudget(final int bytesPerSecond, final int maxPending, final int minRequestIntervalMs) {
        this.bytes = new TokenBucket(Math.max(1, bytesPerSecond), Math.max(1, bytesPerSecond));
        this.maxPending = Math.max(1, maxPending);
        this.minIntervalNanos = Math.max(0L, minRequestIntervalMs) * 1_000_000L;
    }

    public synchronized boolean beginRequest(final long nowNanos) {
        if (pending >= maxPending || (lastRequestNanos != Long.MIN_VALUE && nowNanos - lastRequestNanos < minIntervalNanos)) {
            return false;
        }
        pending++;
        lastRequestNanos = nowNanos;
        return true;
    }

    public synchronized void finishRequest() {
        if (pending > 0) {
            pending--;
        }
    }

    public boolean allowBytes(final int amount, final long nowNanos) {
        return bytes.tryConsume(amount, nowNanos);
    }

    public synchronized int pending() {
        return pending;
    }
}
