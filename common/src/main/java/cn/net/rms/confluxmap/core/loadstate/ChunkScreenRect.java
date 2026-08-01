package cn.net.rms.confluxmap.core.loadstate;

/** Screen-space bounds of one server chunk under the fullscreen map's world transform. */
public record ChunkScreenRect(double x, double y, double size) {
    public static ChunkScreenRect forChunk(
        final int chunkX,
        final int chunkZ,
        final double centerBlockX,
        final double centerBlockZ,
        final int viewportWidth,
        final int viewportHeight,
        final double blocksPerPixel
    ) {
        final double size = chunkSize(blocksPerPixel);
        final double pixelsPerBlock = size / 16.0;
        final double chunkBlockX = (long) chunkX * 16L;
        final double chunkBlockZ = (long) chunkZ * 16L;
        return new ChunkScreenRect(
            viewportWidth / 2.0 + (chunkBlockX - centerBlockX) * pixelsPerBlock,
            viewportHeight / 2.0 + (chunkBlockZ - centerBlockZ) * pixelsPerBlock,
            size
        );
    }

    public static double chunkSize(final double blocksPerPixel) {
        if (!(blocksPerPixel > 0.0) || !Double.isFinite(blocksPerPixel)) {
            throw new IllegalArgumentException("blocksPerPixel must be finite and positive");
        }
        return 16.0 / blocksPerPixel;
    }

    public double right() {
        return x + size;
    }

    public double bottom() {
        return y + size;
    }
}
