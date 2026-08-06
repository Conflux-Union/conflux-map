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
    void centeredViewportUsesTheAdvertisedServerRadius() {
        assertEquals(new ChunkViewport(-7, 17, -15, 9), ChunkViewport.centered(5, -3, 12));
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

    @Test
    void regionSlicesCanExcludePlayerViewDistanceWithoutDroppingOuterChunks() {
        final ChunkViewport viewport = new ChunkViewport(14, 18, 2, 6);
        final ChunkViewport playerView = new ChunkViewport(15, 17, 3, 5);

        final List<ChunkRegionSlice> slices = viewport.regionSlicesExcluding(playerView);

        assertEquals(16L, slices.stream().mapToLong(ChunkRegionSlice::chunkCount).sum());
        for (final ChunkRegionSlice slice : slices) {
            for (int chunkZ = slice.minChunkZ(); chunkZ <= slice.maxChunkZ(); chunkZ++) {
                for (int chunkX = slice.minChunkX(); chunkX <= slice.maxChunkX(); chunkX++) {
                    assertEquals(
                        false,
                        chunkX >= playerView.minChunkX() && chunkX <= playerView.maxChunkX()
                            && chunkZ >= playerView.minChunkZ() && chunkZ <= playerView.maxChunkZ()
                    );
                }
            }
        }
    }
}
