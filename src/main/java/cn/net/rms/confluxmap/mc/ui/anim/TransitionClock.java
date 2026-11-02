package cn.net.rms.confluxmap.mc.ui.anim;

import java.util.function.LongSupplier;

/**
 * Wall-clock frame delta source for render-loop animations. One instance per consumer;
 * the first {@link #tick()} starts the clock and returns zero so the frame that creates
 * the clock never advances an animation.
 */
public final class TransitionClock {
    /**
     * A longer step means the frame stalled; letting it through would skip the whole
     * reveal, so stalls count as at most this much animation time (same cap as the
     * waypoint HUD's delta source).
     */
    private static final float MAX_DELTA_SECONDS = 0.1f;

    private final LongSupplier nanoTime;
    private long lastNanos;
    private boolean started;

    public TransitionClock() {
        this(System::nanoTime);
    }

    TransitionClock(final LongSupplier nanoTime) {
        this.nanoTime = nanoTime;
    }

    /**
     * Clears the running delta sequence so the next {@link #tick()} starts fresh and
     * returns zero, instead of charging a replayed transition with the idle time that
     * passed since the previous animation stopped.
     */
    public void reset() {
        started = false;
    }

    /** Seconds since the previous call, capped at {@value #MAX_DELTA_SECONDS}s; first call returns 0. */
    public float tick() {
        final long now = nanoTime.getAsLong();
        if (!started) {
            started = true;
            lastNanos = now;
            return 0.0f;
        }
        final float delta = (now - lastNanos) / 1_000_000_000.0f;
        lastNanos = now;
        return Math.max(0.0f, Math.min(MAX_DELTA_SECONDS, delta));
    }
}
