package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.task.DirtyChunkSet.Readiness;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Measures visible-sweep queue overhead when the minimap covers more than the server can send. */
@Tag("benchmark")
class CaptureRefreshSweepThroughputBenchmarkTest {
    private static final int ROUNDS = 5;

    @Test
    void measureOversizedVisibleViewport() {
        final ChunkViewport loaded = ChunkViewport.centered(0, 0, 13);
        final long oversized = best(() -> drain(ChunkViewport.centered(0, 0, 32), loaded));
        final long clipped = best(() -> drain(loaded, loaded));

        System.out.println();
        System.out.printf("%-34s %9s%n", "visible sweep", "ms");
        System.out.printf("%-34s %9.2f%n", "65x65 visible, 27x27 loaded", oversized / 1e6);
        System.out.printf("%-34s %9.2f%n", "27x27 visible and loaded", clipped / 1e6);
        System.out.printf("oversized/clipped ratio %.2fx%n", (double) oversized / clipped);
    }

    private static long best(final Runnable round) {
        long best = Long.MAX_VALUE;
        for (int i = 0; i < ROUNDS; i++) {
            final long start = System.nanoTime();
            round.run();
            best = Math.min(best, System.nanoTime() - start);
        }
        return best;
    }

    private static void drain(
        final ChunkViewport visible, final ChunkViewport loaded
    ) {
        final CaptureRefreshSweep sweep = new CaptureRefreshSweep();
        sweep.updateTarget(MapLayer.SURFACE, 0, visible);
        int ticks = 0;
        while (sweep.hasPending()) {
            if (++ticks > 1_000) {
                throw new AssertionError("visible sweep did not drain");
            }
            sweep.drainNearest(
                8,
                0,
                0,
                (x, z) -> loaded.contains(x, z) ? Readiness.READY : Readiness.MISSING
            );
        }
    }
}
