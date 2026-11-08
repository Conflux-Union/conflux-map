package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Full-scale production-pipeline validation for pre-1.18 prediction, opt-in via
 * {@code :common:benchmark} (the default {@code test} task excludes the benchmark tag).
 *
 * <p>Where {@code native/test_legacy_fullscale.c} compares the raw native overview against
 * vanilla's exact generation, this harness runs the real {@link LodSampling} pipeline - exact
 * residual correction, fluid resolution and all - over 4096x4096 block regions and scores the
 * map-visible output block by block against vanilla columns: height, water/land kind, fluid
 * depth buckets and biome id. Two seeds x two regions keeps a run around ten minutes; the
 * native-level sweep covers the full 10-seed x 5-region matrix.
 */
@Tag("benchmark")
class PredictionLegacyFullScalePipelineBenchmarkTest {
    private static final long[] SEEDS = {
        1509797269253326924L, 3515529913517636733L,
    };
    private static final int[][] REGIONS = {
        {0, 0}, {5000000, 5000000},
    };
    private static final int REGION = 4096;
    private static final int TILES = REGION / BaselineGrid.PIXELS;

    @Test
    void productionPipelineTracksVanillaAcrossRegionsAndSeeds() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final int mc = McVersions.toCubiomes("1.17").orElseThrow();

        final Metrics total = new Metrics();
        for (final long seed : SEEDS) {
            for (final int[] region : REGIONS) {
                final NativeBaselineSampler sampler = new NativeBaselineSampler(
                    mc, seed, PredictionDimensions.OVERWORLD, 0
                );
                final Metrics unit = new Metrics();
                for (int tj = 0; tj < TILES; tj++) {
                    for (int ti = 0; ti < TILES; ti++) {
                        final int tileX = region[0] + ti * BaselineGrid.PIXELS;
                        final int tileZ = region[1] + tj * BaselineGrid.PIXELS;
                        accumulateTile(sampler, tileX, tileZ, unit);
                    }
                }
                System.out.printf(
                    "UNIT seed=%d region=(%d,%d) cells=%d mae=%.4f p95=%d max=%d"
                        + " flips=%.4f%% kind=%.4f%% bucket=%.4f%% biome=%.4f%%%n",
                    seed, region[0], region[1], unit.cells, unit.mae(), unit.p95(),
                    unit.max, unit.flips(), unit.kindMismatch(), unit.bucketMismatch(),
                    unit.biomeMismatch());
                assertTrue(unit.mae() <= 8.0, "post-correction MAE broke the contract: " + unit.mae());
                total.merge(unit);
            }
        }
        System.out.printf(
            "TOTAL cells=%d mae=%.4f p95=%d max=%d flips=%.4f%% kind=%.4f%%"
                + " bucket=%.4f%% biome=%.4f%%%n",
            total.cells, total.mae(), total.p95(), total.max, total.flips(),
            total.kindMismatch(), total.bucketMismatch(), total.biomeMismatch());
        assertTrue(total.kindMismatch() <= 15.0,
            "water/land kind agreement broke the contract: " + (100 - total.kindMismatch()));
    }

    private static void accumulateTile(
        final NativeBaselineSampler sampler, final int tileX, final int tileZ, final Metrics out
    ) {
        final BaselineGrid grid = LodSampling.sample(sampler, false, 0, tileX, tileZ);
        assertNotNull(grid, "tile sampling failed at " + tileX + "," + tileZ);

        final int size = BaselineGrid.SIZE;
        final int cells = size * size;
        final int[] exactY = new int[cells];
        final int[] exactFluid = new int[cells];
        final int[] exactSurface = new int[cells];
        final int[] exactFlags = new int[cells];
        final int[] exactBiomes = new int[cells];
        assertTrue(sampler.surfaceColumns(
            tileX - 1, tileZ - 1, size, size, 1, exactY, exactFluid, exactSurface, exactFlags));
        assertTrue(sampler.biomes(1, tileX - 1, tileZ - 1, size, size, exactBiomes));

        for (int lz = 0; lz < BaselineGrid.PIXELS; lz++) {
            for (int lx = 0; lx < BaselineGrid.PIXELS; lx++) {
                final int gi = BaselineGrid.index(lx, lz);
                final int ei = (lz + 1) * size + (lx + 1);
                final int predicted = grid.terrainY[gi];
                final int exact = exactY[ei];
                final int d = Math.abs(predicted - exact);
                out.cells++;
                out.sumAbsDiff += d;
                out.hist[Math.min(d, out.hist.length - 1)]++;
                if (d > out.max) {
                    out.max = d;
                }
                if (((predicted < 62) != (exact < 62))) {
                    out.waterFlips++;
                }
                if ((grid.surfaceFlags[gi] & BaselineGrid.SURFACE_FLUID)
                    != (exactFlags[ei] & BaselineGrid.SURFACE_FLUID)) {
                    out.kindMismatches++;
                }
                if (Bucket.of(predicted) != Bucket.of(exact)) {
                    out.bucketMismatches++;
                }
                if (grid.biomeId[gi] != exactBiomes[ei]) {
                    out.biomeMismatches++;
                }
            }
        }
    }

    private enum Bucket {
        ZERO, SHALLOW, MID, DEEP;

        static Bucket of(final int terrainY) {
            final int depth = terrainY < 62 ? 62 - terrainY : 0;
            if (depth == 0) {
                return ZERO;
            }
            if (depth <= 3) {
                return SHALLOW;
            }
            if (depth <= 9) {
                return MID;
            }
            return DEEP;
        }
    }

    private static final class Metrics {
        long cells;
        double sumAbsDiff;
        long max;
        long waterFlips;
        long kindMismatches;
        long bucketMismatches;
        long biomeMismatches;
        final long[] hist = new long[256];

        double mae() {
            return cells == 0 ? 0 : sumAbsDiff / cells;
        }

        double flips() {
            return cells == 0 ? 0 : 100.0 * waterFlips / cells;
        }

        double kindMismatch() {
            return cells == 0 ? 0 : 100.0 * kindMismatches / cells;
        }

        double bucketMismatch() {
            return cells == 0 ? 0 : 100.0 * bucketMismatches / cells;
        }

        double biomeMismatch() {
            return cells == 0 ? 0 : 100.0 * biomeMismatches / cells;
        }

        int p95() {
            long seen = 0;
            final long target = (long) (cells * 0.95);
            for (int d = 0; d < hist.length; d++) {
                seen += hist[d];
                if (seen >= target) {
                    return d;
                }
            }
            return hist.length - 1;
        }

        void merge(final Metrics other) {
            cells += other.cells;
            sumAbsDiff += other.sumAbsDiff;
            max = Math.max(max, other.max);
            waterFlips += other.waterFlips;
            kindMismatches += other.kindMismatches;
            bucketMismatches += other.bucketMismatches;
            biomeMismatches += other.biomeMismatches;
            for (int d = 0; d < hist.length; d++) {
                hist[d] += other.hist[d];
            }
        }
    }
}
