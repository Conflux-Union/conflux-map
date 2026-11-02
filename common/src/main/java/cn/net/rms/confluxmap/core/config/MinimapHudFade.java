package cn.net.rms.confluxmap.core.config;

import cn.net.rms.confluxmap.core.util.Easing;

/**
 * Pure alpha state for the minimap HUD's show/hide fade, companion to
 * {@link MinimapHudVisibility}: the visibility policy names the target, this animates
 * toward it. While alpha is between 0 and 1 the HUD keeps rendering (with everything
 * scaled by {@link #alpha()}) so the capture viewport and HUD avoidance stay continuous;
 * the hard-hide cleanup only runs once the fade settles at 0. Progress advances linearly
 * and only the output eases, matching the screens' reveal curve. Minecraft-free: callers
 * supply the frame delta.
 */
public final class MinimapHudFade {
    private float progress;

    /** Starts fully hidden so the first appearance animates like every later one. */
    public MinimapHudFade() {
    }

    /**
     * Advances progress toward {@code visible ? 1 : 0} by {@code deltaSeconds} worth of
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
        if (instant || durationSeconds <= 0.0f || progress == target) {
            progress = target;
            return;
        }
        final float step = Math.max(0.0f, deltaSeconds) / durationSeconds;
        progress = visible
            ? Math.min(target, progress + step)
            : Math.max(target, progress - step);
    }

    /** Eased alpha in [0, 1]; 1 fully shown, 0 fully hidden. */
    public float alpha() {
        return Easing.easeOutCubic(progress);
    }
}
