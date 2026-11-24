package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.*;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.BitSet;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ChunkLoadCoverageTest {
    @Test
    void mergesAdjacentEdgesAndPointsEachStrokeInward() {
        final var coverage = new ChunkLoadCoverage(new ChunkLoadCoverage.Window(-10, -14, 5, 4, 1));
        coverage.addChunkRun(-8, -7, -12);
        assertEquals(2, coverage.loaded().cardinality());
        assertEquals(List.of(
            new ChunkLoadCoverage.Edge(true, 2, 2, 4, 1),
            new ChunkLoadCoverage.Edge(true, 3, 2, 4, -1),
            new ChunkLoadCoverage.Edge(false, 2, 2, 3, 1),
            new ChunkLoadCoverage.Edge(false, 4, 2, 3, -1)
        ), coverage.outline());
        assertFalse(coverage.containsChunk(-8, -11));
    }

    @Test
    void boundsFarZoomWorkAndRecognizesSynchronizedCoverageOnlyWhereMarked() {
        final var window = ChunkLoadCoverage.Window.covering(-32, -16, 640, 360, 80);
        assertTrue(window.width() <= 644);
        assertTrue(window.height() <= 364);
        assertEquals(8, window.stride());
        final var coverage = new ChunkLoadCoverage(new ChunkLoadCoverage.Window(-17, -17, 18, 18, 1));
        final var key = new TileKey(new WorldIdentity("server", "world"), DimensionId.OVERWORLD, "surface", 0, -1, -1);
        final var received = new BitSet();
        received.set(16, 48);
        MapLoadedRangeOverlay.addSynchronized(coverage, key, received);
        assertTrue(coverage.containsChunk(-15, -16));
        assertTrue(coverage.containsChunk(-14, -16));
        assertFalse(coverage.containsChunk(-16, -16));
        assertFalse(coverage.containsChunk(-15, -15));
    }
}
