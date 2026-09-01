package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.store.ColumnStore;
import java.util.List;
import java.util.OptionalInt;
import org.junit.jupiter.api.Test;

class BiomeSearchServiceTest {
    @Test
    void predictedSearchUsesSurfaceBiomesWhenTheSamplerProvidesThem() {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale,
                final int x,
                final int z,
                final int width,
                final int height,
                final int[] out
            ) {
                java.util.Arrays.fill(out, 1);
                return true;
            }

            @Override
            public boolean heights(
                final int x4,
                final int z4,
                final int width,
                final int height,
                final int[] outY
            ) {
                return false;
            }

            @Override
            public boolean overviewHeights(
                final int blockX,
                final int blockZ,
                final int width,
                final int height,
                final int stride,
                final int[] outTerrainY
            ) {
                java.util.Arrays.fill(outTerrainY, 64);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX,
                final int blockZ,
                final int width,
                final int height,
                final int stride,
                final int[] terrainY,
                final int[] outBiomeIds
            ) {
                java.util.Arrays.fill(outBiomeIds, 7);
                return true;
            }

            @Override
            public boolean endHeights(
                final int x4,
                final int z4,
                final int width,
                final int height,
                final int[] outY
            ) {
                return false;
            }
        };

        assertEquals(BiomeCandidateSearch.Source.PREDICTED,
            BiomeSearchService.samplePredicted(sampler, 7, 0, 0, 64, 1)
                .get(0).source());
    }

    @Test
    void searchFallsBackToPredictionWhenTheMapHasNoRecordedMatch() {
        final BaselineSampler sampler = constantSampler(7);

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeSearchService.search(
            new ColumnStore(),
            "minecraft:river",
            OptionalInt.of(7),
            sampler,
            0,
            0,
            64,
            1
        ));
    }

    @Test
    void predictedSearchReturnsMatchingSurfaceSamplesInDistanceOrder() {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale,
                final int x,
                final int z,
                final int width,
                final int height,
                final int[] out
            ) {
                for (int row = 0; row < height; row++) {
                    for (int column = 0; column < width; column++) {
                        final int blockX = (x + column) * scale;
                        final int blockZ = (z + row) * scale;
                        out[row * width + column] =
                            blockZ == 0 && (blockX == 0 || blockX == 200) ? 7 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean heights(
                final int x4,
                final int z4,
                final int width,
                final int height,
                final int[] outY
            ) {
                return false;
            }

            @Override
            public boolean endHeights(
                final int x4,
                final int z4,
                final int width,
                final int height,
                final int[] outY
            ) {
                return false;
            }
        };

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, 0, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                200, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeSearchService.samplePredicted(sampler, 7, 0, 0, 512, 10));
    }

    private static BaselineSampler constantSampler(final int biomeId) {
        return new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale,
                final int x,
                final int z,
                final int width,
                final int height,
                final int[] out
            ) {
                java.util.Arrays.fill(out, biomeId);
                return true;
            }

            @Override
            public boolean heights(
                final int x4,
                final int z4,
                final int width,
                final int height,
                final int[] outY
            ) {
                return false;
            }

            @Override
            public boolean endHeights(
                final int x4,
                final int z4,
                final int width,
                final int height,
                final int[] outY
            ) {
                return false;
            }
        };
    }
}
