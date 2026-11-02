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
}
