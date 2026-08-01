package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkViewportTest {
    @Test
    void exactRightAndBottomEdgesDoNotIncludeAnotherChunk() {
        final ChunkViewport viewport = ChunkViewport.covering(
            0.0, 0.0, 256, 256, 16.0
        );

        assertEquals(-128, viewport.minChunkX());
        assertEquals(127, viewport.maxChunkX());
        assertEquals(-128, viewport.minChunkZ());
        assertEquals(127, viewport.maxChunkZ());
        assertEquals(65_536L, viewport.chunkCount());
    }

    @Test
    void subChunkSliverIncludesOnlyTheIntersectedNeighbor() {
        final ChunkViewport viewport = ChunkViewport.covering(
            8.5, 8.5, 1, 1, 1.0
        );

        assertEquals(0, viewport.minChunkX());
        assertEquals(0, viewport.maxChunkX());
        assertEquals(0, viewport.minChunkZ());
        assertEquals(0, viewport.maxChunkZ());
        assertEquals(1L, viewport.chunkCount());
    }

    @Test
    void negativeCoordinatesUseFloorSemantics() {
        final ChunkViewport viewport = ChunkViewport.covering(
            -16.0, -16.0, 1, 1, 1.0
        );

        assertEquals(-2, viewport.minChunkX());
        assertEquals(-1, viewport.maxChunkX());
        assertEquals(-2, viewport.minChunkZ());
        assertEquals(-1, viewport.maxChunkZ());
    }

    @Test
    void regionSlicesCoverOnlyVisibleChunksAcrossNegativeBoundaries() {
        final ChunkViewport viewport = new ChunkViewport(-1, 16, -1, 16);

        final List<ChunkRegionSlice> slices = viewport.regionSlices();

        assertEquals(9, slices.size());
        assertEquals(new ChunkRegionSlice(-1, -1, 15, 15, 15, 15), slices.get(0));
        assertEquals(new ChunkRegionSlice(0, 0, 0, 0, 15, 15), slices.get(4));
        assertEquals(new ChunkRegionSlice(1, 1, 0, 0, 0, 0), slices.get(8));
        assertEquals(
            viewport.chunkCount(),
            slices.stream().mapToLong(ChunkRegionSlice::chunkCount).sum()
        );
    }
}
