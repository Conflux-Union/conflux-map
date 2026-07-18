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
     * Chooses the coarsest available LOD whose texel is at least one screen pixel wide. Using
     * floor here minifies the selected texture between power-of-two zoom steps, which aliases
     * prediction's deterministic canopy grid into large stripe patterns.
     */
    public static int lodForScale(final double blocksPerScreenPixel) {
        if (!Double.isFinite(blocksPerScreenPixel) || blocksPerScreenPixel <= 1.0) {
            return 0;
        }
        final double exact = Math.log(blocksPerScreenPixel) / Math.log(2.0);
        final int lod = (int) Math.ceil(exact - 1.0e-10);
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
}
