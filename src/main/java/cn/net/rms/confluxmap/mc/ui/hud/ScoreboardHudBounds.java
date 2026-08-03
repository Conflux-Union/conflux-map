package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.MinimapHudAvoidance;

/** Captures the exact scaled bounds painted by vanilla's scoreboard sidebar during the current HUD frame. */
public final class ScoreboardHudBounds {
    private static volatile MinimapHudAvoidance.Bounds current;

    private ScoreboardHudBounds() {
    }

    public static void beginFrame() {
        current = null;
    }

    public static void include(final int x1, final int y1, final int x2, final int y2) {
        final MinimapHudAvoidance.Bounds painted = new MinimapHudAvoidance.Bounds(
            Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)
        );
        final MinimapHudAvoidance.Bounds existing = current;
        current = existing == null ? painted : new MinimapHudAvoidance.Bounds(
            Math.min(existing.left(), painted.left()),
            Math.min(existing.top(), painted.top()),
            Math.max(existing.right(), painted.right()),
            Math.max(existing.bottom(), painted.bottom())
        );
    }

    public static MinimapHudAvoidance.Bounds current() {
        return current;
    }
}
