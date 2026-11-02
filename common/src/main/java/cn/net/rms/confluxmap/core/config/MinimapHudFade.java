package cn.net.rms.confluxmap.core.config;

/**
 * Pure alpha state for the minimap HUD's show/hide fade, companion to
 * {@link MinimapHudVisibility}: the visibility policy names the target, this animates
 * toward it. While alpha is between 0 and 1 the HUD keeps rendering (with everything
 * scaled by {@link #alpha()}) so the capture viewport and HUD avoidance stay continuous;
 * the hard-hide cleanup only runs once the fade settles at 0. Minecraft-free: callers
 * supply the frame delta.
 */
public final class MinimapHudFade {
    private float alpha;

    /** Starts fully hidden so the first appearance animates like every later one. */
    public MinimapHudFade() {
    }

    /**
     * Advances alpha toward {@code visible ? 1 : 0} by {@code deltaSeconds} worth of
     * {@code durationSeconds}. An {@code instant} tick (or a non-positive duration) jumps
     * straight to the target - used when transitions are disabled and when a session switch
     * must never blend one world's map into the next. A settled tick is a no-op.
     */
    public void tick(
        final boolean visible,
        final boolean instant,
        final float deltaSeconds,
        final float durationSeconds
    ) {
        final float target = visible ? 1.0f : 0.0f;
        if (instant || durationSeconds <= 0.0f || alpha == target) {
            alpha = target;
            return;
        }
        final float step = Math.max(0.0f, deltaSeconds) / durationSeconds;
        alpha = visible
            ? Math.min(target, alpha + step)
            : Math.max(target, alpha - step);
    }

    public float alpha() {
        return alpha;
    }

    /** True when no animation is in flight (alpha sits exactly at 0 or 1). */
    public boolean settled() {
        return alpha <= 0.0f || alpha >= 1.0f;
    }
}
