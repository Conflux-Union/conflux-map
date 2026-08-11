package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping the vanilla scoreboard clear of the minimap. */
public final class ScoreboardHudAvoidance {
    private static final int GAP = 4;

    private ScoreboardHudAvoidance() {
    }

    /** Resolves the transform for the complete vanilla scoreboard sidebar. */
    public static Transform resolve(
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final Bounds scoreboard
    ) {
        if (minimap == null || scoreboard == null) {
            return Transform.IDENTITY;
        }
        final int safeInformationHeight = Math.max(0, informationHeight);
        final int minimapTop = minimap.y() + minimap.size() + safeInformationHeight <= screenHeight
            ? minimap.y()
            : Math.max(0, minimap.y() - safeInformationHeight);
        final int minimapBottom = minimap.y() + minimap.size()
            + (minimapTop == minimap.y() ? safeInformationHeight : 0);
        final int minimapRight = minimap.x() + minimap.size();
        final boolean overlaps = scoreboard.left() < minimapRight
            && scoreboard.right() > minimap.x()
            && scoreboard.top() < minimapBottom
            && scoreboard.bottom() > minimapTop;
        if (!overlaps) {
            return Transform.IDENTITY;
        }

        final int targetTop = minimapBottom + GAP;
        final int scoreboardHeight = scoreboard.bottom() - scoreboard.top();
        final int availableHeight = screenHeight - GAP - targetTop;
        if (availableHeight <= 0) {
            return Transform.IDENTITY;
        }
        final float scale = scoreboardHeight <= availableHeight || scoreboardHeight == 0
            ? 1f
            : (float) availableHeight / scoreboardHeight;
        final float translateX = scoreboard.right() * (1f - scale);
        final float translateY = targetTop - scoreboard.top() * scale;
        return new Transform(translateX, translateY, scale);
    }

    /** A rectangle in scaled GUI coordinates, with exclusive right and bottom edges. */
    public record Bounds(int left, int top, int right, int bottom) {
        public Bounds {
            if (right < left || bottom < top) {
                throw new IllegalArgumentException("bounds must not have negative dimensions");
            }
        }
    }

    /** An affine transform applied to the complete scoreboard around the GUI origin. */
    public record Transform(float translateX, float translateY, float scale) {
        public static final Transform IDENTITY = new Transform(0f, 0f, 1f);

        public boolean isIdentity() {
            return equals(IDENTITY);
        }
    }
}
