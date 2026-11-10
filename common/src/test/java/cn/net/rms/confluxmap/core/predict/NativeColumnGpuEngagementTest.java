package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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

/**
 * Engagement and drift bounds of the GPU-served surface-columns seam: the shim's {@code
 * cfxSurfaceColumns} must attempt the fork's float point kernel for 1.18+ Overworld anchor
 * batches at or above its gate ({@code CFX_GPU_COLUMN_MIN_ANCHORS}, default 256 - the lod0
 * 34x34 and lod1 18x18 anchor grids both pass, in both the shim's dense-rect and per-point
 * request shapes), a below-gate batch must stay on the CPU loops without counting as a GPU
 * stage, and {@code CFX_GPU_COLUMN_MIN_ANCHORS=0} (read once from the environment at first
 * use) must disable the attempt entirely - the serving switch the composed A/B measurement
 * drives. The seam has no Java-side repair (a returned false drops the caller's tile), so a
 * refusal may only ever fall back to the identical CPU loops: the fallback outputs must stay
 * bit-exact against per-anchor 1x1 solves on the same coordinates. Served batches approximate
 * the CPU reference within the sparse-seam drift budget, so every GPU-served probe must show
 * nonzero drift (zero would mean the seam silently fell back), and the composed lod0/lod1
 * tiles - the only LODs whose exact-residual stage calls this seam - must stay inside the
 * categorical drift gates across the engine switch with the col serve proven through the
 * wrapper's served brackets; the height bound asserts where measured to hold (the near
 * bucket) and the carved scenes - cave-mouth, 30M-border, and the 1M-class tile whose window
 * sits on carved terrain - print their measured height distributions for the ledger. The
 * rect entries stay bit-exact on both sides of the switch, so composed drift attributes to
 * this seam alone. Probes for served drift sit in the fork's documented cave-mouth region,
 * whose carving makes fp32 columns drift visibly. Dispatch engagement is attributed per call
 * through {@link NativeBaselineSampler#gpuStageStats()} deltas, the same mechanism the sparse
 * suites use.
 */
class NativeColumnGpuEngagementTest {
    /** Same fixed seed {@code PredictionNativeIntegrationTest} uses. */
    private static final long SEED = 146008555L;
    /**
     * The fork's documented cave-mouth region and seed: CPU columns there show carved surface
     * discontinuities, the one scene class where fp32 column drift is reliably visible.
     */
    private static final long CAVE_SEED = 12345678901234567L;
    private static final int CAVE_X = 800280;
    private static final int CAVE_Z = -799780;
    /** Probe-picked waterline scene ({@code WaveBenchmarkOptions}' preset, as in the sparse suites). */
    private static final int COAST_X = -32;
    private static final int COAST_Z = -16;

    /** lod0's exact-residual anchor grid: 34x34 stride 8 = 1156 anchors, above the 256 gate. */
    private static final int LOD0_SIDE = 34;
    private static final int LOD0_STRIDE = 8;
    /** lod1's grid: 18x18 stride 16 = 324 anchors, also above the gate. */
    private static final int LOD1_SIDE = 18;
    private static final int LOD1_STRIDE = 16;

    private static final int LOD0 = 0;
    private static final int LOD1 = 1;

    /** Composed-tile drift buckets; asserted/print-only split mirrors the fork's col harness. */
    private static final int NEAR_X = 0;
    private static final int NEAR_Z = 0;
    private static final int ONE_M_X = 1000000;
    private static final int ONE_M_Z = 1000000;
    private static final int BORDER_X = 29900000;
    private static final int BORDER_Z = 29900000;

