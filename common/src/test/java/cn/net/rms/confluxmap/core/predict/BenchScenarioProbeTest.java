package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * One-off origin scout for the {@code coast}/{@code inland}/{@code mixed} scenario presets of
 * {@link WaveBenchmarkOptions}: sweeps candidate tile origins for the fixed benchmark seed and
 * prints water fraction + distinct-biome count per origin, so preset picks are measured rather
 * than guessed. Tagged {@code benchmark} so it never runs in {@code :common:test}; run it with
 * {@code ./gradlew :common:benchmark --tests '*BenchScenarioProbe*'} when re-picking presets.
 */
@Tag("benchmark")
class BenchScenarioProbeTest {
    private static final long SEED = 146008555L;
    private static final String MC_VERSION = "1.21.4";
    /** One tile of the benchmark's lod-4 lattice: 128 blocks per tile. */
    private static final int TILE_BLOCKS = 128;
    private static final int WINDOW = 128;
    private static final int STRIDE = 4;
    private static final int CANDIDATE_SPACING = 16;

    @Test
    void printWaterFractionAndBiomeVarietyPerCandidateOrigin() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable");
        final int mcVersion = McVersions.toCubiomes(MC_VERSION).orElseThrow();
        final int[] terrainY = new int[WINDOW * WINDOW];
        final int[] biomeIds = new int[WINDOW * WINDOW];
        final Map<String, double[]> hits = new LinkedHashMap<>();

        final int span = 24 * CANDIDATE_SPACING;
        for (int tileX = -span; tileX <= span; tileX += CANDIDATE_SPACING) {
            for (int tileZ = -span; tileZ <= span; tileZ += CANDIDATE_SPACING) {
                final NativeBaselineSampler sampler =
                    new NativeBaselineSampler(mcVersion, SEED, PredictionDimensions.OVERWORLD, 0);
                final int blockX = tileX * TILE_BLOCKS;
                final int blockZ = tileZ * TILE_BLOCKS;
                if (!sampler.overviewHeights(blockX, blockZ, WINDOW, WINDOW, STRIDE, terrainY)) {
                    continue;
                }
                if (!sampler.surfaceBiomes(blockX, blockZ, WINDOW, WINDOW, STRIDE, terrainY, biomeIds)) {
                    continue;
                }
                int water = 0;
                final java.util.HashSet<Integer> distinct = new java.util.HashSet<>();
                for (int i = 0; i < biomeIds.length; i++) {
                    distinct.add(biomeIds[i]);
                    if (terrainY[i] <= cn.net.rms.confluxmap.core.predict.BaselineDeriver.WATER_LEVEL) {
                        water++;
                    }
                }
                hits.put(tileX + "," + tileZ, new double[] {
                    (double) water / biomeIds.length, distinct.size()
                });
            }
        }
        System.out.println("[bench-probe] tileX,tileZ waterFraction distinctBiomes (seed " + SEED + ")");
        hits.forEach((origin, stats) -> System.out.printf(
            java.util.Locale.ROOT, "[bench-probe] %s %.3f %d%n", origin, stats[0], (int) stats[1]
        ));
    }
}
