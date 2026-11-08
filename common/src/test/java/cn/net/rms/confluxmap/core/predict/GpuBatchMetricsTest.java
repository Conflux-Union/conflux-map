package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The engine's batch metrics readback ({@link GpuBackend#metrics}) against a live batch
 * dispatch: the counters and histograms must move with the dispatched wave shape, the
 * event timers must agree with the queue's profiling state, and the capability latch must
 * survive repeated probes. Skips like {@link BatchDispatchParityTest} where the native
 * library cannot load or the engine does not serve stages, and where the library predates
 * the metrics export. The engine is process-global, so every assertion runs on a
 * before/after delta around exactly one dispatch.
 */
class GpuBatchMetricsTest {
    /** Same fixed seed {@code BatchDispatchParityTest} uses. */
    private static final long SEED = 146008555L;

    // Slot layout mirror of the GPU_METRICS_* defines in native/cubiomes/gpu.h.
    private static final int SLOT_PROFILING = 0;
    private static final int SLOT_BATCH_MEMBERS = 1;
    private static final int SLOT_BATCH_POINTS = 2;
    private static final int SLOT_KERNEL_LAUNCHES = 3;
    private static final int SLOT_H2D_WRITES = 4;
    private static final int SLOT_D2H_READS = 5;
    private static final int SLOT_HOST_SUBMIT_NANOS = 7;
    private static final int SLOT_HOST_WAIT_NANOS = 8;
    private static final int SLOT_H2D_NANOS = 9;
    private static final int SLOT_KERNEL_NANOS = 10;
    private static final int SLOT_D2H_NANOS = 11;
    private static final int SLOT_QUEUE_DELAY_NANOS = 12;
    private static final int SLOT_MEMBER_HIST = 14;
    private static final int SLOT_POINT_HIST = 47;
    private static final int SLOT_COUNT = 68;

    /**
     * One wave the batch entries serve: two 130x130 stride-4 blocks, the shape
     * {@code WaveComposer}'s leader sends at lod 2. 130x130 keeps both members under the
     * engine's per-dispatch point cap, so the shim submits them as one batch.
     */
    private static final int WAVE_MEMBERS = 2;
    private static final int WAVE_SIDE = 130;
    private static final int WAVE_STRIDE = 4;

    private NativeBaselineSampler sampler;

    @BeforeEach
    void requireNativeServingGpu() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        sampler = new NativeBaselineSampler(
            McVersions.toCubiomes("1.21.4").orElseThrow(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        // The batch entries are refused wholesale without a live engine, so a CPU-fallback
        // machine (no fp64 OpenCL device, mode off) never moves the metrics - skip the same
        // way a missing library skips.
        final long before = sampler.gpuStageStats();
        assertTrue(sampler.overviewHeightsCoarse(0, 0, 130, 130, 4, new int[130 * 130]),
            "probe tile must sample");
        Assumptions.assumeTrue((sampler.gpuStageStats() - before) >>> 32 > 0,
            "engine is not serving stages on a GPU; batch metrics are unobservable");
    }

    @Test
    void batchCountersMoveWithDispatchShape() {
        final long[] before = GpuBackend.metrics();
        Assumptions.assumeTrue(before != null, "metrics export unavailable on this library");
        dispatchCoarseWave();

        final long[] after = GpuBackend.metrics();
        // The height batch passes its inputs as kernel args and reads one plane back per
        // member: n launches, n readbacks, and no input-buffer write at all (the packed
        // terrainY upload is the biome batch's).
        assertEquals(WAVE_MEMBERS, delta(after, before, SLOT_BATCH_MEMBERS), "batch members");
        assertEquals(WAVE_MEMBERS * WAVE_SIDE * WAVE_SIDE, delta(after, before, SLOT_BATCH_POINTS),
            "batch points");
        assertEquals(WAVE_MEMBERS, delta(after, before, SLOT_KERNEL_LAUNCHES), "kernel launches");
        assertEquals(WAVE_MEMBERS, delta(after, before, SLOT_D2H_READS), "d2h reads");
        assertEquals(0L, delta(after, before, SLOT_H2D_WRITES), "h2d writes");

        assertEquals(1L, delta(after, before, SLOT_MEMBER_HIST + WAVE_MEMBERS),
            "member histogram must count the n-member batch entry once");
        assertEquals(1L, delta(after, before, SLOT_POINT_HIST + pointHistSlot(WAVE_MEMBERS * WAVE_SIDE * WAVE_SIDE)),
            "point histogram must count the batch's total points once");
        assertTrue(delta(after, before, SLOT_HOST_SUBMIT_NANOS) > 0L, "submit wall");
        assertTrue(delta(after, before, SLOT_HOST_WAIT_NANOS) > 0L, "readback wait wall");
    }

    @Test
    void metricsTimersMatchProfilingFlag() {
        final long[] before = GpuBackend.metrics();
        Assumptions.assumeTrue(before != null, "metrics export unavailable on this library");
        dispatchCoarseWave();

        final long[] after = GpuBackend.metrics();
        // Slot 0 reports the live queue state, the four event timers accumulate only under
        // it: profiling on with a timestamp-dropping driver stack degrades the timers back
        // to zero instead of reporting garbage, so only the biconditional is invariant.
        final boolean profiling = after[SLOT_PROFILING] == 1L;
        final long timerNanos = delta(after, before, SLOT_H2D_NANOS) + delta(after, before, SLOT_KERNEL_NANOS)
            + delta(after, before, SLOT_D2H_NANOS) + delta(after, before, SLOT_QUEUE_DELAY_NANOS);
        assertEquals(profiling, timerNanos > 0L,
            "event timers must be positive exactly when the queue really profiles events");
    }

    @Test
    void metricsCapabilityLatchSurvives() {
        final long[] first = GpuBackend.metrics();
        final long[] second = GpuBackend.metrics();
        if (first == null) {
            // An older library latches the export off; the latch must hold, not flip back.
            assertNull(second, "a latched-off metrics export must stay off");
            return;
        }
        assertEquals(SLOT_COUNT, first.length, "snapshot slot count");
        assertEquals(SLOT_COUNT, second.length, "snapshot slot count");
        assertTrue(GpuBackend.metricsAvailable(), "metricsAvailable after a successful read");
    }

    /** One coarse-heights batch wave through the production dispatch seam. */
    private void dispatchCoarseWave() {
        final int[] blockX = {1000, 3000};
        final int[] blockZ = {1000, 2000};
        final int[] w = {WAVE_SIDE, WAVE_SIDE};
        final int[] h = {WAVE_SIDE, WAVE_SIDE};
        final int[] stride = {WAVE_STRIDE, WAVE_STRIDE};
        final int[] out = new int[WAVE_MEMBERS * WAVE_SIDE * WAVE_SIDE];
        final int[] failMask = new int[1];
        assertEquals(0, sampler.overviewHeightsCoarseBatch(
            blockX, blockZ, w, h, stride, WAVE_MEMBERS, out, failMask
        ), "batch dispatch must be attempted");
        assertEquals(0, failMask[0], "every wave member must be batch-eligible");
    }

    private static long delta(final long[] after, final long[] before, final int slot) {
        return after[slot] - before[slot];
    }

    /**
     * The engine's point-histogram bucket: the smallest {@code k} with {@code 2^k >=
     * points}, capped at 20 (slot 0 is a single point, slot 20 is the dispatch cap) - see
     * {@code gpuPointHistSlot} in {@code native/cubiomes/gpu.c}.
     */
    private static int pointHistSlot(final long points) {
        int slot = 0;
        while (slot < 20 && (1L << slot) < points) {
            slot++;
        }
        return slot;
    }
}
