package cn.net.rms.confluxmap.core.util;

/** Shared easing curves; one implementation so every UI animation decelerates identically. */
public final class Easing {
    private Easing() {
    }

    /** Cubic ease-out in [0, 1]; out-of-range inputs clamp first. */
    public static float easeOutCubic(final float progress) {
        final float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        final float remaining = 1.0f - clamped;
        return 1.0f - remaining * remaining * remaining;
    }

    /**
     * Back ease-out in [0, 1]: overshoots to roughly 1.1, then settles back onto 1 so
     * translations land with a rebound; out-of-range inputs clamp first. Positions only —
     * alpha must stay in [0, 1], which the cubic above guarantees.
     */
    public static float easeOutBack(final float progress) {
        final float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        // The canonical CSS easeOutBack constants; c1 tunes the overshoot size.
        final float c1 = 1.70158f;
        final float c3 = c1 + 1.0f;
        final float remaining = clamped - 1.0f;
        return 1.0f + c3 * remaining * remaining * remaining + c1 * remaining * remaining;
    }
}
