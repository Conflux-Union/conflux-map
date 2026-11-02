package cn.net.rms.confluxmap.mc.ui.anim;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.util.Easing;

/** Speed-tier math for the interface transitions; easing lives in {@link Easing}. */
public final class UiMotion {
    /** Reveal durations per speed tier, in seconds. */
    public static final float FAST = 0.12f;
    public static final float STANDARD = 0.18f;
    public static final float RELAXED = 0.26f;

    private UiMotion() {
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
     * Duration for a speed tier; a null tier falls back to {@link #STANDARD} (config
     * normalization also guarantees a non-null value).
     */
    public static float durationSeconds(final ConfluxConfig.UiTransitionSpeed tier) {
        if (tier == ConfluxConfig.UiTransitionSpeed.FAST) {
            return FAST;
        }
        if (tier == ConfluxConfig.UiTransitionSpeed.RELAXED) {
            return RELAXED;
        }
        return STANDARD;
    }
}
