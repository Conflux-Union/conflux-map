package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping the vanilla scoreboard clear of the minimap. */
public final class ScoreboardHudAvoidance {
    private static final int GAP = 4;

    private ScoreboardHudAvoidance() {
    }

    /** Resolves the transform for the complete vanilla scoreboard sidebar. */
    public static HudTransform resolve(
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final HudRect scoreboard
    ) {
        if (minimap == null || scoreboard == null) {
            return HudTransform.IDENTITY;
        }
        final HudRect footprint = MinimapInformationLayout.visualBounds(
            minimap, screenHeight, informationHeight
        );
        if (!scoreboard.overlaps(footprint)) {
            return HudTransform.IDENTITY;
        }

        final int targetTop = footprint.bottom() + GAP;
        final int scoreboardHeight = scoreboard.height();
        final int availableHeight = screenHeight - GAP - targetTop;
        if (availableHeight <= 0) {
            return HudTransform.IDENTITY;
        }
        final float scale = scoreboardHeight <= availableHeight || scoreboardHeight == 0
            ? 1f
            : (float) availableHeight / scoreboardHeight;
        final float translateX = scoreboard.right() * (1f - scale);
        final float translateY = targetTop - scoreboard.top() * scale;
        return new HudTransform(translateX, translateY, scale);
    }
}
