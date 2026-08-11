package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping the vanilla scoreboard clear of the minimap. */
public final class ScoreboardHudAvoidance {
    private static final int GAP = 4;

    private ScoreboardHudAvoidance() {
    }

    /** Returns the horizontal translation for the complete vanilla scoreboard sidebar. */
    public static int horizontalShift(
        final int screenHeight,
        final MinimapPlacement.Layout minimap,
        final int informationHeight,
        final Bounds scoreboard
    ) {
        if (minimap == null || scoreboard == null) {
            return 0;
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
            return 0;
        }

        final int shift = Math.min(0, minimap.x() - GAP - scoreboard.right());
        return scoreboard.left() + shift < 0 ? 0 : shift;
    }

    /** A rectangle in scaled GUI coordinates, with exclusive right and bottom edges. */
    public record Bounds(int left, int top, int right, int bottom) {
        public Bounds {
            if (right < left || bottom < top) {
                throw new IllegalArgumentException("bounds must not have negative dimensions");
            }
        }
    }
}