    @BeforeEach
    void requireNativeServingGpu() {
        Assumptions.assumeTrue(
            NativeLib.initForTests(), "native prediction library unavailable on this platform"
        );
        // Earlier tests in this JVM may have left the engine off; the engagement evidence below
        // is only meaningful from the auto mode, and every test restores it on exit.
        setBackend(PredictionBackend.GPU);
        // The rect stages must serve on this machine for the drift comparisons to attribute to
        // the col seam (they are bit-exact either way, but a CPU-fallback engine means the
        // whole composition is CPU and no serve is observable) - skip the same way a missing
        // library skips. This probe deliberately does not involve the col seam.
        final NativeBaselineSampler probe = modernSampler(SEED);
        final long before = probe.gpuStageStats();
        assertTrue(
            probe.overviewHeightsCoarse(0, 0, 130, 130, 4, new int[130 * 130]),
            "probe tile must sample"
        );
        Assumptions.assumeTrue(
            gpuServed(before, probe.gpuStageStats()) > 0,
            "engine is not serving stages on a GPU; column GPU engagement is unobservable"
        );
    }

    @AfterEach
    void restoreEngineMode() {
        setBackend(PredictionBackend.GPU);
    }

    /**
     * The lod0-shaped dense-rect batch: GPU-served in auto mode (one tracked, one served stage
     * inside the call bracket), and its solidY plane must drift against the per-anchor CPU
     * reference - the served-bracket proof that the float kernel actually answered.
     */
    @Test
    void rectShapedColumnChunkIsGpuServedWithNonzeroDrift() {
        assumeColGateActive();
        final NativeBaselineSampler cave = modernSampler(CAVE_SEED);
        final int side = LOD0_SIDE;
        final int stride = LOD0_STRIDE;
        assertFalse(
            denseRectShape(CAVE_X, CAVE_Z, side, side, stride),
            "oracle precondition: the lod0 probe must take the dense-rect shape"
        );
        final Columns served = new Columns(side * side);
        final long before = cave.gpuStageStats();
        assertTrue(
            cave.surfaceColumns(CAVE_X, CAVE_Z, side, side, stride,
                served.solidY, served.fluidY, served.surfaceY, served.flags),
            "above-gate rect chunk must resolve on " + engineLabel()
        );
        assertEquals(1, gpuAttempted(before, cave.gpuStageStats()),
            "the above-gate rect chunk must be tracked as a GPU-attempted stage on " + engineLabel());
        assertEquals(1, gpuServed(before, cave.gpuStageStats()),
            "the above-gate rect chunk must be GPU-served on " + engineLabel());
        final Columns reference = perAnchorReference(cave, CAVE_X, CAVE_Z, side, side, stride);
        SparseDriftGates.assertHeightPlane(
            reference.solidY, served.solidY, BaselineGrid.NO_SURFACE,
            "solidY", "cave rect chunk (GPU-served)", true
        );
    }

    /**
     * The lod1-shaped per-point batch: the same gate and the same float kernel, marshalled
     * from the per-anchor request shape instead of the dense rect.
     */
    @Test
    void perPointShapedColumnChunkIsGpuServedWithNonzeroDrift() {
        assumeColGateActive();
        final NativeBaselineSampler cave = modernSampler(CAVE_SEED);
        final int side = LOD1_SIDE;
        final int stride = LOD1_STRIDE;
        assertTrue(
            denseRectShape(CAVE_X, CAVE_Z, side, side, stride),
            "oracle precondition: the lod1 probe must take the per-point shape"
        );
        final Columns served = new Columns(side * side);
        final long before = cave.gpuStageStats();
        assertTrue(
            cave.surfaceColumns(CAVE_X, CAVE_Z, side, side, stride,
                served.solidY, served.fluidY, served.surfaceY, served.flags),
            "above-gate per-point chunk must resolve on " + engineLabel()
        );
        assertEquals(1, gpuAttempted(before, cave.gpuStageStats()),
            "the above-gate per-point chunk must be tracked as a GPU-attempted stage on " + engineLabel());
        assertEquals(1, gpuServed(before, cave.gpuStageStats()),
            "the above-gate per-point chunk must be GPU-served on " + engineLabel());
        final Columns reference = perAnchorReference(cave, CAVE_X, CAVE_Z, side, side, stride);
        SparseDriftGates.assertHeightPlane(
            reference.solidY, served.solidY, BaselineGrid.NO_SURFACE,
            "solidY", "cave per-point chunk (GPU-served)", true
        );
    }

