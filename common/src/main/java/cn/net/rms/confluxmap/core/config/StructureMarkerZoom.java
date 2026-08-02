package cn.net.rms.confluxmap.core.config;

/** Lowest fullscreen-map zoom at which ordinary predicted structure markers remain visible. */
public enum StructureMarkerZoom {
    ZOOM_0_25(4.0),
    ZOOM_0_125(8.0),
    ZOOM_0_0625(16.0),
    ALWAYS(Double.POSITIVE_INFINITY);

    private final double maxBlocksPerPixel;

    StructureMarkerZoom(final double maxBlocksPerPixel) {
        this.maxBlocksPerPixel = maxBlocksPerPixel;
    }

    public boolean displaysAt(final double blocksPerPixel) {
        return Double.isFinite(blocksPerPixel)
            && blocksPerPixel > 0.0
            && blocksPerPixel <= maxBlocksPerPixel;
    }
}
