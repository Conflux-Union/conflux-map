package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping vanilla status-effect icons clear of the minimap. */
public final class StatusEffectHudAvoidance {
    private static final int ICON_SIZE = 24;
    private static final int ICON_STEP = 25;
    private static final int ROW_STEP = 26;
    private static final int GAP = 4;

    private StatusEffectHudAvoidance() {
    }

    /**
     * Returns the horizontal translation to apply to the complete vanilla effect overlay.
     * Beneficial and harmful effects use separate rows, so only occupied row rectangles count.
     */
    public static int horizontalShift(
        final int screenWidth,
        final int topY,
        final int beneficialCount,
        final int harmfulCount,
        final MinimapPlacement.Layout minimap
    ) {
        if (minimap == null || screenWidth <= 0) {
            return 0;
        }
        final boolean overlapsBeneficial = overlapsRow(
            screenWidth, topY, Math.max(0, beneficialCount), minimap
        );
        final boolean overlapsHarmful = overlapsRow(
            screenWidth, topY + ROW_STEP, Math.max(0, harmfulCount), minimap
        );
        if (!overlapsBeneficial && !overlapsHarmful) {
            return 0;
        }
        return Math.min(0, minimap.x() - GAP - screenWidth);
    }

    private static boolean overlapsRow(
        final int screenWidth,
        final int rowY,
        final int iconCount,
        final MinimapPlacement.Layout minimap
    ) {
        if (iconCount == 0) {
            return false;
        }
        final long left = (long) screenWidth - (long) ICON_STEP * iconCount;
        final long right = screenWidth;
        final long bottom = (long) rowY + ICON_SIZE;
        final long minimapRight = (long) minimap.x() + minimap.size();
        final long minimapBottom = (long) minimap.y() + minimap.size();
        return left < minimapRight
            && right > minimap.x()
            && rowY < minimapBottom
            && bottom > minimap.y();
    }
}