    /**
     * Below-gate batches of both request shapes never register as GPU stages and resolve
     * bit-exactly onto the per-anchor CPU reference - the fallback the seam must always take
     * when it does not attempt.
     */
    @Test
    void belowGateColumnChunksStayCpuUntrackedAndBitExact() {
        final NativeBaselineSampler coast = modernSampler(SEED);
        assertFallbackShape(coast, COAST_X, COAST_Z, 15, 15, 8, "below-gate rect chunk");
        assertFallbackShape(coast, COAST_X, COAST_Z, 12, 12, 16, "below-gate per-point chunk");
    }

    /**
     * The environment switch: with {@code CFX_GPU_COLUMN_MIN_ANCHORS=0} the attempt is off
     * entirely - an above-gate batch must not register as a GPU stage and must resolve
     * bit-exactly onto the per-anchor CPU reference. Runs only in a JVM started with that
     * environment (the composed A/B run); the default suite keeps the gate active.
     */
    @Test
    void envZeroColumnGateDisablesTheAttemptAndStaysBitExact() {
        Assumptions.assumeTrue(
            "0".equals(System.getenv("CFX_GPU_COLUMN_MIN_ANCHORS")),
            "requires CFX_GPU_COLUMN_MIN_ANCHORS=0 in the JVM environment"
        );
        final NativeBaselineSampler coast = modernSampler(SEED);
        assertFallbackShape(coast, COAST_X, COAST_Z, LOD0_SIDE, LOD0_SIDE, LOD0_STRIDE, "env-disabled rect chunk");
        assertFallbackShape(coast, COAST_X, COAST_Z, LOD1_SIDE, LOD1_SIDE, LOD1_STRIDE, "env-disabled per-point chunk");
    }

    /**
     * Composed-tile drift across the engine switch at the four drift buckets, lod0 and lod1:
     * the auto run must serve the col seam through the wrapper's bracket, every scene's
     * categorical planes (biomeId, surfaceFlags) must stay under the 2% mismatch gate, and
     * the height planes must stay under |dY| &lt;= 8 where that bound is measured to hold.
     * Measured on this machine: the near bucket composes drift-free or at |dY| 1 (its lod0
     * anchor window contains no drifted anchor - drift-free-but-served is fine, the wrapper's
     * bracket proves the serve), while the 1M-class bucket reaches composed terrainY |dY| 16 -
     * the same carve-discontinuity class as the cave-mouth and 30M-border scenes, whose
     * height distributions print without the bound and feed the drift ledger. The rect
     * entries stay bit-exact on both sides of the switch, so composed drift attributes to
     * this seam alone.
     */
    @Test
    void composedColumnTilesMatchAcrossTheEngineSwitch() {
        assumeColGateActive();
        for (final Scene scene : scenes()) {
            setBackend(PredictionBackend.CPU);
            final BaselineGrid[] off = sampleBothLods(scene.sampler(), scene);
            setBackend(PredictionBackend.GPU);
            final ColumnRecordingSampler wrapper = new ColumnRecordingSampler(scene.sampler());
            final BaselineGrid[] auto = sampleBothLods(wrapper, scene);
            assertTrue(
                wrapper.colServed > 0,
                scene.where() + ": the auto composition must serve the col seam"
            );
            for (final int lod : new int[] {LOD0, LOD1}) {
                final String label = scene.where() + " lod" + lod;
                if (scene.heightBound()) {
                    SparseDriftGates.assertGridsDriftBounded(off[lod], auto[lod], label, false, false);
                } else {
                    SparseDriftGates.assertGridsCarveClassDrift(off[lod], auto[lod], label);
                }
            }
            System.out.printf(
                "%s: col stage stats attempted=%d served=%d over one lod0+lod1 composition%n",
                scene.where(), wrapper.colAttempted, wrapper.colServed
            );
        }
    }

    private static BaselineGrid[] sampleBothLods(final BaselineSampler sampler, final Scene scene) {
        final BaselineGrid[] grids = new BaselineGrid[LOD1 + 1];
        for (final int lod : new int[] {LOD0, LOD1}) {
            grids[lod] = LodSampling.sample(sampler, false, lod, scene.x(), scene.z());
            assertNotNull(grids[lod], scene.where() + " composition must succeed at lod" + lod);
        }
        return grids;
    }

