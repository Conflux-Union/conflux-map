package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Per-stage wall-clock breakdown of one predicted tile's composition on the
 * pre-1.18 pipeline, so the post-optimization bottleneck is measured rather
 * than guessed. Opt-in: {@code :common:benchmark}.
 *
 * <p>Stages, in the order the production composer runs them at LOD0:
 * overview window (258x258), surface-biome pass (memo hit), the exact-anchor
 * pair inside the residual pass (overview 34x34 stride 8 + exact columns
 * 34x34 stride 8), the whole {@link LodSampling#sample} (adds band fixes and
 * bilinear residual expansion), the water/land derivation, and the canopy
 * styling. LOD2/LOD4 sample totals show the coarse-LOD floor.
 */
@Tag("benchmark")
class PredictionLegacyStageTimingBenchmarkTest {
    private static final long SEED = 1509797269253326924L;
    private static final int TILES = 24;
    private static final int WARMUP = 4;

    @Test
    void stageBreakdown() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final int mc = McVersions.toCubiomes("1.17").orElseThrow();
        final NativeBaselineSampler sampler = new NativeBaselineSampler(
            mc, SEED, PredictionDimensions.OVERWORLD, 0
        );

        // Warm every native context and JIT path.
        for (int i = 0; i < WARMUP; i++) {
            assertNotNull(LodSampling.sample(sampler, false, 0, i * 512, 0));
            assertNotNull(LodSampling.sample(sampler, false, 2, i * 2048, 0));
            assertNotNull(LodSampling.sample(sampler, false, 4, i * 8192, 0));
        }

        final int size = BaselineGrid.SIZE;
        final int[] heights = new int[size * size];
        final int[] biomes = new int[size * size];
        final int[] terrainY = new int[size * size];
        final int[] fluidY = new int[size * size];
        final int[] surfaceY = new int[size * size];
        final int[] flags = new int[size * size];

        // ---- LOD0 per-stage timings ------------------------------------
        long tOverview = 0, tSurfaceBiomes = 0, tAnchorOverview = 0, tAnchorExact = 0;
        for (int t = 0; t < TILES + WARMUP; t++) {
            final int ox = -65536 + t * 256;
            long a = System.nanoTime();
            assertTrue(sampler.overviewHeights(ox, 0, size, size, 1, heights));
            long b = System.nanoTime();
            assertTrue(sampler.surfaceBiomes(ox, 0, size, size, 1, heights, biomes));
            long c = System.nanoTime();
            assertTrue(sampler.overviewHeights(ox, 0, 34, 34, 8, heights));
            long d = System.nanoTime();
            assertTrue(sampler.surfaceColumns(ox, 0, 34, 34, 8, terrainY, fluidY, surfaceY, flags));
            long e = System.nanoTime();
            if (t >= WARMUP) {
                tOverview += b - a;
                tSurfaceBiomes += c - b;
                tAnchorOverview += d - c;
                tAnchorExact += e - d;
            }
        }
        final int timed = TILES;
        report("LOD0 overview window 258x258 s1", tOverview / timed);
        report("LOD0 surfaceBiomes 258x258 (memo)", tSurfaceBiomes / timed);
        report("LOD0 anchor overview 34x34 s8", tAnchorOverview / timed);
        report("LOD0 anchor exact columns 34x34 s8", tAnchorExact / timed);

        // ---- whole-pipeline totals per LOD ------------------------------
        for (final int lod : new int[] {0, 1, 2, 4}) {
            long t = 0;
            BaselineGrid last = null;
            for (int t2 = 0; t2 < TILES + WARMUP; t2++) {
                final int ox = -65536 + t2 * (256 << lod);
                final long a = System.nanoTime();
                final BaselineGrid grid = LodSampling.sample(sampler, false, lod, ox, 0);
                final long b = System.nanoTime();
                if (t2 >= WARMUP) {
                    t += b - a;
                }
                last = grid;
            }
            assertNotNull(last);
            report("LOD" + lod + " LodSampling.sample total", t / timed);
        }

        // ---- post-derivation stages -------------------------------------
        final BaselineGrid grid0 = LodSampling.sample(sampler, false, 0, 0, 0);
        assertNotNull(grid0);
        long tDerive = 0, tCanopy = 0;
        for (int t = 0; t < TILES + WARMUP; t++) {
            final int ox = -65536 + t * 256;
            final BaselineGrid grid = LodSampling.sample(sampler, false, 0, ox, 0);
            final long a = System.nanoTime();
            final DerivedGrid derived = BaselineDeriver.derive(grid);
            final long b = System.nanoTime();
            CanopyStylizer.apply(derived, grid, SEED, 0, ox, 0);
            final long c = System.nanoTime();
            if (t >= WARMUP) {
                tDerive += b - a;
                tCanopy += c - b;
            }
        }
        report("LOD0 BaselineDeriver.derive", tDerive / timed);
        report("LOD0 CanopyStylizer.apply", tCanopy / timed);
    }

    private static void report(final String label, final long nanos) {
        System.out.printf("STAGE %-40s %8.3f ms/tile%n", label, nanos / 1e6);
    }
}
