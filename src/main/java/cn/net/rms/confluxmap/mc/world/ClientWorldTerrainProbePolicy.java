package cn.net.rms.confluxmap.mc.world;

/** Limits incomplete 3x3 terrain probes so recognition never consumes unbounded client work. */
final class ClientWorldTerrainProbePolicy {
    static final int RETRY_INTERVAL_TICKS = 20;
    static final int MAX_ATTEMPTS = 5;

    private long lastAttemptTick = Long.MIN_VALUE;
    private int attempts;

    boolean shouldProbe(final long tick) {
        return attempts < MAX_ATTEMPTS && (lastAttemptTick == Long.MIN_VALUE
            || tick - lastAttemptTick >= RETRY_INTERVAL_TICKS);
    }

    void recordAttempt(final long tick) {
        if (!shouldProbe(tick)) {
            throw new IllegalStateException("terrain probe attempted outside the configured rate limit");
        }
        lastAttemptTick = tick;
        attempts++;
    }

    boolean exhausted() {
        return attempts >= MAX_ATTEMPTS;
    }

    void reset() {
        lastAttemptTick = Long.MIN_VALUE;
        attempts = 0;
    }
}