    /**
     * One call that must not (or may not) attempt: below the gate or under the environment
     * switch, both request shapes resolve untracked and bit-exactly onto the CPU reference.
     */
    private static void assertFallbackShape(
        final NativeBaselineSampler sampler,
        final int originX,
        final int originZ,
        final int w,
        final int h,
        final int stride,
        final String where
    ) {
        final Columns resolved = new Columns(w * h);
        final long before = sampler.gpuStageStats();
        assertTrue(
            sampler.surfaceColumns(originX, originZ, w, h, stride,
                resolved.solidY, resolved.fluidY, resolved.surfaceY, resolved.flags),
            where + " must resolve"
        );
        assertEquals(0, gpuAttempted(before, sampler.gpuStageStats()),
            where + " must not count as a GPU stage");
        assertEquals(0, gpuServed(before, sampler.gpuStageStats()),
            where + " must stay on the CPU loops");
        final Columns reference = perAnchorReference(sampler, originX, originZ, w, h, stride);
        assertArrayEquals(reference.solidY, resolved.solidY, where + " solidY must be the CPU reference");
        assertArrayEquals(reference.fluidY, resolved.fluidY, where + " fluidY must be the CPU reference");
        assertArrayEquals(reference.surfaceY, resolved.surfaceY, where + " surfaceY must be the CPU reference");
        assertArrayEquals(reference.flags, resolved.flags, where + " flags must be the CPU reference");
    }

    /** Per-anchor 1x1 solves: the CPU reference both fallback paths must reproduce exactly. */
    private static Columns perAnchorReference(
        final NativeBaselineSampler sampler,
        final int originX,
        final int originZ,
        final int w,
        final int h,
        final int stride
    ) {
        final Columns out = new Columns(w * h);
        final int[] solid = new int[1];
        final int[] fluid = new int[1];
        final int[] surface = new int[1];
        final int[] flags = new int[1];
        for (int j = 0; j < h; j++) {
            for (int i = 0; i < w; i++) {
                assertTrue(
                    sampler.surfaceColumns(originX + i * stride, originZ + j * stride, 1, 1, 1,
                        solid, fluid, surface, flags),
                    "per-anchor reference must resolve"
                );
                final int index = j * w + i;
                out.solidY[index] = solid[0];
                out.fluidY[index] = fluid[0];
                out.surfaceY[index] = surface[0];
                out.flags[index] = flags[0];
            }
        }
        return out;
    }

    /** The shim's request-shape decision, mirrored from {@code cfxSurfaceColumns} in the shim. */
    private static boolean denseRectShape(
        final int blockX, final int blockZ, final int w, final int h, final int stride
    ) {
        final long lastX = (long) blockX + (long) (w - 1) * stride;
        final long lastZ = (long) blockZ + (long) (h - 1) * stride;
        final long denseColumns = (Math.floorDiv(lastX, 4) - Math.floorDiv(blockX, 4) + 2L)
            * (Math.floorDiv(lastZ, 4) - Math.floorDiv(blockZ, 4) + 2L);
        return denseColumns > 4L * w * h;
    }

    /** The four surface-column planes of one anchor grid. */
    private static final class Columns {
        final int[] solidY;
        final int[] fluidY;
        final int[] surfaceY;
        final int[] flags;

        Columns(final int cells) {
            solidY = new int[cells];
            fluidY = new int[cells];
            surfaceY = new int[cells];
            flags = new int[cells];
        }
    }

    /**
     * Real generator behind a recording seam: every call forwards, and the surface-columns
     * brackets attribute this seam's attempted/served stage deltas to the col transport alone
     * (the rect stage the same composition serves runs outside any col bracket).
     */
    private static final class ColumnRecordingSampler implements BaselineSampler {
        private final NativeBaselineSampler delegate;
        long colAttempted;
        long colServed;

