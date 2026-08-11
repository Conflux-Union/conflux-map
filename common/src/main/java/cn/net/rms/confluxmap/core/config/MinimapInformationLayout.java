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
}
