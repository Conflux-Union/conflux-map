package cn.net.rms.confluxmap.core.selection;

import cn.net.rms.confluxmap.core.export.MapExportBounds;

/**
 * Chunk-snapped rectangular selection math for the fullscreen map, independent
 * of Minecraft mouse event versions. Corners are block coordinates; the result
 * always covers whole 16x16 chunks, so {@link MapExportBounds#blockWidth()} and
 * {@link MapExportBounds#blockHeight()} are always multiples of 16.
 */
public final class ChunkSelectionMath {
    private static final int CHUNK_BLOCKS = 16;

    private ChunkSelectionMath() {
    }

    /**
     * Snaps the given block-coordinate corners outward to the chunks they touch,
     * normalizing so min is the lower corner's chunk origin and max is the upper
     * corner's chunk end (inclusive).
     */
    public static MapExportBounds betweenBlocks(
        final int firstX,
        final int firstZ,
        final int secondX,
        final int secondZ
    ) {
        return new MapExportBounds(
            Math.floorDiv(Math.min(firstX, secondX), CHUNK_BLOCKS) * CHUNK_BLOCKS,
            Math.floorDiv(Math.min(firstZ, secondZ), CHUNK_BLOCKS) * CHUNK_BLOCKS,
            Math.floorDiv(Math.max(firstX, secondX), CHUNK_BLOCKS) * CHUNK_BLOCKS + CHUNK_BLOCKS - 1,
            Math.floorDiv(Math.max(firstZ, secondZ), CHUNK_BLOCKS) * CHUNK_BLOCKS + CHUNK_BLOCKS - 1
        );
    }

    /** Number of whole chunks covered horizontally; blockWidth() is always this times 16. */
    public static int chunksAcrossX(final MapExportBounds bounds) {
        return Math.floorDiv(bounds.maxX(), CHUNK_BLOCKS) - Math.floorDiv(bounds.minX(), CHUNK_BLOCKS) + 1;
    }

    /** Number of whole chunks covered vertically; blockHeight() is always this times 16. */
    public static int chunksAcrossZ(final MapExportBounds bounds) {
        return Math.floorDiv(bounds.maxZ(), CHUNK_BLOCKS) - Math.floorDiv(bounds.minZ(), CHUNK_BLOCKS) + 1;
    }
}
