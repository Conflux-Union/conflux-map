package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Arrays;
import java.util.List;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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

    @Test
    void farthestSearchOrdersPredictedMatchesByDescendingDistance() {
        final BaselineSampler sampler = twoPointSampler();

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                200, 0, BiomeCandidateSearch.Source.PREDICTED
            ),
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
            512,
            10,
            SearchOrder.FARTHEST
        ));
    }

    private static BaselineSampler twoPointSampler() {
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
    }

    @Test
    void farthestSearchSamplesPredictionEvenWhenExploredAloneFillsTheLimit() {
        final SessionGuard.Session session = new SessionGuard.Session(
            7L, WorldIdentity.singleplayer("farthest-explored-world"), DimensionId.OVERWORLD
        );
        final MapWorld world = new MapWorld(session);
        world.put(MapLayer.SURFACE, riverColumns(session.token(), 0, 0), SampleSource.REAL_LIVE);
        world.put(MapLayer.SURFACE, riverColumns(session.token(), 9, 0), SampleSource.REAL_LIVE);

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                9_000, 0, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                159, 15, BiomeCandidateSearch.Source.EXPLORED
            )
        ), BiomeSearchService.search(
            world.store(MapLayer.SURFACE),
            "minecraft:river",
            OptionalInt.of(7),
            pointSampler(7, 9_000, 0),
            0,
            0,
            10_000,
            2,
            SearchOrder.FARTHEST
        ));
    }

    private static ChunkSnapshot riverColumns(final long sessionToken, final int chunkX, final int chunkZ) {
        final String[] biomes = new String[ChunkSnapshot.COLUMNS];
        Arrays.fill(biomes, "minecraft:river");
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        return new ChunkSnapshot(
            chunkX,
            chunkZ,
            sessionToken,
            surfaceY,
            biomes,
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static BaselineSampler pointSampler(final int biomeId, final int matchX, final int matchZ) {
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
                for (int row = 0; row < height; row++) {
                    for (int column = 0; column < width; column++) {
                        final int blockX = (x + column) * scale;
                        final int blockZ = (z + row) * scale;
                        out[row * width + column] =
                            blockX == matchX && blockZ == matchZ ? biomeId : 1;
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
    }

    @Test
    void coarseScale16HitIsRefinedToSurfaceGridCoordinates() {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomesSparseStrided(
                final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
            ) {
                if (scale != 16) {
                    return false;
                }
                for (int row = 0; row < h; row++) {
                    for (int column = 0; column < w; column++) {
                        final int blockX = (x + column * stride) * 16;
                        final int blockZ = (z + row * stride) * 16;
                        out[row * w + column] = blockX == 0 && blockZ == 0 ? 7 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean overviewHeights(
                final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outY
            ) {
                java.util.Arrays.fill(outY, 64);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] terrainY, final int[] outBiomeIds
            ) {
                for (int row = 0; row < h; row++) {
                    for (int column = 0; column < w; column++) {
                        final int sampleX = blockX + column * stride;
                        final int sampleZ = blockZ + row * stride;
                        outBiomeIds[row * w + column] = sampleX == 8 && sampleZ == 8 ? 7 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h, final int[] out
            ) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                8, 8, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeSearchService.samplePredicted(sampler, 7, 0, 0, 64, 1));
    }

    @Test
    void coarseHitWithoutSurfaceConfirmationKeepsCoarseCoordinates() {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomesSparseStrided(
                final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
            ) {
                if (scale != 16) {
                    return false;
                }
                for (int row = 0; row < h; row++) {
                    for (int column = 0; column < w; column++) {
                        final int blockX = (x + column * stride) * 16;
                        final int blockZ = (z + row * stride) * 16;
                        out[row * w + column] = blockX == 0 && blockZ == 0 ? 7 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean overviewHeights(
                final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outY
            ) {
                java.util.Arrays.fill(outY, 64);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] terrainY, final int[] outBiomeIds
            ) {
                java.util.Arrays.fill(outBiomeIds, 1);
                return true;
            }

            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h, final int[] out
            ) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeSearchService.samplePredicted(sampler, 7, 0, 0, 64, 1));
    }

    @Test
    void sparseScaleFourGridIsReturnedDirectlyWithoutSurfaceRefinement() {
        final boolean[] surfaceTouched = {false};
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomesSparseStrided(
                final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
            ) {
                if (scale != 4) {
                    return false;
                }
                for (int row = 0; row < h; row++) {
                    for (int column = 0; column < w; column++) {
                        final int blockX = (x + column * stride) * 4;
                        final int blockZ = (z + row * stride) * 4;
                        out[row * w + column] = blockX == 64 && blockZ == 0 ? 7 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean overviewHeights(
                final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outY
            ) {
                surfaceTouched[0] = true;
                return false;
            }

            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h, final int[] out
            ) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                64, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeSearchService.samplePredicted(sampler, 7, 0, 0, 64, 1));
        assertFalse(surfaceTouched[0], "scale-4 sparse hits must not trigger surface refinement");
    }

    @Test
    void coarseMissFallsBackToTheSurfacePath() {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomesSparseStrided(
                final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
            ) {
                java.util.Arrays.fill(out, 1);
                return true;
            }

            @Override
            public boolean overviewHeights(
                final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outY
            ) {
                java.util.Arrays.fill(outY, 64);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] terrainY, final int[] outBiomeIds
            ) {
                for (int row = 0; row < h; row++) {
                    for (int column = 0; column < w; column++) {
                        final int sampleX = blockX + column * stride;
                        outBiomeIds[row * w + column] = sampleX == 0 ? 7 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h, final int[] out
            ) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeSearchService.samplePredicted(sampler, 7, 0, 0, 64, 1));
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

    @Test
    void ringScheduleGrowsFromTheFirstRingToTheRequestedRadius() {
        assertArrayEquals(new int[] {5_000, 15_000, 45_000, 100_000},
            BiomeSearchService.ringRadii(100_000));
        assertArrayEquals(new int[] {5_000}, BiomeSearchService.ringRadii(5_000));
        assertArrayEquals(new int[] {2_000}, BiomeSearchService.ringRadii(2_000));
        assertArrayEquals(new int[] {5_000, 12_499}, BiomeSearchService.ringRadii(12_499));
    }

    @Test
    void nearestSearchStopsAtTheInnermostRingThatFillsTheLimit() {
        final Set<Integer> coarseStrides = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());
        final BaselineSampler sampler = gridHitsSampler((blockX, blockZ) ->
            Math.abs(blockX) <= 2_000 && Math.abs(blockZ) <= 2_000, coarseStrides);

        final List<BiomeCandidateSearch.Candidate> candidates = BiomeSearchService.samplePredicted(
            sampler, 7, 0, 0, 100_000, 5
        );

        assertFalse(candidates.isEmpty(), "the fake layout must produce candidates");
        for (final BiomeCandidateSearch.Candidate candidate : candidates) {
            assertTrue(Math.abs(candidate.blockX()) <= 2_100 && Math.abs(candidate.blockZ()) <= 2_100,
                "every hit lives in the inner band, got " + candidate.blockX() + "," + candidate.blockZ());
        }
        assertEquals(Set.of(2), coarseStrides,
            "only the innermost 5k ring (2-cell coarse stride) may be scanned");
    }

    @Test
    void farthestSearchScansTheOutermostRingFirstAndStopsThere() {
        final Set<Integer> coarseStrides = java.util.Collections.synchronizedSet(
            new java.util.HashSet<>());
        final BaselineSampler sampler = gridHitsSampler((blockX, blockZ) ->
            blockX >= 60_000 && blockX <= 99_000 && Math.abs(blockZ) <= 2_000, coarseStrides);

        final List<BiomeCandidateSearch.Candidate> candidates = BiomeSearchService.samplePredicted(
            sampler, 7, 0, 0, 100_000, 5, SearchOrder.FARTHEST
        );

        assertFalse(candidates.isEmpty(), "the fake layout must produce candidates");
        for (final BiomeCandidateSearch.Candidate candidate : candidates) {
            assertTrue(candidate.blockX() >= 60_000,
                "every hit lives in the outer band, got " + candidate.blockX() + "," + candidate.blockZ());
        }
        assertEquals(Set.of(25), coarseStrides,
            "only the outermost 100k ring (25-cell coarse stride) may be scanned");
    }

    private interface HitRule {
        boolean hit(int blockX, int blockZ);
    }

    /** Scale-16 scan fake whose hits sit on the 400-block lattice, so every ring grid sees them. */
    private static BaselineSampler gridHitsSampler(final HitRule rule, final Set<Integer> strides) {
        return new BaselineSampler() {
            @Override
            public boolean biomesScanStrided(
                final int scale, final int x, final int z, final int w, final int h,
                final int stride, final int targetBiomeId, final int[] out
            ) {
                if (scale != 16) {
                    return false;
                }
                strides.add(stride);
                for (int row = 0; row < h; row++) {
                    for (int column = 0; column < w; column++) {
                        final int blockX = (x + column * stride) * 16;
                        final int blockZ = (z + row * stride) * 16;
                        // Checkerboard over the 400-block lattice: hits are never
                        // grid-adjacent, so each is its own connected component in every
                        // ring grid and all pairs clear the 128-block separation rule.
                        out[row * w + column] =
                            blockX % 400 == 0 && blockZ % 400 == 0
                                && (blockX / 400 + blockZ / 400) % 2 == 0
                                && rule.hit(blockX, blockZ)
                                ? targetBiomeId : -1;
                    }
                }
                return true;
            }

            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h, final int[] out
            ) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };
    }

    @Test
    void parallelScanMatchesSingleThreadedResultsAndCoversEveryRowExactlyOnce() throws InterruptedException {
        final Set<Long> scannedRows = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomesScanStrided(
                final int scale, final int x, final int z, final int w, final int h,
                final int stride, final int targetBiomeId, final int[] out
            ) {
                if (scale != 16) {
                    return false;
                }
                for (int row = 0; row < h; row++) {
                    if (!scannedRows.add((long) z + row * stride)) {
                        throw new IllegalStateException("row scanned twice: " + (z + row * stride));
                    }
                    for (int column = 0; column < w; column++) {
                        final int blockX = (x + column * stride) * 16;
                        final int blockZ = (z + row * stride) * 16;
                        final boolean hit = blockX >= -2048 && blockX <= 2048 && blockZ >= -512 && blockZ <= 512;
                        out[row * w + column] = hit && (blockX + blockZ) % 400 == 0 ? targetBiomeId : -1;
                    }
                }
                return true;
            }

            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h, final int[] out
            ) {
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        final List<BiomeCandidateSearch.Candidate> singleThreaded = BiomeSearchService.samplePredicted(
            sampler, 7, 0, 0, 100_000, 10
        );
        assertFalse(singleThreaded.isEmpty(), "the fake layout must produce candidates");
        final ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            scannedRows.clear();
            final List<BiomeCandidateSearch.Candidate> parallel = BiomeSearchService.samplePredicted(
                sampler, 7, 0, 0, 100_000, 10, SearchOrder.NEAREST, pool, 3
            );
            assertEquals(singleThreaded, parallel, "row-parallel scanning must not change the candidates");
            assertEquals(
                501, scannedRows.size(),
                "the innermost 5k ring fills the limit, so exactly its 501 grid rows must be scanned once each"
            );
        } finally {
            pool.shutdownNow();
        }
    }
}
