package cn.net.rms.confluxmap.core.config;

/** Pure screen-space policy for keeping vanilla status-effect icons clear of the minimap. */
public final class StatusEffectHudAvoidance {
    private static final int GAP = 4;

    private StatusEffectHudAvoidance() {
    }

    /**
     * Returns the horizontal translation to apply to the complete effect overlay, in screen
     * pixels. Beneficial and harmful effects use separate rows, so only occupied rows count, and
     * an empty row is passed as {@code null}.
     *
     * <p>The overlay is never moved so far left that it would leave the screen; in that case the
     * icons stay where vanilla put them.
     */
    public static int horizontalShift(
        final MinimapPlacement.Layout minimap,
        final HudRect beneficialRow,
        final HudRect harmfulRow
    ) {
        if (minimap == null) {
            return 0;
        }
        final HudRect rows = union(beneficialRow, harmfulRow);
        if (rows == null) {
            return 0;
        }
        final HudRect map = new HudRect(
            minimap.x(),
            minimap.y(),
            minimap.x() + minimap.size(),
            minimap.y() + minimap.size()
        );
        final boolean overlaps = (beneficialRow != null && beneficialRow.overlaps(map))
            || (harmfulRow != null && harmfulRow.overlaps(map));
        if (!overlaps) {
            return 0;
        }
        final int shift = Math.min(0, map.left() - GAP - rows.right());
        return rows.left() + shift < 0 ? 0 : shift;
    }

    private static HudRect union(final HudRect first, final HudRect second) {
        if (first == null) {
            return second;
        }
        return first.union(second);
    }
}
