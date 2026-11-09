package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the sparse refinement seam between {@link LodSampling}'s collection/execution split and
 * {@link BaselineSampler}: the band and cells collectors must select the same requests the old
 * per-point loops issued, sparse transport must stay bit-identical to those per-point entries,
 * refusal must repair through them in collection order, and accounting must attribute the packed
 * calls to the same stage buckets. Everything runs on {@link RecordingWaveSampler}, whose sparse
 * and per-point entries agree by construction.
 */
class LodSamplingSparseSeamTest {
    private static final int LOD = 2;
    /**
     * The fake's anchor heights step by 8 in both axes, so the origin fixes their residue mod 8:
     * this one leaves adjacent lattice anchors straddling the biome-decides-fluid height (61),
     * which is what makes both collectors select requests at all.
     */
    private static final int ORIGIN_X = 0;
    private static final int ORIGIN_Z = 12345;

    @Test
    void bandSparseRequestsMatchPerPointSelection() {
        final RecordingWaveSampler perPoint = new RecordingWaveSampler();
        perPoint.refuseSparse = true;
        final BaselineGrid repaired = LodSampling.sample(perPoint, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(repaired);
        final Set<Long> perPointSet = new HashSet<>(bandPoints(perPoint));
        assertFalse(perPointSet.isEmpty(), "oracle precondition: the band must request pixels");

        final RecordingWaveSampler sparse = new RecordingWaveSampler();
        final BaselineGrid batched = LodSampling.sample(sparse, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(batched);
        assertFalse(sparse.sparseCalls.isEmpty(), "this geometry must cross the sparse threshold");

        assertEquals(perPointSet, new HashSet<>(sparse.sparseBandRequests));
        assertGridsBitEqual(repaired, batched);
    }

    @Test
    void cellsSparseRequestsMatchPerCellSelection() {
        final RecordingWaveSampler perPoint = new RecordingWaveSampler();
        perPoint.refuseSparse = true;
        final BaselineGrid repaired = LodSampling.sample(perPoint, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(repaired);
        final Set<Long> perPointSet = new HashSet<>(cellPoints(perPoint));
        assertFalse(perPointSet.isEmpty(), "oracle precondition: some cells must need exact biomes");

        final RecordingWaveSampler sparse = new RecordingWaveSampler();
        final BaselineGrid batched = LodSampling.sample(sparse, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(batched);
        assertTrue(
            sparse.sparseCalls.stream().anyMatch(c -> c.startsWith("cellsSparse:")),
            "this geometry must cross the sparse threshold"
        );

        assertEquals(perPointSet, new HashSet<>(sparse.sparseCellRequests));
        assertGridsBitEqual(repaired, batched);
    }

    @Test
    void sparseRefusalRepairsThroughPerPointPath() {
        final RecordingWaveSampler refused = new RecordingWaveSampler();
        refused.refuseSparse = true;
        final BaselineGrid repaired = LodSampling.sample(refused, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(repaired);
        assertFalse(refused.sparseCalls.isEmpty(), "refused sparse entries must still be attempted");

        final RecordingWaveSampler sparse = new RecordingWaveSampler();
        final BaselineGrid batched = LodSampling.sample(sparse, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(batched);

        assertEquals(new HashSet<>(sparse.sparseBandRequests), new HashSet<>(bandPoints(refused)));
        assertEquals(new HashSet<>(sparse.sparseCellRequests), new HashSet<>(cellPoints(refused)));
        assertGridsBitEqual(repaired, batched);
    }

    @Test
    void smallRequestCountSkipsSparseTransport() {
        final OneCrossingQuadSampler sampler = new OneCrossingQuadSampler();
        final BaselineGrid grid = LodSampling.sampleOverworldWindow(
            sampler, LOD, ORIGIN_X, ORIGIN_Z, 60, 60, 60, 60
        );
        assertNotNull(grid);
        final int bandCount = bandCalls(sampler);
        assertTrue(
            bandCount >= 1 && bandCount < 16,
            "precondition: window must select a sub-threshold band count, got " + bandCount
        );
        assertTrue(
            sparseCallsAbsent(sampler),
            "below the threshold the per-point entries must carry every request"
        );
    }

    @Test
    void emptyBandCollectionMakesNoSamplerCall() {
        final FlatDeepWaterSampler sampler = new FlatDeepWaterSampler();
        final BaselineGrid grid = LodSampling.sample(sampler, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(grid);
        assertFalse(
            sampler.calls.stream().anyMatch(c -> c.equals("overviewHeights")),
            "an all-interpolated band must not fall back to per-point solves"
        );
        assertTrue(sparseCallsAbsent(sampler));
    }

    @Test
    void sparseFailureFailsLikePerPointFailure() {
        final RecordingWaveSampler probe = new RecordingWaveSampler();
        probe.refuseSparse = true;
        assertNotNull(LodSampling.sample(probe, false, LOD, ORIGIN_X, ORIGIN_Z));
        final List<Long> points = bandPoints(probe);
        assertFalse(points.isEmpty());
        final long failing = points.get(0);

        final RecordingWaveSampler armedSparse = new RecordingWaveSampler();
        armedSparse.failSparseBandAt = failing;
        armedSparse.failOverviewHeightsAt = failing;
        assertNull(
            LodSampling.sample(armedSparse, false, LOD, ORIGIN_X, ORIGIN_Z),
            "a failed sparse chunk must abort the tile like the per-point loop"
        );
        assertFalse(armedSparse.sparseBandRequests.isEmpty(), "the sparse entry must have been reached");

        final RecordingWaveSampler armedPerPoint = new RecordingWaveSampler();
        armedPerPoint.refuseSparse = true;
        armedPerPoint.failOverviewHeightsAt = failing;
        assertNull(LodSampling.sample(armedPerPoint, false, LOD, ORIGIN_X, ORIGIN_Z));

        assertEquals(bandPoints(armedPerPoint), bandPoints(armedSparse));
        assertEquals(List.of(failing), bandPoints(armedSparse));
    }

    @Test
    void countingSamplerAttributesSparseCallsAndPoints() {
        final RecordingWaveSampler fake = new RecordingWaveSampler();
        final CountingSampler counting = new CountingSampler(fake);

        final int[] xs = {0, 16, 32};
        final int[] zs = {0, -16, 32};
        final int[] y = new int[3];
        long pointsBefore = counting.points();
        long callsBefore = counting.stageCalls()[SampleStage.H.ordinal()];
        final long nanosBefore = counting.stageNanos()[SampleStage.H.ordinal()];
        assertTrue(counting.overviewHeightsSparse(xs, zs, 3, y));
        assertEquals(3, counting.points() - pointsBefore, "band sparse points = n");
        assertEquals(1, counting.stageCalls()[SampleStage.H.ordinal()] - callsBefore);
        assertTrue(counting.stageNanos()[SampleStage.H.ordinal()] > nanosBefore);
        assertEquals(3, fake.sparseBandRequests.size());

        // The sparse entry lands in the same bucket its per-point entry uses.
        pointsBefore = counting.points();
        callsBefore = counting.stageCalls()[SampleStage.H.ordinal()];
        assertTrue(counting.overviewHeights(0, 0, 1, 1, 4, new int[1]));
        assertEquals(1, counting.points() - pointsBefore);
        assertEquals(1, counting.stageCalls()[SampleStage.H.ordinal()] - callsBefore);

        // LOD2 lattice cells are 2x2 points, so n cells carry n*4 points into the B bucket.
        final int[] cellX = {0, 8};
        final int[] cellZ = {0, 8};
        final int[] packedHeights = new int[8];
        final int[] packedOut = new int[8];
        pointsBefore = counting.points();
        callsBefore = counting.stageCalls()[SampleStage.B.ordinal()];
        final long cellNanosBefore = counting.stageNanos()[SampleStage.B.ordinal()];
        assertTrue(counting.surfaceBiomesSparse(cellX, cellZ, 2, 4, packedHeights, 2, packedOut));
        assertEquals(8, counting.points() - pointsBefore, "cells sparse points = n*edge*edge");
        assertEquals(1, counting.stageCalls()[SampleStage.B.ordinal()] - callsBefore);
        assertTrue(counting.stageNanos()[SampleStage.B.ordinal()] > cellNanosBefore);
        assertEquals(2, fake.sparseCellRequests.size());
        pointsBefore = counting.points();
        callsBefore = counting.stageCalls()[SampleStage.B.ordinal()];
        assertTrue(counting.surfaceBiomes(0, 0, 2, 2, 4, new int[4], new int[4]));
        assertEquals(1, counting.stageCalls()[SampleStage.B.ordinal()] - callsBefore);
        assertEquals(4, counting.points() - pointsBefore);

        // Through a real composition: every band/cells dispatch the fake saw is what landed in
        // the stage counters.
        final RecordingWaveSampler composed = new RecordingWaveSampler();
        final CountingSampler wrapper = new CountingSampler(composed);
        assertNotNull(LodSampling.sample(wrapper, false, LOD, ORIGIN_X, ORIGIN_Z));
        assertFalse(composed.sparseCalls.isEmpty(), "this geometry must cross the sparse threshold");
        // The stage bucket counts every call of the entry family, so the sparse chunks must add
        // exactly the packed calls on top of all per-point shapes (band 1x1s, cell 2x2s, anchor
        // sweeps) already attributed there.
        assertEquals(
            composed.overviewHeightCalls.size() + expectedStageCalls(composed, "bandSparse:"),
            wrapper.stageCalls()[SampleStage.H.ordinal()]
        );
        assertEquals(
            composed.surfaceBiomeCalls.size() + expectedStageCalls(composed, "cellsSparse:"),
            wrapper.stageCalls()[SampleStage.B.ordinal()]
        );
        assertEquals(expectedSparsePoints(composed), composed.sparsePoints());
    }

    @Test
    void outputsBitIdenticalWithAndWithoutSparse() {
        final RecordingWaveSampler sparse = new RecordingWaveSampler();
        final BaselineGrid batchedTile = LodSampling.sample(sparse, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(batchedTile);
        final RecordingWaveSampler perPoint = new RecordingWaveSampler();
        perPoint.refuseSparse = true;
        final BaselineGrid repairedTile = LodSampling.sample(perPoint, false, LOD, ORIGIN_X, ORIGIN_Z);
        assertNotNull(repairedTile);
        assertGridsBitEqual(repairedTile, batchedTile);

        final RecordingWaveSampler sparseWindow = new RecordingWaveSampler();
        final BaselineGrid batchedWindow = LodSampling.sampleOverworldWindow(
            sparseWindow, LOD, ORIGIN_X, ORIGIN_Z, 100, 100, 129, 129
        );
        assertNotNull(batchedWindow);
        final RecordingWaveSampler perPointWindow = new RecordingWaveSampler();
        perPointWindow.refuseSparse = true;
        final BaselineGrid repairedWindow = LodSampling.sampleOverworldWindow(
            perPointWindow, LOD, ORIGIN_X, ORIGIN_Z, 100, 100, 129, 129
        );
        assertNotNull(repairedWindow);
        assertGridsBitEqual(repairedWindow, batchedWindow);
    }

    /** 1x1 overviewHeights calls, i.e. exactly the band's per-point requests, in call order. */
    private static List<Long> bandPoints(final RecordingWaveSampler sampler) {
        return sampler.overviewHeightCalls.stream()
            .filter(c -> c[2] == 1 && c[3] == 1)
            .map(c -> pack(c[0], c[1]))
            .collect(Collectors.toList());
    }

    /** edge x edge surfaceBiomes calls, i.e. exactly the cells' per-point requests, in call order. */
    private static List<Long> cellPoints(final RecordingWaveSampler sampler) {
        return sampler.surfaceBiomeCalls.stream()
            .filter(c -> c[2] == 2 && c[3] == 2)
            .map(c -> pack(c[0], c[1]))
            .collect(Collectors.toList());
    }

    private static long expectedStageCalls(final RecordingWaveSampler sampler, final String prefix) {
        return sampler.sparseCalls.stream().filter(c -> c.startsWith(prefix)).count();
    }

    private static long expectedSparsePoints(final RecordingWaveSampler sampler) {
        long points = 0;
        for (final String call : sampler.sparseCalls) {
            if (call.startsWith("bandSparse:")) {
                points += Integer.parseInt(call.substring("bandSparse:".length()));
            } else {
                points += 4L * Integer.parseInt(call.substring("cellsSparse:".length()));
            }
        }
        return points;
    }

    private static long pack(final int blockX, final int blockZ) {
        return ((long) blockX << 32) | (blockZ & 0xFFFFFFFFL);
    }

    private static void assertGridsBitEqual(final BaselineGrid expected, final BaselineGrid actual) {
        assertArrayEquals(expected.biomeId, actual.biomeId);
        assertArrayEquals(expected.terrainY, actual.terrainY);
        assertArrayEquals(expected.fluidY, actual.fluidY);
        assertArrayEquals(expected.baseSurfaceY, actual.baseSurfaceY);
        assertArrayEquals(expected.surfaceFlags, actual.surfaceFlags);
    }

    private static boolean sparseCallsAbsent(final FlatDeepWaterSampler sampler) {
        return sampler.sparseBandRequests.isEmpty() && sampler.sparseCellRequests.isEmpty();
    }

    private static int bandCalls(final OneCrossingQuadSampler sampler) {
        return sampler.bandCalls;
    }

    private static boolean sparseCallsAbsent(final OneCrossingQuadSampler sampler) {
        return sampler.sparseBandRequests.isEmpty() && sampler.sparseCellRequests.isEmpty();
    }

    /**
     * Flat terrain below every interpolation threshold and one uniform land biome: every band
     * quad interpolates and every lattice cell copies, so the refinement collectors come back
     * empty and neither the per-point entries nor the sparse seam may be reached by them.
     */
    private static final class FlatDeepWaterSampler implements BaselineSampler {
        final List<String> calls = new ArrayList<>();
        final List<Long> sparseBandRequests = new ArrayList<>();
        final List<Long> sparseCellRequests = new ArrayList<>();

        @Override
        public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
            java.util.Arrays.fill(out, 0, w * h, 1);
            return true;
        }

        @Override
        public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return false;
        }

        @Override
        public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return false;
        }

        @Override
        public boolean interpolatableOverviewHeights() {
            return true;
        }

        @Override
        public boolean overviewHeightsCoarse(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
        ) {
            java.util.Arrays.fill(outTerrainY, 0, w * h, 40);
            return true;
        }

        @Override
        public boolean overviewHeights(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
        ) {
            calls.add("overviewHeights");
            java.util.Arrays.fill(outTerrainY, 0, w * h, 40);
            return true;
        }

        @Override
        public boolean overviewHeightsSparse(final int[] blockX, final int[] blockZ, final int n, final int[] outY) {
            for (int i = 0; i < n; i++) {
                sparseBandRequests.add(pack(blockX[i], blockZ[i]));
                outY[i] = 40;
            }
            return true;
        }

        @Override
        public boolean surfaceBiomes(
            final int blockX, final int blockZ, final int w, final int h, final int stride,
            final int[] terrainY, final int[] outBiomeIds
        ) {
            calls.add("surfaceBiomes");
            java.util.Arrays.fill(outBiomeIds, 0, w * h, 1);
            return true;
        }

        @Override
        public boolean surfaceBiomesSparse(
            final int[] cellX, final int[] cellZ, final int edge, final int stride,
            final int[] terrainYPacked, final int n, final int[] outBiomeIdsPacked
        ) {
            for (int i = 0; i < n; i++) {
                sparseCellRequests.add(pack(cellX[i], cellZ[i]));
            }
            java.util.Arrays.fill(outBiomeIdsPacked, 0, n * edge * edge, 1);
            return true;
        }
    }

    /**
     * Deep-water terrain with one tall anchor at absolute lattice cell (30,30): the 1x1 window at
     * pixel (60,60) reconstructs a 7x7 area whose only crossing quads are the four around that
     * anchor, so the band collector selects exactly its four odd pixels - a sub-threshold request
     * count the executor must carry per-point.
     */
    private static final class OneCrossingQuadSampler implements BaselineSampler {
        static final int ANCHOR_K = 30;
        /** LOD2 anchor lattice: one anchor every 2 pixels = every 8 blocks. */
        static final int ANCHOR_BLOCK_STRIDE = 8;

        final List<Long> sparseBandRequests = new ArrayList<>();
        final List<Long> sparseCellRequests = new ArrayList<>();
        int bandCalls;

        private static int tallHeight(final int blockX, final int blockZ) {
            final boolean tallAnchor = (blockX - ORIGIN_X) / ANCHOR_BLOCK_STRIDE == ANCHOR_K
                && (blockZ - ORIGIN_Z) / ANCHOR_BLOCK_STRIDE == ANCHOR_K;
            return tallAnchor ? 70 : 40;
        }

        @Override
        public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
            java.util.Arrays.fill(out, 0, w * h, 1);
            return true;
        }

        @Override
        public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return false;
        }

        @Override
        public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
            return false;
        }

        @Override
        public boolean interpolatableOverviewHeights() {
            return true;
        }

        @Override
        public boolean overviewHeightsCoarse(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
        ) {
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    outTerrainY[row * w + col] = tallHeight(
                        blockX + col * ANCHOR_BLOCK_STRIDE, blockZ + row * ANCHOR_BLOCK_STRIDE
                    );
                }
            }
            return true;
        }

        @Override
        public boolean overviewHeights(
            final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
        ) {
            bandCalls++;
            for (int row = 0; row < h; row++) {
                for (int col = 0; col < w; col++) {
                    outTerrainY[row * w + col] = tallHeight(blockX + col * stride, blockZ + row * stride);
                }
            }
            return true;
        }

        @Override
        public boolean overviewHeightsSparse(final int[] blockX, final int[] blockZ, final int n, final int[] outY) {
            for (int i = 0; i < n; i++) {
                sparseBandRequests.add(pack(blockX[i], blockZ[i]));
                outY[i] = tallHeight(blockX[i], blockZ[i]);
            }
            return true;
        }

        @Override
        public boolean surfaceBiomes(
            final int blockX, final int blockZ, final int w, final int h, final int stride,
            final int[] terrainY, final int[] outBiomeIds
        ) {
            java.util.Arrays.fill(outBiomeIds, 0, w * h, 1);
            return true;
        }

        @Override
        public boolean surfaceBiomesSparse(
            final int[] cellX, final int[] cellZ, final int edge, final int stride,
            final int[] terrainYPacked, final int n, final int[] outBiomeIdsPacked
        ) {
            for (int i = 0; i < n; i++) {
                sparseCellRequests.add(pack(cellX[i], cellZ[i]));
            }
            java.util.Arrays.fill(outBiomeIdsPacked, 0, n * edge * edge, 1);
            return true;
        }
    }
}
