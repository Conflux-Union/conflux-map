package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.HudRect;
import cn.net.rms.confluxmap.core.config.HudTransform;

/** Captures the vanilla scoreboard sidebar's on-screen bounds, one frame at a time. */
public final class ScoreboardHudBounds {
    private static final HudElementBounds SIDEBAR = new HudElementBounds();

    private ScoreboardHudBounds() {
    }

    public static void beginFrame(final int screenWidth, final int screenHeight) {
        SIDEBAR.beginFrame(screenWidth, screenHeight);
    }

    public static void include(final float x1, final float y1, final float x2, final float y2) {
        SIDEBAR.include(x1, y1, x2, y2);
    }

    public static HudRect previousFrame(final int screenWidth, final int screenHeight) {
        return SIDEBAR.previousFrame(screenWidth, screenHeight);
    }

    public static void recordAppliedTransform(final HudTransform transform) {
        SIDEBAR.recordAppliedTransform(transform);
    }

    public static HudTransform previousAppliedTransform(
        final int screenWidth,
        final int screenHeight
    ) {
        return SIDEBAR.previousAppliedTransform(screenWidth, screenHeight);
    }
}
