package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Batch parity for native pre-1.18 overview sampling.
 *
 * cubiomes ends the pre-1.18 biome stack in a Voronoi zoom whose dense query
 * corrupts boundary cells (ocean-biome patches on tile edges) for origins at
 * 2 or 3 mod 4. The shim queries with a margin and reads the interior; this
 * test pins the contract that margin must buy: every batch cell, edges
 * included, matches an isolated single-column query at the same world
 * position, across all four origin alignments mod 4 and both LOD strides that
 * route through the scale-1 path.
 *
 * The overview heights themselves are now a world-aligned quart macro lattice
 * (as 1.18+ has been since the modern path adopted one), so a batch cell is
 * the bilinear interpolation of its cell corners while a single query resolves
 * the exact point. That interpolation drift is bounded by one block; the
 * biome corruption this test guards against displaces the surface by two or
 * more, so a wrong boundary biome still fails loudly.
 */
class PredictionNativeBatchEdgeParityTest {
    private static final long SEED = 5900392137368868154L;

    private NativeBaselineSampler newSampler() {
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(SEED, McVersions.toCubiomes("1.17").orElseThrow());
        return new NativeBaselineSampler(
            state.mcVersion(), state.seed(),
            PredictionDimensions.nativeDim(DimensionId.OVERWORLD),
            state.cubiomesFlags(DimensionId.OVERWORLD)
        );
    }

    @Test
    void tileBatchMatchesSingleQueriesAtBoundaryCells() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        // The production LOD0 query shape for the tile west of X=-1024: 258x258 with the
        // one-block margin, origin -1025 is 3 mod 4 - the alignment the corruption hits.
        assertBatchParity(newSampler(), -1025, -1, 258, 258, 1);
    }

    @Test
    void smallBatchesMatchSingleQueriesAcrossAllAlignments() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final NativeBaselineSampler sampler = newSampler();
        assertBatchParity(sampler, -1028, 2, 33, 33, 1);
        assertBatchParity(sampler, -1027, 1, 33, 33, 1);
        assertBatchParity(sampler, -1026, 0, 33, 33, 1);
        assertBatchParity(sampler, -1025, 3, 33, 33, 1);
    }

    @Test
    void strideTwoBatchMatchesSingleQueries() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        assertBatchParity(newSampler(), -1025, 3, 40, 40, 2);
    }

    private void assertBatchParity(
        NativeBaselineSampler sampler, int originX, int originZ, int w, int h, int stride
    ) {
        final int[] batch = new int[w * h];
        assertTrue(sampler.overviewHeights(originX, originZ, w, h, stride, batch),
            "batch query failed");

        final StringBuilder mismatches = new StringBuilder();
        int mismatchCount = 0;
        final int[] single = new int[1];
        for (int j = 0; j < h; j++) {
            final boolean rowEdge = j <= 1 || j >= h - 3;
            if (!rowEdge && (j & 7) != 0) {
                continue;
            }
            for (int i = 0; i < w; i++) {
                final boolean columnEdge = i <= 1 || i >= w - 3;
                if (!rowEdge && !columnEdge && (i & 7) != 0) {
                    continue;
                }
                final int worldX = originX + i * stride;
                final int worldZ = originZ + j * stride;
                assertTrue(sampler.overviewHeights(worldX, worldZ, 1, 1, 1, single),
                    "single query failed at " + worldX + "," + worldZ);
                final int drift = Math.abs(single[0] - batch[j * w + i]);
                if (drift > 1 && mismatchCount < 20) {
                    mismatches.append(String.format(
                        "%n  batch cell (%d,%d) world (%d,%d): batch=%d single=%d",
                        i, j, worldX, worldZ, batch[j * w + i], single[0]));
                }
                if (drift > 1) {
                    mismatchCount++;
                }
            }
        }
        assertEquals(0, mismatchCount,
            "batch cells diverged from single-column queries by more than one"
                + " block:" + mismatches);
    }
}
