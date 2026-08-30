package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.task.DirtyChunkSet.Readiness;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class CaptureRefreshSweepTest {
    @Test
    void changingPivotCompletesTheVisibleSweepBeforeStartingTheLatestOne() {
        final CaptureRefreshSweep sweep = new CaptureRefreshSweep();
        final ChunkViewport visible = ChunkViewport.centered(0, 0, 4);
        final Map<Integer, Set<String>> capturedByPivot = new HashMap<>();

        for (int tick = 0; tick < 20; tick++) {
            sweep.updateTarget(MapLayer.CAVE_AUTO, tick, visible);
            final CaptureRefreshSweep.Batch batch = sweep.drainNearest(
                8, 0, 0, (x, z) -> Readiness.READY
            );
            batch.chunks().forEach(pos -> capturedByPivot
                .computeIfAbsent(batch.pivotY(), ignored -> new HashSet<>())
                .add(key(pos)));
        }

        assertEquals(visible.chunkCount(), capturedByPivot.get(0).size());
        assertTrue(capturedByPivot.containsKey(11), "intermediate pivots should coalesce");
        assertEquals(Set.of(0, 11), capturedByPivot.keySet());
    }

    @Test
    void movingViewportAddsOnlyNewChunksToTheCurrentSweep() {
        final CaptureRefreshSweep sweep = new CaptureRefreshSweep();
        final ChunkViewport first = new ChunkViewport(0, 2, 0, 0);
        sweep.updateTarget(MapLayer.CAVE_AUTO, 32, first);
        sweep.drainNearest(2, 0, 0, (x, z) -> Readiness.READY);

        sweep.updateTarget(
            MapLayer.CAVE_AUTO, 32, new ChunkViewport(1, 3, 0, 0)
        );
        final CaptureRefreshSweep.Batch remainder = sweep.drainNearest(
            8, 1, 0, (x, z) -> Readiness.READY
        );

        assertEquals(
            Set.of("2,0", "3,0"),
            remainder.chunks().stream().map(CaptureRefreshSweepTest::key).collect(
                java.util.stream.Collectors.toSet()
            )
        );
    }

    private static String key(final long[] pos) {
        return pos[0] + "," + pos[1];
    }
}