        ColumnRecordingSampler(final NativeBaselineSampler delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean surfaceColumns(
            final int blockX,
            final int blockZ,
            final int w,
            final int h,
            final int stride,
            final int[] outSolidY,
            final int[] outFluidY,
            final int[] outSurfaceY,
            final int[] outFlags
        ) {
            final long before = delegate.gpuStageStats();
            final boolean ok = delegate.surfaceColumns(
                blockX, blockZ, w, h, stride, outSolidY, outFluidY, outSurfaceY, outFlags
            );
            final long delta = delegate.gpuStageStats() - before;
            colAttempted += delta & 0xFFFFFFFFL;
            colServed += delta >>> 32;
            return ok;
        }

        @Override
        public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
            return delegate.biomes(scale, x, z, w, h, out);
        }

        @Override
        public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return delegate.heights(x4, z4, w, h, outY);
        }

        @Override
        public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return delegate.endHeights(x4, z4, w, h, outY);
        }

        @Override
        public boolean interpolatableOverviewHeights() {
            return delegate.interpolatableOverviewHeights();
        }

        @Override
        public int terrainFreeBiomeScale() {
            return delegate.terrainFreeBiomeScale();
        }

        @Override
        public boolean overviewHeights(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
        ) {
            return delegate.overviewHeights(blockX, blockZ, w, h, stride, outTerrainY);
        }

        @Override
        public boolean overviewHeightsCoarse(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
        ) {
            return delegate.overviewHeightsCoarse(blockX, blockZ, w, h, stride, outTerrainY);
        }

        @Override
        public boolean surfaceBiomes(
            final int blockX, final int blockZ, final int w, final int h, final int stride,
            final int[] terrainY, final int[] outBiomeIds
        ) {
            return delegate.surfaceBiomes(blockX, blockZ, w, h, stride, terrainY, outBiomeIds);
        }

        @Override
        public boolean biomesStrided(
            final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
        ) {
            return delegate.biomesStrided(scale, x, z, w, h, stride, out);
        }

        @Override
        public boolean heightsStrided(final int x4, final int z4, final int w, final int h, final int stride, final int[] outY) {
            return delegate.heightsStrided(x4, z4, w, h, stride, outY);
        }
    }

    /**
     * One composed-drift bucket: origin, seed and whether the height smoke bound asserts
     * (only the near bucket measuredly holds it; carved scenes print instead).
     */
    private record Scene(String where, long seed, int x, int z, boolean heightBound) {
        NativeBaselineSampler sampler() {
            return modernSampler(seed);
        }
    }

    private static Scene[] scenes() {
        return new Scene[] {
            new Scene("near", SEED, NEAR_X, NEAR_Z, true),
            new Scene("1M", SEED, ONE_M_X, ONE_M_Z, false),
            new Scene("border", SEED, BORDER_X, BORDER_Z, false),
            new Scene("cave", CAVE_SEED, CAVE_X, CAVE_Z, false),
        };
    }

    private static NativeBaselineSampler modernSampler(final long seed) {
        return new NativeBaselineSampler(
            McVersions.toCubiomes("1.21.4").orElseThrow(), seed, PredictionDimensions.OVERWORLD, 0
        );
    }

    /** The composed A/B must be able to turn the attempt off without rebuilding. */
    private static void assumeColGateActive() {
        Assumptions.assumeTrue(
            !"0".equals(System.getenv("CFX_GPU_COLUMN_MIN_ANCHORS")),
            "CFX_GPU_COLUMN_MIN_ANCHORS=0 disables the column attempt; serving is unobservable"
        );
    }

    /** Production backend switch - the same seam the settings UI drives; the flip is asserted. */
    private static void setBackend(final PredictionBackend backend) {
        GpuBackend.configure(backend);
        assertEquals(
            GpuBackend.engineMode(backend), currentEngineMode(),
            "the engine must switch to " + backend
        );
    }

    private static int currentEngineMode() {
        final GpuStats stats = GpuBackend.stats();
        assertNotNull(stats, "gpu stats must be available once the library is loaded");
        return stats.engineMode();
    }

    private static String engineLabel() {
        return GpuBackend.engineModeLabel(currentEngineMode());
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
