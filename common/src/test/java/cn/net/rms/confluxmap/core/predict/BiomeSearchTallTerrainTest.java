package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for the coarse scan's blind spot under tall terrain: the scale-16
 * Overworld grid samples at quart y=0, so a stony-peaks column with surface y=181 reads
 * as an underground biome there. The search used to hide such occurrences whenever the
 * scan found any hit elsewhere in the window, because the surface fallback only ran on a
 * global miss. Seed 7682567111588117319 (MC 26.1.2) has stony peaks at (-826, 3289)
 * whose entire massif reads deep-dark at y=0; with the default 100k search radius the
 * nearest reported candidate must stay on that massif, not jump to the 24k-distant
 * low-terrain peaks the y=0 slice can see.
 */
class BiomeSearchTallTerrainTest {
    private static final long SEED = 7682567111588117319L;

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
    }

    @Test
    void tallTerrainSurfaceBiomesStayVisibleToTheCoarseScan() {
        final OptionalInt mc = McVersions.toCubiomes("26.1.2");
        assertTrue(mc.isPresent(), "McVersions must know \"26.1.2\"");
        final NativeBaselineSampler sampler = new NativeBaselineSampler(
            mc.getAsInt(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        final OptionalInt stonyPeaks = CubiomesBiomeIds.idForName("stony_peaks");
        assertTrue(stonyPeaks.isPresent(), "stony_peaks must have a cubiomes id");

        final List<BiomeCandidateSearch.Candidate> candidates = BiomeSearchService.samplePredicted(
            sampler, stonyPeaks.getAsInt(), 0, 0, 100_000, 30, SearchOrder.NEAREST
        );
        assertTrue(!candidates.isEmpty(), "seed must hold stony peaks inside the radius");
        final BiomeCandidateSearch.Candidate nearest = candidates.get(0);
        assertTrue(Math.hypot(nearest.blockX(), nearest.blockZ()) < 5_000,
            "nearest stony peaks sits on the massif ~2.8k from origin, got "
                + nearest.blockX() + "," + nearest.blockZ());
    }
}
