package cn.net.rms.confluxmap.core.net;

/** Monotonic-time token bucket used for per-player bandwidth budgets. */
public final class TokenBucket {
    private final long capacity;
    private final double refillPerNanosecond;
    private long tokens;
    private long lastNanos;

    public TokenBucket(final long capacity, final long refillPerSecond) {
        this(capacity, refillPerSecond, System.nanoTime());
    }

    public TokenBucket(final long capacity, final long refillPerSecond, final long nowNanos) {
        if (capacity <= 0 || refillPerSecond <= 0) {
            throw new IllegalArgumentException("token bucket rates must be positive");
        }
        this.capacity = capacity;
        this.refillPerNanosecond = (double) refillPerSecond / 1_000_000_000d;
        this.tokens = capacity;
        this.lastNanos = nowNanos;
    }

    public synchronized boolean tryConsume(final long amount) {
        return tryConsume(amount, System.nanoTime());
    }

    public synchronized boolean tryConsume(final long amount, final long nowNanos) {
        if (amount < 0 || amount > capacity) {
            return false;
        }
        refill(nowNanos);
        if (tokens < amount) {
            return false;
        }
        tokens -= amount;
        return true;
    }

    public synchronized long available(final long nowNanos) {
        refill(nowNanos);
        return tokens;
    }

    private void refill(final long nowNanos) {
        if (nowNanos <= lastNanos) {
            return;
        }
        final long elapsed = nowNanos - lastNanos;
        final long refill = (long) (elapsed * refillPerNanosecond);
        if (refill > 0) {
            tokens = Math.min(capacity, tokens + refill);
            lastNanos = nowNanos;
        }
    }
}
