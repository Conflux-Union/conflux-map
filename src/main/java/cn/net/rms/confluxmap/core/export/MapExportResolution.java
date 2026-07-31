package cn.net.rms.confluxmap.core.export;

/** Globally-aligned map sampling resolutions supported by PNG export. */
public enum MapExportResolution {
    ONE_BLOCK(0),
    TWO_BLOCKS(1),
    FOUR_BLOCKS(2),
    EIGHT_BLOCKS(3),
    SIXTEEN_BLOCKS(4);

    private final int lod;

    MapExportResolution(final int lod) {
        this.lod = lod;
    }

    public int lod() {
        return lod;
    }

    public int blocksPerPixel() {
        return 1 << lod;
    }

    public static MapExportResolution forLod(final int lod) {
        for (final MapExportResolution resolution : values()) {
            if (resolution.lod == lod) {
                return resolution;
            }
        }
        throw new IllegalArgumentException("Unsupported export LOD: " + lod);
    }
}
