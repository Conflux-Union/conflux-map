package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.task.CaptureRefreshSweep;
import cn.net.rms.confluxmap.core.task.DirtyChunkSet.Readiness;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ChunkCaptureViewDistanceTest {
    @Test
    void tintNeighborsAreOnlyRequiredWhenBiomeBlendIsEnabled() {
        assertFalse(ChunkCaptureService.needsTintNeighbors(0));
        assertTrue(ChunkCaptureService.needsTintNeighbors(1));
    }

    @Test
    void advertisedServerDistanceOverridesSmallerClientSetting() {
        assertEquals(12, ChunkCaptureService.captureViewDistance(4, 12));
    }

    @Test
    void clientSettingIsUsedOnlyWhenTheServerDidNotAdvertiseItsDistance() {
        assertEquals(4, ChunkCaptureService.captureViewDistance(4, -1));
    }

    @Test
    void maximumMinimapRangeIsClippedToTheCaptureableArea() {
        assertEquals(
            ChunkViewport.centered(0, 0, 13),
            ChunkCaptureService.visibleCaptureViewport(
                ChunkViewport.centered(0, 0, 32),
                ChunkViewport.centered(0, 0, 12)
            )
        );
    }

    @Test
    void remoteMinimapViewportWithNoLoadedOverlapSchedulesNothing() {
        assertEquals(
            null,
            ChunkCaptureService.visibleCaptureViewport(
                ChunkViewport.centered(100, 100, 2),
                ChunkViewport.centered(0, 0, 12)
            )
        );
    }

    @Test
    void missingNeighborInsideSendDistanceWaitsWithoutFallback() {
        final Set<String> loaded = Set.of("0,0");

        assertEquals(
            Readiness.AWAITING_NEIGHBORS,
            ChunkCaptureService.captureReadiness(
                0, 0, ChunkViewport.centered(0, 0, 1),
                (x, z) -> loaded.contains(x + "," + z), true
            )
        );
    }

    @Test
    void missingNeighborOutsideSendDistanceUsesTheClampedEdgeSample() {
        final Set<String> loaded = Set.of("0,0");

        assertEquals(
            Readiness.READY,
            ChunkCaptureService.captureReadiness(
                0, 0, ChunkViewport.centered(0, 0, 0),
                (x, z) -> loaded.contains(x + "," + z), true
            )
        );
    }

    @Test
    void maximumRangeSweepCannotPermanentlyHoldLoadedTerrain() {
        final CaptureRefreshSweep sweep = new CaptureRefreshSweep();
        final Set<String> loaded = Set.of("0,0");
        final ChunkViewport expected = ChunkViewport.centered(0, 0, 1);
        sweep.updateTarget(
            MapLayer.SURFACE, 0, ChunkViewport.centered(0, 0, 2)
        );

        boolean centerCaptured = false;
        for (int tick = 0; tick < 100 && !centerCaptured; tick++) {
            final CaptureRefreshSweep.Batch batch = sweep.drainNearest(
                8,
                0,
                0,
                (x, z) -> ChunkCaptureService.captureReadiness(
                    x, z, expected, (nx, nz) -> loaded.contains(nx + "," + nz), true
                )
            );
            centerCaptured = batch.chunks().stream()
                .anyMatch(pos -> pos[0] == 0L && pos[1] == 0L);
        }

        assertTrue(centerCaptured, "a loaded visible chunk remained blocked forever");
    }
}
