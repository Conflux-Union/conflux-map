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
