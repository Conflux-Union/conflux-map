package cn.net.rms.confluxmap.mc.ui.anim;

/** Pure easing and speed-tier math shared by the interface transitions. */
public final class UiMotion {
    /** Reveal durations per speed tier, in seconds. */
    public static final float FAST = 0.12f;
    public static final float STANDARD = 0.18f;
    public static final float RELAXED = 0.26f;

    private UiMotion() {
    }

    /** Decelerating ease in [0, 1]; out-of-range inputs clamp first. */
    public static float easeOutCubic(final float progress) {
        final float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        final float remaining = 1.0f - clamped;
        return 1.0f - remaining * remaining * remaining;
    }

    /**
     * Moves {@code progress} toward 1 by {@code deltaSeconds} worth of {@code durationSeconds},
     * clamped to [0, 1]; a non-positive duration jumps straight to 1.
     */
    public static float advance(
        final float progress,
        final float deltaSeconds,
        final float durationSeconds
    ) {
        if (durationSeconds <= 0.0f) {
            return 1.0f;
        }
        return Math.max(0.0f, Math.min(1.0f, progress + Math.max(0.0f, deltaSeconds) / durationSeconds));
    }

    /**
     * Duration for a tier name; matches the {@code ConfluxConfig.UiTransitionSpeed} enum
     * names, with unknown or null tiers falling back to {@link #STANDARD}.
     */
    public static float durationSeconds(final String tier) {
        if ("FAST".equals(tier)) {
            return FAST;
        }
        if ("RELAXED".equals(tier)) {
            return RELAXED;
        }
        return STANDARD;
    }
}
