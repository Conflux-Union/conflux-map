package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.GpuBackend.GpuStats;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.nativepredict.PredictionBackend;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Drift-bounded baseline parity and dispatch engagement of the sparse fp32 kernels. */
class NativeSparseGpuEngagementTest {
    /** Same fixed seed {@code PredictionNativeIntegrationTest} uses. */
    private static final long SEED = 146008555L;
    /** Probe-picked waterline scene ({@code WaveBenchmarkOptions}' preset, as in the Phase 2 suite). */
    private static final int COAST_X = -32;
    private static final int COAST_Z = -16;
    private static final int LOD2 = 2;
    private static final int LOD4 = 4;

    /**
     * Above-gate sparse band chunk: 300 points, past the 256-point heights gate but far under
     * the engine's 2^20 per-dispatch cap, so the fork must serve it in one kernel and one read.
     */
    private static final int BAND_CHUNK_POINTS = 300;
    /**
     * Above-gate sparse cell chunk: 1024 cells sits exactly on the biome gate; edge 2 x stride 4
     * spreads each cell's 4 points 4 blocks apart (4096 points total, again far under the cap).
     */
    private static final int CELL_CHUNK_CELLS = 1024;
    private static final int CELL_EDGE = 2;
    private static final int CELL_STRIDE = 4;

    private NativeBaselineSampler sampler;

