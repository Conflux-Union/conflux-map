package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Parity and accounting of the packed sparse refinement entries ({@link
 * NativeBaselineSampler#overviewHeightsSparse}/{@link NativeBaselineSampler#surfaceBiomesSparse})
 * against the per-point entries they replace: the collected band requests must resolve
 * identically on both transports (they are lattice-driven, and the rect anchors both transports
 * read are bit-exact), values resolved through the GPU-attempted packed transport must stay
 * inside the Phase 5 drift bounds against the per-point reference (the seam serves the fp32
 * kernels since Phase 5 - {@link SparseDriftGates} carries the bounds and prints the measured
 * rates), a refused entry must repair through the per-point path onto the identical grid, the
 * packed transport must collapse the per-point JNI call counts at equal point totals, and a
 * library without the new symbols must latch the capability off without disabling the library.
 * The cell requests are collected from the band-refined height grid, so the packed transport's
 * drifted band heights can move a height-threshold cell in or out of the collection: the request
 * sets may diverge within the same smoke scale the drift bounds use, the matched requests are
 * drift-bounded value-for-value, and the unmatched counts print. Above-gate chunks are served by
 * the float sparse kernels on a capable library and repaired on the CPU loops otherwise, so the
 * drift bounds hold on GPU and CPU-fallback machines alike - the nonzero-drift proof applies
 * only where the wrapper's served-stage brackets show the GPU actually served. Pre-1.18,
 * wrong-dimension, below-gate and latched-off resolutions never touch the float kernels and
 * keep exact equality.
 */
class NativeSparseBatchParityTest {
    /** Same fixed seed {@code PredictionNativeIntegrationTest} uses. */
    private static final long SEED = 146008555L;
    private static final int LOD2 = 2;
    private static final int LOD4 = 4;
    /** Probe-picked waterline and mixed scenes for this seed (WaveBenchmarkOptions' presets). */
    private static final int COAST_X = -32;
    private static final int COAST_Z = -16;
    private static final int MIXED_X = -288;
    private static final int MIXED_Z = 64;
    /** LodSampling's own dispatch-below-this-counts-per-point threshold. */
    private static final int SPARSE_MIN_POINTS = 16;

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(
            NativeLib.initForTests(), "native prediction library unavailable on this platform"
        );
    }

    private static int mc21() {
        final OptionalInt mc = McVersions.toCubiomes("1.21.4");
        assertTrue(mc.isPresent(), "McVersions must know \"1.21.4\"");
        return mc.getAsInt();
    }

    private static int mc17() {
        final OptionalInt mc = McVersions.toCubiomes("1.17");
        assertTrue(mc.isPresent(), "McVersions must know \"1.17\"");
        return mc.getAsInt();
    }

    private static NativeBaselineSampler modernSampler() {
        return new NativeBaselineSampler(mc21(), SEED, PredictionDimensions.OVERWORLD, 0);
    }

    private static NativeBaselineSampler legacySampler() {
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(SEED, mc17());
        return new NativeBaselineSampler(
            state.mcVersion(), state.seed(),
            PredictionDimensions.nativeDim(DimensionId.OVERWORLD),
            state.cubiomesFlags(DimensionId.OVERWORLD)
        );
    }

    @Test
    void modernBandAndCellTransportsMatchPerPoint() {
        for (final int[] origin : new int[][] {{COAST_X, COAST_Z}, {MIXED_X, MIXED_Z}}) {
            final RecordingNativeSampler perPoint = recording(false, false);
            final BaselineGrid repaired = LodSampling.sample(perPoint, false, LOD2, origin[0], origin[1]);
            assertNotNull(repaired, "per-point composition must succeed at " + origin[0] + "," + origin[1]);
            final RecordingNativeSampler sparse = recording(true, true);
            final BaselineGrid batched = LodSampling.sample(sparse, false, LOD2, origin[0], origin[1]);
            assertNotNull(batched, "sparse composition must succeed at " + origin[0] + "," + origin[1]);

            assertTrue(
                perPoint.bandPoints.size() >= SPARSE_MIN_POINTS,
                "oracle precondition: the waterline band must dispatch at " + origin[0] + "," + origin[1]
            );
            assertTrue(
                perPoint.cellPoints.size() >= SPARSE_MIN_POINTS,
                "oracle precondition: the cell sweep must dispatch at " + origin[0] + "," + origin[1]
            );
            assertEquals(
                perPoint.bandPoints, sparse.bandPoints,
                "band request collection must not depend on the transport"
            );
            // The packed band heights serve the float kernels where the wrapper's served brackets
            // prove it, so the per-point 1x1 solves are the exact reference and the packed values
            // are drift-bounded against them (printed rates).
            SparseDriftGates.assertHeightPlane(
                toArray(perPoint.bandValues), toArray(sparse.bandValues), BaselineGrid.NO_SURFACE,
                "band heights", originLabel(origin), sparse.bandServed > 0
            );
            // The cell requests are collected from the band-refined height grid, so the packed
            // transport's drifted band heights can move a height-threshold cell into or out of
            // the collection; the divergence is drift-driven and bounded on the smoke scale, and
            // the drift gate below compares only the matched requests.
            assertEquals(
                perPoint.cellPoints.size() * 4, perPoint.cellValues.size(),
                "oracle precondition: the recorded cells must be uniformly edge-2 (4 points per cell)"
            );
            assertEquals(
                sparse.cellPoints.size() * 4, sparse.cellValues.size(),
                "oracle precondition: the recorded cells must be uniformly edge-2 (4 points per cell)"
            );
            assertCellRequestsDivergeWithinSmokeScale(perPoint, sparse, originLabel(origin));
            SparseDriftGates.assertCategoricalPlane(
                matchedCellValues(perPoint, sparse), matchedCellValues(sparse, perPoint),
                "cell biomes", originLabel(origin), sparse.cellServed > 0
            );
            SparseDriftGates.assertGridsDriftBounded(
                repaired, batched, originLabel(origin), sparse.cellServed > 0, sparse.bandServed > 0
            );
        }
    }

    /**
     * Pre-1.18 parity, built directly: that generator opts out of the interpolation lattice, so
     * LodSampling never collects band or cell requests there - but the packed entries still
     * serve the same columns and cells, and the legacy overview's per-point biome dependency is
     * exactly what the sparse loop must reproduce.
     */
    @Test
    void legacyBandAndCellTransportsMatchPerPoint() {
        final NativeBaselineSampler sampler = legacySampler();

        final int step = 16;
        final int span = 17;
        final int[] xs = new int[span * span];
        final int[] zs = new int[xs.length];
        int i = 0;
        for (int z = 0; z < span; z++) {
            for (int x = 0; x < span; x++) {
                xs[i] = COAST_X - 64 + x * step;
                zs[i] = COAST_Z - 64 + z * step;
                i++;
            }
        }
        final int[] sparseHeights = new int[xs.length];
        assertTrue(sampler.overviewHeightsSparse(xs, zs, xs.length, sparseHeights));
        final int[] perPointHeights = new int[xs.length];
        final int[] single = new int[1];
        for (int k = 0; k < xs.length; k++) {
            assertTrue(sampler.overviewHeights(xs[k], zs[k], 1, 1, 4, single), "1x1 solve must succeed");
            perPointHeights[k] = single[0];
        }
        assertArrayEquals(
            perPointHeights, sparseHeights,
            "legacy packed heights must be bit-equal to the 1x1 overview solves"
        );

        final int cells = 16;
        final int edge = 2;
        final int stride = 4;
        final int[] cellX = new int[cells * cells];
        final int[] cellZ = new int[cellX.length];
        for (int k = 0; k < cellX.length; k++) {
            cellX[k] = COAST_X - 64 + (k % cells) * 8;
            cellZ[k] = COAST_Z - 64 + (k / cells) * 8;
        }
        final int points = edge * edge;
        final int[] heights = new int[cellX.length * points];
        java.util.Arrays.setAll(heights, h -> 40 + (h % 64));
        final int[] sparseBiomes = new int[heights.length];
        assertTrue(sampler.surfaceBiomesSparse(cellX, cellZ, edge, stride, heights, cellX.length, sparseBiomes));
        final int[] perCellBiomes = new int[sparseBiomes.length];
        final int[] cellHeights = new int[points];
        final int[] cellOut = new int[points];
        for (int k = 0; k < cellX.length; k++) {
            System.arraycopy(heights, k * points, cellHeights, 0, points);
            assertTrue(
                sampler.surfaceBiomes(cellX[k], cellZ[k], edge, edge, stride, cellHeights, cellOut),
                "per-cell solve must succeed"
            );
            System.arraycopy(cellOut, 0, perCellBiomes, k * points, points);
        }
        assertArrayEquals(
            perCellBiomes, sparseBiomes,
            "legacy packed cell biomes must be bit-equal to the per-cell surface solves"
        );
    }

    @Test
    void wrongDimensionRefusesAndRepairReproducesTheSparseGrid() {
        final NativeBaselineSampler nether = new NativeBaselineSampler(
            mc21(), SEED, PredictionDimensions.nativeDim(DimensionId.NETHER), 0
        );
        final int[] xs = {COAST_X, COAST_X + 16, COAST_X + 32};
        final int[] zs = {COAST_Z, COAST_Z + 16, COAST_Z + 32};
        assertFalse(
            nether.overviewHeightsSparse(xs, zs, xs.length, new int[xs.length]),
            "the wrong-dimension context must refuse the packed height entry"
        );
        assertFalse(
            nether.surfaceBiomesSparse(xs, zs, 2, 4, new int[12], xs.length, new int[12]),
            "the wrong-dimension context must refuse the packed cell entry"
        );

        // The batched side runs through the recording wrapper so the served-stage brackets can
        // tell the drift gate where the GPU actually served the packed transport.
        final RecordingNativeSampler batchedTransport = recording(true, true);
        final BaselineGrid batched = LodSampling.sample(batchedTransport, false, LOD2, COAST_X, COAST_Z);
        assertNotNull(batched);
        final RefusingSparseSampler refused = new RefusingSparseSampler();
        final BaselineGrid repaired = LodSampling.sample(refused, false, LOD2, COAST_X, COAST_Z);
        assertNotNull(repaired);
        assertTrue(
            refused.refusedSparseCalls >= 2,
            "oracle precondition: both packed entries must have been reached"
        );
        SparseDriftGates.assertGridsDriftBounded(
            repaired, batched, originLabel(new int[] {COAST_X, COAST_Z}),
            batchedTransport.cellServed > 0, batchedTransport.bandServed > 0
        );
    }

    @Test
    void sparseTransportCollapsesPerPointCallCounts() {
        for (final int lod : new int[] {LOD2, LOD4}) {
            // The per-point baseline is the repair transport (refused packed entries repair
            // point by point): the interface's default loop is internal to the sampler, so a
            // latched-off composition stays one wrapper-visible call and cannot show the drop.
            // The refusal baseline double-counts points (refused chunk + repair both cross the
            // wrapper), so the equal-workload check runs against the latched-off composition,
            // whose count covers exactly the packed points.
            final long[] perPoint = composeStageCounts(lod, Transport.REFUSED);
            final long[] packed = composeStageCounts(lod, Transport.PACKED);
            final long[] latched = composeStageCounts(lod, Transport.LATCHED_OFF);
            assertTrue(
                packed[0] < perPoint[0],
                "lod" + lod + " H-bucket call count must drop: per-point=" + perPoint[0] + " sparse=" + packed[0]
            );
            assertTrue(
                packed[1] < perPoint[1],
                "lod" + lod + " B-bucket call count must drop: per-point=" + perPoint[1] + " sparse=" + packed[1]
            );
            // The sampled point total no longer pins exactly across transports: the cell requests
            // are collected from the band-refined heights, which drift on the packed transport,
            // so a height-threshold cell can join or leave the collection (measured: 4 of ~55.9k
            // points at the coast lod2). The smoke bound absorbs the threshold flips on the same
            // scale as the drift bounds; the counts print below.
            assertTrue(
                Math.abs(packed[2] - latched[2]) <= latched[2] / 50,
                "lod" + lod + " sampled point totals must stay within the 2% drift smoke scale: packed="
                    + packed[2] + " latched=" + latched[2]
            );
            System.out.printf(
                "lod%d H calls %d -> %d, B calls %d -> %d, points %d (latched-off %d)%n",
                lod, perPoint[0], packed[0], perPoint[1], packed[1], packed[2], latched[2]
            );
        }
    }

    /**
     * The latched-off behavior of the missing-symbol tolerance. The {@code UnsatisfiedLinkError}
     * latch write itself is not constructible against a fresh library (the sampler is final and
     * the context cannot be faked), so the catch path stays source-verified; this pins what
     * every later call must do: resolve through the interface's default loops with correct
     * outputs, and never disable the library for the session.
     */
    @Test
    void latchedSparseCapabilityTakesTheDefaultLoop() {
        final NativeBaselineSampler healthy = modernSampler();
        final int[] xs = {COAST_X, COAST_X + 16, COAST_X + 32, COAST_X + 48, COAST_X + 64};
        final int[] zs = {COAST_Z, COAST_Z + 16, COAST_Z + 32, COAST_Z + 48, COAST_Z + 64};
        final int n = xs.length;
        final int[] perPointHeights = new int[n];
        final int[] single = new int[1];
        for (int k = 0; k < n; k++) {
            assertTrue(healthy.overviewHeights(xs[k], zs[k], 1, 1, 4, single));
            perPointHeights[k] = single[0];
        }
        final int[] packedHeights = {64, 60, 58, 55, 70, 63, 61, 52, 68, 59, 57, 62, 65, 54, 60, 58, 71, 56, 53, 66};
        final int edge = 2;
        final int[] perPointBiomes = new int[packedHeights.length];
        final int[] cellHeights = new int[edge * edge];
        final int[] cellOut = new int[edge * edge];
        for (int k = 0; k < n; k++) {
            System.arraycopy(packedHeights, k * edge * edge, cellHeights, 0, edge * edge);
            assertTrue(healthy.surfaceBiomes(xs[k], zs[k], edge, edge, 4, cellHeights, cellOut));
            System.arraycopy(cellOut, 0, perPointBiomes, k * edge * edge, edge * edge);
        }

        NativeBaselineSampler.setSparseNativeAvailable(false);
        try {
            final NativeBaselineSampler stale = modernSampler();
            final int[] out = new int[n];
            assertTrue(stale.overviewHeightsSparse(xs, zs, n, out));
            assertArrayEquals(perPointHeights, out, "a latched-off band must resolve per point");
            final int[] biomes = new int[packedHeights.length];
            assertTrue(stale.surfaceBiomesSparse(xs, zs, edge, 4, packedHeights, n, biomes));
            assertArrayEquals(perPointBiomes, biomes, "a latched-off cell sweep must resolve per cell");
            assertTrue(
                NativeLib.available(),
                "a latched sparse capability must not disable the library for the session"
            );
        } finally {
            NativeBaselineSampler.setSparseNativeAvailable(true);
        }
    }

    /** Which transport a measurement composition drives through the counted wrapper. */
    private enum Transport {
        /** Packed native entries, latch on. */
        PACKED,
        /** Packed entries refuse and repair point by point - the per-point baseline. */
        REFUSED,
        /** Sparse capability latched off - the interface default loop carries the requests. */
        LATCHED_OFF
    }

    /**
     * H/B stage call counts and sampled points for one full-tile composition at {@code lod} on
     * the given transport.
     */
    private long[] composeStageCounts(final int lod, final Transport transport) {
        if (transport == Transport.LATCHED_OFF) {
            NativeBaselineSampler.setSparseNativeAvailable(false);
        }
        try {
            final CountingSampler counting = new CountingSampler(
                transport == Transport.REFUSED ? new RefusingSparseSampler() : modernSampler()
            );
            assertNotNull(LodSampling.sample(counting, false, lod, COAST_X, COAST_Z));
            assertEquals(
                transport == Transport.LATCHED_OFF,
                !NativeBaselineSampler.sparseNativeAvailable(),
                "the composition must not change the latch state"
            );
            return new long[] {
                counting.stageCalls()[SampleStage.H.ordinal()],
                counting.stageCalls()[SampleStage.B.ordinal()],
                counting.points()
            };
        } finally {
            NativeBaselineSampler.setSparseNativeAvailable(true);
        }
    }
    private static RecordingNativeSampler recording(final boolean sparseHeights, final boolean sparseCells) {
        return new RecordingNativeSampler(modernSampler(), sparseHeights, sparseCells);
    }

    /**
     * Real generator behind a recording seam: both packed entries can either forward to the
     * native transport or take the interface's default per-point loops, while every request
     * coordinate and every resolved value is recorded in call order, so a test can compare the
     * two transports element-for-element. The sparse branches also bracket the native calls with
     * the delegate's served-stage deltas ({@link NativeBaselineSampler#gpuStageStats()}), so a
     * drift comparison can require nonzero drift exactly where the GPU actually served.
     */
    private static final class RecordingNativeSampler implements BaselineSampler {
        private final NativeBaselineSampler delegate;
        private final boolean sparseHeights;
        private final boolean sparseCells;
        /** Packed band coordinates and resolved heights, per sparse or per-point call in order. */
        final List<Long> bandPoints = new ArrayList<>();
        final List<Integer> bandValues = new ArrayList<>();
        /** Packed cell origins and resolved biomes, per sparse chunk or per-cell call in order. */
        final List<Long> cellPoints = new ArrayList<>();
        final List<Integer> cellValues = new ArrayList<>();
        /** GPU-served stage delta accumulated across this wrapper's sparse band dispatches. */
        long bandServed;
        /** GPU-served stage delta accumulated across this wrapper's sparse cell dispatches. */
        long cellServed;

        RecordingNativeSampler(
            final NativeBaselineSampler delegate,
            final boolean sparseHeights,
            final boolean sparseCells
        ) {
            this.delegate = delegate;
            this.sparseHeights = sparseHeights;
            this.sparseCells = sparseCells;
        }

        @Override
        public boolean overviewHeightsSparse(final int[] blockX, final int[] blockZ, final int n, final int[] outTerrainY) {
            for (int i = 0; i < n; i++) {
                bandPoints.add(pack(blockX[i], blockZ[i]));
            }
            final long statsBefore = delegate.gpuStageStats();
            final boolean ok = sparseHeights
                ? delegate.overviewHeightsSparse(blockX, blockZ, n, outTerrainY)
                : BaselineSampler.super.overviewHeightsSparse(blockX, blockZ, n, outTerrainY);
            bandServed += servedStages(statsBefore, delegate.gpuStageStats());
            for (int i = 0; i < n; i++) {
                bandValues.add(outTerrainY[i]);
            }
            return ok;
        }

        @Override
        public boolean surfaceBiomesSparse(
            final int[] cellX,
            final int[] cellZ,
            final int edge,
            final int stride,
            final int[] terrainYPacked,
            final int n,
            final int[] outBiomeIdsPacked
        ) {
            for (int i = 0; i < n; i++) {
                cellPoints.add(pack(cellX[i], cellZ[i]));
            }
            final long statsBefore = delegate.gpuStageStats();
            final boolean ok = sparseCells
                ? delegate.surfaceBiomesSparse(cellX, cellZ, edge, stride, terrainYPacked, n, outBiomeIdsPacked)
                : BaselineSampler.super.surfaceBiomesSparse(
                    cellX, cellZ, edge, stride, terrainYPacked, n, outBiomeIdsPacked
                );
            cellServed += servedStages(statsBefore, delegate.gpuStageStats());
            for (int i = 0; i < n * edge * edge; i++) {
                cellValues.add(outBiomeIdsPacked[i]);
            }
            return ok;
        }

        /** High half of a packed {@link NativeBaselineSampler#gpuStageStats()} delta. */
        private static long servedStages(final long before, final long after) {
            return (after - before) >>> 32;
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
        public boolean surfaceColumns(
            final int blockX, final int blockZ, final int w, final int h, final int stride,
            final int[] outSolidY, final int[] outFluidY, final int[] outSurfaceY, final int[] outFlags
        ) {
            return delegate.surfaceColumns(blockX, blockZ, w, h, stride, outSolidY, outFluidY, outSurfaceY, outFlags);
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

        private static long pack(final int blockX, final int blockZ) {
            return ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL);
        }
    }

    /**
     * Real generator whose packed entries always refuse: the collectors must send every request
     * through the per-point repair and land on the identical grid.
     */
    private static final class RefusingSparseSampler implements BaselineSampler {
        private final NativeBaselineSampler delegate;
        int refusedSparseCalls;

        RefusingSparseSampler() {
            this.delegate = modernSampler();
        }

        @Override
        public boolean overviewHeightsSparse(final int[] blockX, final int[] blockZ, final int n, final int[] outTerrainY) {
            refusedSparseCalls++;
            return false;
        }

        @Override
        public boolean surfaceBiomesSparse(
            final int[] cellX,
            final int[] cellZ,
            final int edge,
            final int stride,
            final int[] terrainYPacked,
            final int n,
            final int[] outBiomeIdsPacked
        ) {
            refusedSparseCalls++;
            return false;
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
        public boolean surfaceColumns(
            final int blockX, final int blockZ, final int w, final int h, final int stride,
            final int[] outSolidY, final int[] outFluidY, final int[] outSurfaceY, final int[] outFlags
        ) {
            return delegate.surfaceColumns(blockX, blockZ, w, h, stride, outSolidY, outFluidY, outSurfaceY, outFlags);
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
    }

    /** {@code x,z} label of a probe origin for the drift-gate prints and failure messages. */
    private static String originLabel(final int[] origin) {
        return origin[0] + "," + origin[1];
    }

    /** Unboxes a recorded value list into the array form the drift gates compare. */
    private static int[] toArray(final List<Integer> values) {
        final int[] out = new int[values.size()];
        java.util.Arrays.setAll(out, values::get);
        return out;
    }

    /**
     * The two transports' cell request sets may diverge at height-threshold cells (the collection
     * reads the band-refined heights, which drift on the packed transport), so instead of
     * requiring identical request sets the parity gate bounds the divergence on the same smoke
     * scale as the drift bounds and prints both counts; the matched cells resolve
     * value-for-value through {@link #matchedCellValues}.
     */
    private static void assertCellRequestsDivergeWithinSmokeScale(
        final RecordingNativeSampler perPoint, final RecordingNativeSampler sparse, final String where
    ) {
        final Set<Long> sparseCells = new HashSet<>(sparse.cellPoints);
        int common = 0;
        for (final long cell : perPoint.cellPoints) {
            if (sparseCells.contains(cell)) {
                common++;
            }
        }
        final int unmatched = perPoint.cellPoints.size() + sparse.cellPoints.size() - 2 * common;
        final double rate = 100.0 * unmatched / perPoint.cellPoints.size();
        System.out.printf(
            "cell requests %s drift: per-point %d, packed %d, unmatched %d (%.4f%%)%n",
            where, perPoint.cellPoints.size(), sparse.cellPoints.size(), unmatched, rate
        );
        assertTrue(
            rate < 2.0,
            "cell requests " + where + ": unmatched rate " + rate + "% >= 2.0% smoke bound"
        );
    }

    /**
     * Values of {@code from}'s recorded cells whose origins also appear in {@code to}'s
     * recording, in {@code from}'s scan order. The common cells appear in the same relative
     * order in both transports (each recording is the same scan with different cells skipped),
     * so the two matched arrays align element-for-element for the drift gate. The compositions'
     * cell sweeps are uniformly edge-2 ({@code step = 2 * subPerAxis} with supersampling
     * dormant), pinned by the oracle precondition in the caller.
     */
    private static int[] matchedCellValues(final RecordingNativeSampler from, final RecordingNativeSampler to) {
        final Set<Long> toCells = new HashSet<>(to.cellPoints);
        final int pointsPerCell = 4;
        int matched = 0;
        for (final long cell : from.cellPoints) {
            if (toCells.contains(cell)) {
                matched++;
            }
        }
        final int[] out = new int[matched * pointsPerCell];
        int outIndex = 0;
        int valueIndex = 0;
        for (final long cell : from.cellPoints) {
            if (!toCells.contains(cell)) {
                valueIndex += pointsPerCell;
                continue;
            }
            for (int p = 0; p < pointsPerCell; p++) {
                out[outIndex++] = from.cellValues.get(valueIndex++);
            }
        }
        return out;
    }
}
