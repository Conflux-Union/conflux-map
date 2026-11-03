package cn.net.rms.confluxmap.core.util;

/**
 * Slices one shared progress value into per-item progress, so a group of UI elements can
 * fly with a stagger while every element finishes together.
 */
public final class Stagger {
    private Stagger() {
    }

    /**
     * Progress for item {@code index} of {@code count}, carved out of the shared
     * {@code progress}: each item starts later by {@code staggerFraction} of the timeline,
     * and the slices compress each item's own flight by the same factor. All items reach 1
     * together at {@code progress} 1; the result is clamped to [0, 1].
     */
    public static float slice(
        final float progress,
        final int index,
        final int count,
        final float staggerFraction
    ) {
        final float spread = 1.0f + staggerFraction * (count - 1);
        return Math.max(0.0f, Math.min(1.0f, progress * spread - index * staggerFraction));
    }
}
