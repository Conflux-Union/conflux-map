package cn.net.rms.confluxmap.core.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.export.MapExportBounds;
import org.junit.jupiter.api.Test;

class ChunkSelectionMathTest {

    @Test
    void dragInsideOneChunkSelectsThatWholeChunk() {
        final MapExportBounds bounds = ChunkSelectionMath.betweenBlocks(32, 48, 35, 51);

        assertEquals(32, bounds.minX());
        assertEquals(48, bounds.minZ());
        assertEquals(47, bounds.maxX());
        assertEquals(63, bounds.maxZ());
        assertEquals(1, ChunkSelectionMath.chunksAcrossX(bounds));
        assertEquals(1, ChunkSelectionMath.chunksAcrossZ(bounds));
        assertEquals(16, bounds.blockWidth());
        assertEquals(16, bounds.blockHeight());
    }

    @Test
    void dragAcrossAChunkBoundaryCoversBothChunks() {
        final MapExportBounds bounds = ChunkSelectionMath.betweenBlocks(15, 0, 16, 0);

        assertEquals(0, bounds.minX());
        assertEquals(31, bounds.maxX());
        assertEquals(2, ChunkSelectionMath.chunksAcrossX(bounds));
        assertEquals(32, bounds.blockWidth());
    }

    @Test
    void negativeCoordinatesFloorTowardNegativeInfinity() {
        final MapExportBounds bounds = ChunkSelectionMath.betweenBlocks(-1, -17, 0, -1);

        assertEquals(-16, bounds.minX());
        assertEquals(15, bounds.maxX());
        assertEquals(-32, bounds.minZ());
        assertEquals(-1, bounds.maxZ());
        assertEquals(2, ChunkSelectionMath.chunksAcrossX(bounds));
        assertEquals(2, ChunkSelectionMath.chunksAcrossZ(bounds));
    }

    @Test
    void reversedCornersNormalizeBeforeSnapping() {
        final MapExportBounds bounds = ChunkSelectionMath.betweenBlocks(40, 63, 33, 50);

        assertEquals(32, bounds.minX());
        assertEquals(47, bounds.maxX());
        assertEquals(48, bounds.minZ());
        assertEquals(63, bounds.maxZ());
    }

    @Test
    void blockSizesAreAlwaysChunkCountsTimesSixteen() {
        final MapExportBounds bounds = ChunkSelectionMath.betweenBlocks(-1, 100, 33, 350);

        assertEquals(ChunkSelectionMath.chunksAcrossX(bounds) * 16L, bounds.blockWidth());
        assertEquals(ChunkSelectionMath.chunksAcrossZ(bounds) * 16L, bounds.blockHeight());
    }

    @Test
    void alignedChunkOriginStaysPut() {
        final MapExportBounds bounds = ChunkSelectionMath.betweenBlocks(16, 16, 31, 31);

        assertEquals(16, bounds.minX());
        assertEquals(16, bounds.minZ());
        assertEquals(31, bounds.maxX());
        assertEquals(31, bounds.maxZ());
        assertEquals(1, ChunkSelectionMath.chunksAcrossX(bounds));
        assertEquals(1, ChunkSelectionMath.chunksAcrossZ(bounds));
    }
}
