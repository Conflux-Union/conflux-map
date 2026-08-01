package cn.net.rms.confluxmap.core.export;

/** Inclusive block-coordinate rectangle selected for export. */
public record MapExportBounds(int minX, int minZ, int maxX, int maxZ) {
    private static final long MAX_PNG_EDGE = Integer.MAX_VALUE;

    public MapExportBounds {
        if (minX > maxX || minZ > maxZ) {
            throw new IllegalArgumentException("Export bounds must be normalized");
        }
    }

    public static MapExportBounds between(
        final int firstX,
        final int firstZ,
        final int secondX,
        final int secondZ
    ) {
        return new MapExportBounds(
            Math.min(firstX, secondX),
            Math.min(firstZ, secondZ),
            Math.max(firstX, secondX),
            Math.max(firstZ, secondZ)
        );
    }

    public long blockWidth() {
        return (long) maxX - minX + 1L;
    }

    public long blockHeight() {
        return (long) maxZ - minZ + 1L;
    }

    public int pixelWidth(final MapExportResolution resolution) {
        return pngEdge(blockWidth(), resolution.blocksPerPixel());
    }

    public int pixelHeight(final MapExportResolution resolution) {
        return pngEdge(blockHeight(), resolution.blocksPerPixel());
    }

    public long pixelCount(final MapExportResolution resolution) {
        return Math.multiplyExact(
            (long) pixelWidth(resolution),
            pixelHeight(resolution)
        );
    }

    private static int pngEdge(final long blocks, final int blocksPerPixel) {
        final long pixels = (blocks + blocksPerPixel - 1L) / blocksPerPixel;
        if (pixels < 1L || pixels > MAX_PNG_EDGE) {
            throw new IllegalArgumentException("PNG edge is outside the supported range: " + pixels);
        }
        return (int) pixels;
    }
}
