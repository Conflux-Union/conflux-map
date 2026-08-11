package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.core.config.ScoreboardHudAvoidance;

/** Captures vanilla's scoreboard bounds while retaining the last complete HUD frame. */
public final class ScoreboardHudBounds {
    private static final Frame EMPTY = new Frame(
        -1,
        -1,
        null,
        ScoreboardHudAvoidance.Transform.IDENTITY
    );
    private static volatile Frame previous = EMPTY;
    private static int currentScreenWidth = -1;
    private static int currentScreenHeight = -1;
    private static ScoreboardHudAvoidance.Bounds currentBounds;
    private static ScoreboardHudAvoidance.Transform currentAppliedTransform =
        ScoreboardHudAvoidance.Transform.IDENTITY;

    private ScoreboardHudBounds() {
    }

    public static void beginFrame(final int screenWidth, final int screenHeight) {
        previous = new Frame(
            currentScreenWidth,
            currentScreenHeight,
            currentBounds,
            currentAppliedTransform
        );
        currentScreenWidth = screenWidth;
        currentScreenHeight = screenHeight;
        currentBounds = null;
        currentAppliedTransform = ScoreboardHudAvoidance.Transform.IDENTITY;
    }

    public static void include(final int x1, final int y1, final int x2, final int y2) {
        final ScoreboardHudAvoidance.Bounds painted = new ScoreboardHudAvoidance.Bounds(
            Math.min(x1, x2), Math.min(y1, y2), Math.max(x1, x2), Math.max(y1, y2)
        );
        final ScoreboardHudAvoidance.Bounds existing = currentBounds;
        currentBounds = existing == null
            ? painted
            : new ScoreboardHudAvoidance.Bounds(
                Math.min(existing.left(), painted.left()),
                Math.min(existing.top(), painted.top()),
                Math.max(existing.right(), painted.right()),
                Math.max(existing.bottom(), painted.bottom())
            );
    }

    /** Returns the last complete frame only when it uses the current scaled viewport. */
    public static ScoreboardHudAvoidance.Bounds previousFrame(
        final int screenWidth,
        final int screenHeight
    ) {
        final Frame frame = previous;
        return frame.screenWidth() == screenWidth && frame.screenHeight() == screenHeight
            ? frame.bounds()
            : null;
    }

    public static void recordAppliedTransform(final ScoreboardHudAvoidance.Transform transform) {
        currentAppliedTransform = transform;
    }

    /** Returns the transform applied during the last complete frame for the current viewport. */
    public static ScoreboardHudAvoidance.Transform previousAppliedTransform(
        final int screenWidth,
        final int screenHeight
    ) {
        final Frame frame = previous;
        return frame.screenWidth() == screenWidth && frame.screenHeight() == screenHeight
            ? frame.transform()
            : ScoreboardHudAvoidance.Transform.IDENTITY;
    }

    private record Frame(
        int screenWidth,
        int screenHeight,
        ScoreboardHudAvoidance.Bounds bounds,
        ScoreboardHudAvoidance.Transform transform
    ) {
    }
}
