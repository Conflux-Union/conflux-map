package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class BiomeCandidateSearchTest {
    @Test
    void continuousPredictedBiomeBecomesOneLocation() {
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeCandidateSearch.fromGrid(
            7,
            -4,
            -4,
            3,
            3,
            4,
            new int[] {7, 7, 7, 7, 7, 7, 7, 7, 7}
        ));
    }

    @Test
    void exploredSearchReadsBiomeIdentifiersFromCapturedMapColumns() {
        final SessionGuard.Session session = new SessionGuard.Session(
            7L, WorldIdentity.singleplayer("world"), DimensionId.OVERWORLD
        );
        final MapWorld world = new MapWorld(session);
        final String[] biomes = new String[ChunkSnapshot.COLUMNS];
        Arrays.fill(biomes, "minecraft:plains");
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        world.put(
            MapLayer.SURFACE,
            new ChunkSnapshot(
                2, 0, session.token(), surfaceY, biomes,
                new byte[ChunkSnapshot.COLUMNS],
                new int[ChunkSnapshot.COLUMNS],
                new int[ChunkSnapshot.COLUMNS],
                new int[ChunkSnapshot.COLUMNS],
                kind,
                new byte[ChunkSnapshot.COLUMNS]
            ),
            SampleSource.REAL_LIVE
        );

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                32, 0, BiomeCandidateSearch.Source.EXPLORED
            )
        ), BiomeCandidateSearch.findExplored(
            world.store(MapLayer.SURFACE),
            "minecraft:plains",
            0,
            0,
            1_000,
            10
        ));
    }

    @Test
    void exploredGridUsesFullRegistryIdentifiers() {
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                32, -16, BiomeCandidateSearch.Source.EXPLORED
            )
        ), BiomeCandidateSearch.fromGrid(
            "minecraft:plains",
            32,
            -16,
            2,
            1,
            1,
            new String[] {"minecraft:plains", "example:plains"}
        ));
    }

    @Test
    void predictedGridProducesWorldCoordinatesOnlyForTheRequestedBiome() {
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                -12, 20, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                -4, 20, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                -8, 24, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeCandidateSearch.fromGrid(
            7,
            -12,
            20,
            3,
            2,
            4,
            new int[] {7, 2, 7, 3, 7, 4}
        ));
    }

    @Test
    void farthestSelectionOrdersByDistanceAloneAcrossSources() {
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, -9_000, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                0, -8_000, BiomeCandidateSearch.Source.EXPLORED
            ),
            new BiomeCandidateSearch.Candidate(
                30, 40, BiomeCandidateSearch.Source.EXPLORED
            )
        ), BiomeCandidateSearch.select(
            0,
            0,
            10_000,
            3,
            List.of(
                new BiomeCandidateSearch.Candidate(
                    0, -8_000, BiomeCandidateSearch.Source.EXPLORED
                ),
                new BiomeCandidateSearch.Candidate(
                    0, -9_000, BiomeCandidateSearch.Source.PREDICTED
                ),
                new BiomeCandidateSearch.Candidate(
                    30, 40, BiomeCandidateSearch.Source.EXPLORED
                )
            ),
            SearchOrder.FARTHEST
        ));
    }

    @Test
    void farthestSelectionStillClipsTheRadiusAndEnforcesSeparation() {
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                0, -9_000, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                0, 0, BiomeCandidateSearch.Source.EXPLORED
            )
        ), BiomeCandidateSearch.select(
            0,
            0,
            10_000,
            4,
            List.of(
                new BiomeCandidateSearch.Candidate(
                    0, -12_000, BiomeCandidateSearch.Source.EXPLORED
                ),
                new BiomeCandidateSearch.Candidate(
                    0, -9_000, BiomeCandidateSearch.Source.PREDICTED
                ),
                new BiomeCandidateSearch.Candidate(
                    0, -8_950, BiomeCandidateSearch.Source.EXPLORED
                ),
                new BiomeCandidateSearch.Candidate(
                    0, 0, BiomeCandidateSearch.Source.EXPLORED
                )
            ),
            SearchOrder.FARTHEST
        ));
    }

    @Test
    void nearestSelectionKeepsExploredPriorityWithDistanceAscending() {
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                30, 40, BiomeCandidateSearch.Source.EXPLORED
            ),
            new BiomeCandidateSearch.Candidate(
                0, -8_000, BiomeCandidateSearch.Source.EXPLORED
            ),
            new BiomeCandidateSearch.Candidate(
                0, -9_000, BiomeCandidateSearch.Source.PREDICTED
            )
        ), BiomeCandidateSearch.select(
            0,
            0,
            10_000,
            3,
            List.of(
                new BiomeCandidateSearch.Candidate(
                    0, -8_000, BiomeCandidateSearch.Source.EXPLORED
                ),
                new BiomeCandidateSearch.Candidate(
                    0, -9_000, BiomeCandidateSearch.Source.PREDICTED
                ),
                new BiomeCandidateSearch.Candidate(
                    30, 40, BiomeCandidateSearch.Source.EXPLORED
                )
            ),
            SearchOrder.NEAREST
        ));
    }

    @Test
    void farthestPredictedGridPickUsesTheOutermostCellOfEachComponent() {
        final int[] grid = {1, 7, 7, 7, 7, 1};

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(300, 0, BiomeCandidateSearch.Source.PREDICTED)
        ), BiomeCandidateSearch.fromGrid(7, 0, 0, 6, 1, 100, grid, SearchOrder.NEAREST));
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(100, 0, BiomeCandidateSearch.Source.PREDICTED)
        ), BiomeCandidateSearch.fromGrid(7, 0, 0, 6, 1, 100, grid, SearchOrder.FARTHEST));
    }

    @Test
    void farthestExploredSearchPrefersTheOutermostColumnOfEachCell() {
        final SessionGuard.Session session = new SessionGuard.Session(
            7L, WorldIdentity.singleplayer("farthest-biome-world"), DimensionId.OVERWORLD
        );
        final MapWorld world = new MapWorld(session);
        world.put(MapLayer.SURFACE, riverColumns(session.token(), 0, 0), SampleSource.REAL_LIVE);
        world.put(MapLayer.SURFACE, riverColumns(session.token(), 7, 7), SampleSource.REAL_LIVE);

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(0, 0, BiomeCandidateSearch.Source.EXPLORED)
        ), BiomeCandidateSearch.findExplored(
            world.store(MapLayer.SURFACE), "minecraft:river", 0, 0, 10_000, 10, SearchOrder.NEAREST
        ));
        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(127, 127, BiomeCandidateSearch.Source.EXPLORED)
        ), BiomeCandidateSearch.findExplored(
            world.store(MapLayer.SURFACE), "minecraft:river", 0, 0, 10_000, 10, SearchOrder.FARTHEST
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

    @Test
    void exploredCandidatesComeBeforePredictionAndNearbyDuplicatesCollapse() {
        final List<BiomeCandidateSearch.Candidate> candidates = BiomeCandidateSearch.select(
            0,
            0,
            2_000,
            3,
            List.of(
                new BiomeCandidateSearch.Candidate(
                    1_000, 0, BiomeCandidateSearch.Source.EXPLORED
                ),
                new BiomeCandidateSearch.Candidate(
                    10, 0, BiomeCandidateSearch.Source.PREDICTED
                ),
                new BiomeCandidateSearch.Candidate(
                    1_050, 30, BiomeCandidateSearch.Source.PREDICTED
                ),
                new BiomeCandidateSearch.Candidate(
                    500, 0, BiomeCandidateSearch.Source.PREDICTED
                )
            )
        );

        assertEquals(List.of(
            new BiomeCandidateSearch.Candidate(
                1_000, 0, BiomeCandidateSearch.Source.EXPLORED
            ),
            new BiomeCandidateSearch.Candidate(
                10, 0, BiomeCandidateSearch.Source.PREDICTED
            ),
            new BiomeCandidateSearch.Candidate(
                500, 0, BiomeCandidateSearch.Source.PREDICTED
            )
        ), candidates);
    }
}
