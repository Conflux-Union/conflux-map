package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.task.DirtyChunkSet.Readiness;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Replays the client-side capture loop against {@link DirtyChunkSet} to measure how many
 * snapshots it actually takes to fill one view-distance square, versus how many chunks that
 * square contains. Nothing here asserts - it is a measurement harness for the capture budget.
 */
@Tag("benchmark")
class CapturePipelineSimulationTest {
    private static final int BUDGET = 8;
    private static final int MAX_TICKS = 20_000;

    @Test
    void measureCaptureAmplification() {
        System.out.println();
        System.out.printf(
            "%-26s %7s %8s %8s %6s %8s %9s%n",
            "scenario", "chunks", "samples", "amp", "ticks", "seconds", "degraded"
        );
        for (final int arrivals : new int[]{2, 4, 8, 16, 64}) {
            report("cold stream " + arrivals + "/tick", simulate(16, arrivals, false));
        }
        report("warm reseed (all loaded)", simulate(16, 0, true));
        report("cold stream 4/tick vd32", simulate(32, 4, false));
    }

    private static void report(final String name, final Result result) {
        System.out.printf(
            "%-26s %7d %8d %7.2fx %6d %8.1f %9d%n",
            name,
            result.uniqueChunks,
            result.totalSamples,
            (double) result.totalSamples / result.uniqueChunks,
            result.ticks,
            result.ticks / 20.0,
            result.degradedSamples
        );
    }

    /**
     * @param viewDistance    server send distance in chunks
     * @param arrivalsPerTick chunks the client receives per tick; 0 with {@code preloaded}
     *                        means every chunk is already there when the session starts
     * @param preloaded       whether the session change finds the square already loaded
     */
    private static Result simulate(
        final int viewDistance, final int arrivalsPerTick, final boolean preloaded
    ) {
        final int radius = viewDistance + 1;
        final List<long[]> arrivalOrder = nearestFirst(radius);
        final Set<Long> loaded = new HashSet<>();
        final DirtyChunkSet dirty = new DirtyChunkSet();
        final Map<Long, Integer> samplesPerChunk = new HashMap<>();

        int cursor = 0;
        if (preloaded) {
            for (final long[] chunk : arrivalOrder) {
                loaded.add(key(chunk[0], chunk[1]));
            }
            cursor = arrivalOrder.size();
        }
        // ChunkCaptureService.onSessionChanged -> reseedViewport
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                dirty.mark(dx, dz);
            }
        }

        int totalSamples = 0;
        int degradedSamples = 0;
        int ticks = 0;
        while (ticks < MAX_TICKS) {
            ticks++;
            for (int i = 0; i < arrivalsPerTick && cursor < arrivalOrder.size(); i++) {
                final long[] chunk = arrivalOrder.get(cursor++);
                loaded.add(key(chunk[0], chunk[1]));
                dirty.markWithLoadedNeighbors(
                    (int) chunk[0], (int) chunk[1], (x, z) -> loaded.contains(key(x, z))
                );
            }
            final List<long[]> batch = dirty.drainNearest(
                BUDGET, 0, 0, (x, z) -> readiness(loaded, radius, x, z)
            );
            for (final long[] chunk : batch) {
                totalSamples++;
                samplesPerChunk.merge(key(chunk[0], chunk[1]), 1, Integer::sum);
                if (readiness(loaded, radius, (int) chunk[0], (int) chunk[1]) != Readiness.READY) {
                    degradedSamples++;
                }
            }
            if (cursor >= arrivalOrder.size() && dirty.size() == 0) {
                break;
            }
        }
        return new Result(samplesPerChunk.size(), totalSamples, degradedSamples, ticks);
    }

    private static Readiness readiness(
        final Set<Long> loaded, final int radius, final int chunkX, final int chunkZ
    ) {
        if (!loaded.contains(key(chunkX, chunkZ))) {
            return Readiness.MISSING;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                final int neighborX = chunkX + dx;
                final int neighborZ = chunkZ + dz;
                if ((dx != 0 || dz != 0)
                    && Math.abs(neighborX) <= radius
                    && Math.abs(neighborZ) <= radius
                    && !loaded.contains(key(neighborX, neighborZ))) {
                    return Readiness.AWAITING_NEIGHBORS;
                }
            }
        }
        return Readiness.READY;
    }

    /** The order a vanilla server sends chunks in: nearest to the player first. */
    private static List<long[]> nearestFirst(final int radius) {
        final List<long[]> chunks = new ArrayList<>();
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                chunks.add(new long[]{dx, dz});
            }
        }
        chunks.sort((a, b) -> Long.compare(
            a[0] * a[0] + a[1] * a[1], b[0] * b[0] + b[1] * b[1]
        ));
        return chunks;
    }

    private static long key(final long chunkX, final long chunkZ) {
        return (chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    private record Result(int uniqueChunks, int totalSamples, int degradedSamples, int ticks) {
    }
}