    @BeforeEach
    void requireNativeServingGpu() {
        Assumptions.assumeTrue(
            NativeLib.initForTests(), "native prediction library unavailable on this platform"
        );
        sampler = new NativeBaselineSampler(
            McVersions.toCubiomes("1.21.4").orElseThrow(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        // Earlier tests in this JVM may have left the engine off; the engagement evidence below
        // is only meaningful from the auto mode, and every test restores it on exit.
        setBackend(PredictionBackend.GPU);
        // The sparse kernels are refused wholesale without a live engine, so a CPU-fallback
        // machine has nothing to engage - skip the same way a missing library skips.
        final long before = sampler.gpuStageStats();
        assertTrue(
            sampler.overviewHeightsCoarse(0, 0, 130, 130, 4, new int[130 * 130]),
            "probe tile must sample"
        );
        Assumptions.assumeTrue(
            gpuServed(before, sampler.gpuStageStats()) > 0,
            "engine is not serving stages on a GPU; sparse GPU engagement is unobservable"
        );
    }

    @AfterEach
    void restoreEngineMode() {
        setBackend(PredictionBackend.GPU);
    }

    /**
     * Each above-gate probe brackets exactly one sparse dispatch between two stats reads, so the
     * delta attributes engagement to the sparse seam itself - no rect entry or composition noise
     * is inside the bracket.
     */
    @Test
    void aboveGateSparseChunksAreGpuServedInAutoAndRefusedWhenOff() {
        assertBandChunkEngagement(PredictionBackend.GPU, 1, 1);
        assertBandChunkEngagement(PredictionBackend.CPU, 1, 0);
        assertCellChunkEngagement(PredictionBackend.GPU, 1, 1);
        assertCellChunkEngagement(PredictionBackend.CPU, 1, 0);
    }

    /** Below-gate chunks take the CPU loops and must not register as tracked GPU stages at all. */
    @Test
    void belowGateSparseChunksStayCpuAndUntracked() {
        final int[] xs = {COAST_X, COAST_X + 16, COAST_X + 32, COAST_X + 48};
        final int[] zs = {COAST_Z, COAST_Z + 16, COAST_Z + 32, COAST_Z + 48};
        final long before = sampler.gpuStageStats();
        assertTrue(sampler.overviewHeightsSparse(xs, zs, xs.length, new int[xs.length]),
            "below-gate band chunk must resolve");
        final long after = sampler.gpuStageStats();
        assertEquals(0, gpuAttempted(before, after), "a below-gate chunk must not count as a GPU stage");
        assertEquals(0, gpuServed(before, after), "a below-gate chunk must stay on the CPU loops");

        final int cells = 2;
        final int points = CELL_EDGE * CELL_EDGE;
        final int[] cellX = {COAST_X, COAST_X + 8};
        final int[] cellZ = {COAST_Z, COAST_Z + 8};
        final long biomesBefore = sampler.gpuStageStats();
        assertTrue(sampler.surfaceBiomesSparse(
            cellX, cellZ, CELL_EDGE, CELL_STRIDE, chunkHeights(cells * points), cells,
            new int[cells * points]
        ), "below-gate cell chunk must resolve");
        assertEquals(0, gpuAttempted(biomesBefore, sampler.gpuStageStats()),
            "a below-gate cell chunk must not count as a GPU stage");
    }

    /**
     * Composition drift across the engine switch: the CPU run is the reference and the fp32
     * GPU run approximates it, so the planes are gated by the shared sparse drift bounds
     * (2% categorical, |dY| &lt;= 8) instead of bit equality. The engagement evidence that the
     * auto run actually serves the sparse seam lives in the bracketed tests above; this gate
     * only bounds the composed drift.
     */
    @Test
    void compositionMatchesAcrossTheEngineSwitch() {
        final BaselineGrid[] off = compose(PredictionBackend.CPU);
        final BaselineGrid[] auto = compose(PredictionBackend.GPU);
        for (final int lod : new int[] {LOD2, LOD4}) {
            SparseDriftGates.assertGridsDriftBounded(off[lod], auto[lod], "lod" + lod, false, false);
        }
    }

    /**
     * Windows and the full tile must agree within fp32 transport parity: a pixel inside a
     * 16-wide window resolves through below-gate per-point dispatches, the full tile packs its
     * chunks, and since the fp32 unification the kernel entries round independently - so the
     * former per-pixel equality becomes tight bounds (max |dY| &lt;= 2, categorical under 2%),
     * while a dispatch-shape bug still fails at index scale.
     */
    @Test
    void gpuWindowsMatchFullTileAcrossSparseDispatchThresholds() {
        final BaselineGrid full = LodSampling.sample(sampler, false, LOD4, 0, 0);
        assertNotNull(full);
        final String[] planes = {"terrain", "biomes", "fluid", "surface", "flags"};
        final int[][] fullPlanes = {
            full.terrainY, full.biomeId, full.fluidY, full.baseSurfaceY, full.surfaceFlags};
        for (int minZ = 0; minZ < 256; minZ += 16) {
            for (int minX = 0; minX < 256; minX += 16) {
                final BaselineGrid window = LodSampling.sampleOverworldWindow(
                    sampler, LOD4, 0, 0, minX, minZ, minX + 15, minZ + 15
                );
                assertNotNull(window);
                final int[][] windowPlanes = {
                    window.terrainY, window.biomeId, window.fluidY,
                    window.baseSurfaceY, window.surfaceFlags};
                final String where = "window " + minX + "," + minZ;
                for (int plane = 0; plane < planes.length; plane++) {
                    // terrain/fluid/baseSurface carry numeric heights; biome/flags are
                    // categorical ids, so a numeric |dY| bound is meaningless on them
                    final boolean heightPlane = plane == 0 || plane == 2 || plane == 3;
                    int maxAbsDy = 0;
                    long mismatches = 0;
                    for (int pixelZ = minZ; pixelZ < minZ + 16; pixelZ++) {
                        for (int pixelX = minX; pixelX < minX + 16; pixelX++) {
                            final int index = BaselineGrid.index(pixelX, pixelZ);
                            if (fullPlanes[plane][index] != windowPlanes[plane][index]) {
                                mismatches++;
                                maxAbsDy = Math.max(
                                    maxAbsDy,
                                    Math.abs(fullPlanes[plane][index] - windowPlanes[plane][index])
                                );
                            }
                        }
                    }
                    final double rate = 100.0 * mismatches / 256;
                    System.out.printf(
                        "%s %s transport parity: mismatch %d/256 (%.4f%%), max numeric |dY| %d%n",
                        planes[plane], where, mismatches, rate, maxAbsDy
                    );
                    if (heightPlane) {
                        assertTrue(
                            maxAbsDy <= 2,
                            planes[plane] + " " + where + ": max |dY| " + maxAbsDy
                                + " > 2 transport-parity bound"
                        );
                    }
                    assertTrue(
                        rate < 10.0,
                        planes[plane] + " " + where + ": mismatch rate " + rate
                            + "% >= 10% transport-parity bound"
                    );
                }
            }
        }
    }

    /**
     * Composition-wide engagement evidence: the auto run's served count must move over a full
     * sparse-carrying composition, and the report line records the observed split.
     */
    @Test
    void autoCompositionServesGpuStages() {
        final long before = sampler.gpuStageStats();
        assertNotNull(compose(PredictionBackend.GPU)[LOD2], "auto composition must succeed");
        final long after = sampler.gpuStageStats();
        final long attempted = gpuAttempted(before, after);
        final long served = gpuServed(before, after);
        assertTrue(attempted > 0, "the composition must contain GPU-attempted stage shapes");
        assertTrue(served > 0, "the auto run must serve stages from the GPU");
        System.out.printf(
            "auto composition stage stats: attempted=%d served=%d (delta over one lod2 tile)%n",
            attempted, served
        );
    }

    private void assertBandChunkEngagement(
        final PredictionBackend backend, final long expectedAttempted, final long expectedServed
    ) {
        setBackend(backend);
        final int n = BAND_CHUNK_POINTS;
        final int[] xs = new int[n];
        final int[] zs = new int[n];
        for (int i = 0; i < n; i++) {
            xs[i] = COAST_X - 1024 + i % 32 * 4;
            zs[i] = COAST_Z - 1024 + i / 32 * 4;
        }
        final long before = sampler.gpuStageStats();
        assertTrue(sampler.overviewHeightsSparse(xs, zs, n, new int[n]),
            "above-gate band chunk must resolve on " + GpuBackend.engineModeLabel(currentEngineMode()));
        final long after = sampler.gpuStageStats();
        assertEquals(expectedAttempted, gpuAttempted(before, after),
            "the above-gate band chunk must be tracked as a GPU-attempted stage on " + backend);
        assertEquals(expectedServed, gpuServed(before, after),
            "the above-gate band chunk must " + (expectedServed > 0 ? "be GPU-served" : "fall to the CPU loops")
                + " on " + backend);
    }

    private void assertCellChunkEngagement(
        final PredictionBackend backend, final long expectedAttempted, final long expectedServed
    ) {
        setBackend(backend);
        final int cells = CELL_CHUNK_CELLS;
        final int points = CELL_EDGE * CELL_EDGE;
        final int[] cellX = new int[cells];
        final int[] cellZ = new int[cells];
        for (int i = 0; i < cells; i++) {
            cellX[i] = COAST_X - 256 + i % 32 * 8;
            cellZ[i] = COAST_Z - 256 + i / 32 * 8;
        }
        final long before = sampler.gpuStageStats();
        assertTrue(sampler.surfaceBiomesSparse(
            cellX, cellZ, CELL_EDGE, CELL_STRIDE, chunkHeights(cells * points), cells,
            new int[cells * points]
        ), "above-gate cell chunk must resolve on " + GpuBackend.engineModeLabel(currentEngineMode()));
        final long after = sampler.gpuStageStats();
        assertEquals(expectedAttempted, gpuAttempted(before, after),
            "the above-gate cell chunk must be tracked as a GPU-attempted stage on " + backend);
        assertEquals(expectedServed, gpuServed(before, after),
            "the above-gate cell chunk must " + (expectedServed > 0 ? "be GPU-served" : "fall to the CPU loops")
                + " on " + backend);
    }

    /** Composes the coast tile at both sparse-carrying LODs under the given backend. */
    private BaselineGrid[] compose(final PredictionBackend backend) {
        setBackend(backend);
        final BaselineGrid[] grids = new BaselineGrid[LOD4 + 1];
        for (final int lod : new int[] {LOD2, LOD4}) {
            grids[lod] = LodSampling.sample(sampler, false, lod, COAST_X, COAST_Z);
            assertNotNull(grids[lod], "composition must succeed at lod" + lod + " on " + backend);
        }
        return grids;
    }

    /**
     * Production backend switch - the same seam the settings UI drives; the flip is asserted so a
     * loader that swallows the mode change fails here instead of faking the engagement evidence.
     */
    private static void setBackend(final PredictionBackend backend) {
        GpuBackend.configure(backend);
        assertEquals(
            GpuBackend.engineMode(backend), currentEngineMode(),
            "the engine must switch to " + backend
        );
    }

    /** One read of the engine's process-global mode, through the debug stats snapshot. */
    private static int currentEngineMode() {
        final GpuStats stats = GpuBackend.stats();
        assertNotNull(stats, "gpu stats must be available once the library is loaded");
        return stats.engineMode();
    }

    /** Deterministic column heights for a packed cell chunk (the kernels clamp at 62 like the shim). */
    private static int[] chunkHeights(final int points) {
        final int[] heights = new int[points];
        for (int i = 0; i < points; i++) {
            heights[i] = 30 + i % 60;
        }
        return heights;
    }

    /** High half of a packed {@link NativeBaselineSampler#gpuStageStats()} delta: GPU-served stages. */
    private static long gpuServed(final long before, final long after) {
        return (after - before) >>> 32;
    }

    /** Low half of a packed stats delta: stages whose shape the GPU path would attempt. */
    private static long gpuAttempted(final long before, final long after) {
        return (after - before) & 0xFFFFFFFFL;
    }
}
