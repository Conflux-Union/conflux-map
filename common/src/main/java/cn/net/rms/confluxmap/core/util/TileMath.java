package cn.net.rms.confluxmap.core.util;

/**
 * Coordinate conversions between blocks, chunks, tiles and regions.
 * All conversions use floor semantics so negative coordinates behave correctly.
 * One tile/region covers 256x256 blocks (16x16 chunks) at LOD 0.
 */
public final class TileMath {
    public static final int TILE_SIZE = 256;
    public static final int TILE_SHIFT = 8;
    public static final int CHUNKS_PER_TILE = 16;
    public static final int MAX_LOD = 4;

    private TileMath() {
    }

    public static int blockToChunk(final int block) {
        return block >> 4;
    }

    public static int blockToTile(final int block) {
        return block >> TILE_SHIFT;
    }

    public static int chunkToTile(final int chunk) {
        return chunk >> 4;
    }

    /** Block offset within its tile, always in [0, 255]. */
    public static int blockInTile(final int block) {
        return block & (TILE_SIZE - 1);
    }

    /** Chunk offset within its tile, always in [0, 15]. */
    public static int chunkInTile(final int chunk) {
        return chunk & (CHUNKS_PER_TILE - 1);
    }

    /** Blocks covered by one pixel at the given LOD. */
    public static int blocksPerPixel(final int lod) {
        return 1 << lod;
    }

    /** Blocks covered by one tile edge at the given LOD. */
    public static int blocksPerTile(final int lod) {
        return TILE_SIZE << lod;
    }

    /**
     * Chooses the finest useful LOD whose texel is no larger than one screen pixel. Keeping the
     * selected texture at full resolution avoids the two-pixel texel expansion and visible blur
     * that otherwise occurs immediately after crossing an LOD boundary.
     */
    public static int lodForScale(final double blocksPerScreenPixel) {
        return lodForScale(blocksPerScreenPixel, cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality.BALANCED);
    }

    /**
     * Chooses LOD for the given scale taking into account player's {@link ConfluxConfig.MapQuality}.
     * When zoomed in (blocksPerScreenPixel <= 1.0), the result is strictly clamped to LOD 0 regardless
     * of quality setting, ensuring pixel-perfect fidelity when zooming close.
     * When zoomed out (blocksPerScreenPixel > 1.0), the quality setting scales the slope (loadline)
     * of the logarithmic decay curve.
     */
    public static int lodForScale(
        final double blocksPerScreenPixel,
        final cn.net.rms.confluxmap.core.config.ConfluxConfig.MapQuality quality
    ) {
        if (!Double.isFinite(blocksPerScreenPixel) || blocksPerScreenPixel <= 1.0) {
            return 0;
        }
        final double slope = quality != null ? quality.slope() : 1.0;
        final double exact = Math.log(blocksPerScreenPixel) / Math.log(2.0);
        final int lod = (int) Math.floor(exact * slope + 1.0e-10);
        return Math.max(0, Math.min(MAX_LOD, lod));
    }

    /** Tile coordinate containing the block at the given LOD. */
    public static int blockToTile(final int block, final int lod) {
        return block >> (TILE_SHIFT + lod);
    }

    /** Pixel offset of a block within its LOD tile, in [0, 255]. */
    public static int blockToPixelInTile(final int block, final int lod) {
        return (block >> lod) & (TILE_SIZE - 1);
    }

    /**
     * Parent tile coordinate one LOD coarser than {@code childTile}. Arithmetic right shift keeps
     * negative tiles in the correct parent: tile -1 and tile -2 both belong to parent -1.
     */
    public static int parentTile(final int childTile) {
        return childTile >> 1;
    }

    /** Child tile coordinate one LOD finer, for the half of {@code parentTile} selected by {@code quadrant}. */
    public static int childTile(final int parentTile, final int quadrant) {
        return (parentTile << 1) + (quadrant & 1);
    }

    /**
     * Which half of its parent tile {@code childTile} occupies: 0 for the negative-coordinate half
     * (west / north), 1 for the positive-coordinate half (east / south).
     */
    public static int quadrantInParent(final int childTile) {
        return childTile & 1;
    }

    /**
     * UV rectangle of {@code childTile} inside its parent tile, written into {@code uvOut} as
     * {@code [u0, v0, u1, v1]}. Each axis covers exactly half the parent, so a missing finer tile
     * can be covered by stretching the matching quadrant of the coarser texture.
     */
    public static void parentUv(
        final int childTileX,
        final int childTileZ,
        final float[] uvOut
    ) {
        final float u0 = (childTileX & 1) * 0.5f;
        final float v0 = (childTileZ & 1) * 0.5f;
        uvOut[0] = u0;
        uvOut[1] = v0;
        uvOut[2] = u0 + 0.5f;
        uvOut[3] = v0 + 0.5f;
    }

    /** Whether two aligned LOD tiles overlap when expressed in LOD-0 tile coordinates. */
    public static boolean overlaps(
        final int firstLod,
        final int firstTileX,
        final int firstTileZ,
        final int secondLod,
        final int secondTileX,
        final int secondTileZ
    ) {
        final long firstScale = 1L << firstLod;
        final long secondScale = 1L << secondLod;
        final long firstX = (long) firstTileX * firstScale;
        final long firstZ = (long) firstTileZ * firstScale;
        final long secondX = (long) secondTileX * secondScale;
        final long secondZ = (long) secondTileZ * secondScale;
        return firstX < secondX + secondScale && secondX < firstX + firstScale
            && firstZ < secondZ + secondScale && secondZ < firstZ + firstScale;
    }
}
