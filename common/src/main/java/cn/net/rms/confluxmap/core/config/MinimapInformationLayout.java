package cn.net.rms.confluxmap.core.config;

/** Shared screen-space metrics for the information lines rendered beside the minimap. */
public final class MinimapInformationLayout {
    public static final int GAP = 3;
    public static final int LINE_HEIGHT = 10;

    private MinimapInformationLayout() {
    }

    /** Returns the complete outside-frame height, including the gap next to the minimap. */
    public static int height(
        final boolean showCoordinates,
        final boolean showBiome,
        final boolean showLayerIndicator
    ) {
        int lines = 0;
        if (showCoordinates) {
            lines++;
        }
        if (showBiome) {
            lines++;
        }
        if (showLayerIndicator) {
            lines++;
        }
        return lines == 0 ? 0 : GAP + lines * LINE_HEIGHT;
    }

    /** Returns the complete visible footprint, including information text below or above the map. */
    public static HudRect visualBounds(
        final MinimapPlacement.Layout layout,
        final int screenHeight,
        final int informationHeight
    ) {
        if (layout == null) {
            throw new IllegalArgumentException("layout must not be null");
        }
        final int safeInformationHeight = Math.max(0, informationHeight);
        if (safeInformationHeight == 0) {
            return new HudRect(layout.x(), layout.y(), layout.x() + layout.size(), layout.y() + layout.size());
        }
        final int belowBottom = layout.y() + layout.size() + safeInformationHeight;
        if (belowBottom <= screenHeight) {
            return new HudRect(layout.x(), layout.y(), layout.x() + layout.size(), belowBottom);
        }
        return new HudRect(
            layout.x(), Math.max(0, layout.y() - safeInformationHeight),
            layout.x() + layout.size(), layout.y() + layout.size()
        );
    }
}
