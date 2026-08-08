package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.MinimapHudAvoidance;

/** Captures vanilla's scoreboard bounds while retaining the last complete HUD frame. */
public final class ScoreboardHudBounds {
    private static final Frame EMPTY = new Frame(-1, -1, null);
    private static volatile Frame previous = EMPTY;
    private static int currentScreenWidth = -1;
    private static int currentScreenHeight = -1;
    private static MinimapHudAvoidance.Bounds currentBounds;

    private ScoreboardHudBounds() {
    }

    public static void beginFrame(final int screenWidth, final int screenHeight) {
        previous = new Frame(currentScreenWidth, currentScreenHeight, currentBounds);
        currentScreenWidth = screenWidth;
        currentScreenHeight = screenHeight;
        currentBounds = null;
    }

    public static void include(final int x1, final int y1, final int x2, final int y2) {
        final MinimapHudAvoidance.Bounds painted = new MinimapHudAvoidance.Bounds(
            Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)
        );
        final MinimapHudAvoidance.Bounds existing = currentBounds;
        currentBounds = existing == null
            ? painted
            : new MinimapHudAvoidance.Bounds(
                Math.min(existing.left(), painted.left()),
                Math.min(existing.top(), painted.top()),
                Math.max(existing.right(), painted.right()),
                Math.max(existing.bottom(), painted.bottom())
            );
    }

    /** Returns the last complete frame only when it uses the current scaled viewport. */
    public static MinimapHudAvoidance.Bounds previousFrame(
        final int screenWidth,
        final int screenHeight
    ) {
        final Frame frame = previous;
        return frame.screenWidth() == screenWidth && frame.screenHeight() == screenHeight
            ? frame.bounds()
            : null;
    }

    private record Frame(int screenWidth, int screenHeight, MinimapHudAvoidance.Bounds bounds) {
    }
}
