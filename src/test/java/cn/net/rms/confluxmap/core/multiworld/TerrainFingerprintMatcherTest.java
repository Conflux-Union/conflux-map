package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TerrainFingerprintMatcherTest {
    @Test
    void selectsOnlyAHighConfidenceWinnerWithEnoughTerrain() {
        final List<ChunkSnapshot> probes = probes(4, (short) 70);
        final TerrainFingerprintMatcher.Candidate expected = candidate("survival", (short) 70);
        final TerrainFingerprintMatcher.Candidate different = candidate("creative", (short) 120);

        final TerrainFingerprintMatcher.Result result = TerrainFingerprintMatcher.match(
            probes, List.of(expected, different)
        );

        assertEquals("survival", result.profileId().orElseThrow());
        assertTrue(result.bestScore() >= TerrainFingerprintMatcher.MIN_SCORE);
    }

    @Test
    void refusesTiesAndInsufficientTerrain() {
        final TerrainFingerprintMatcher.Candidate first = candidate("first", (short) 70);
        final TerrainFingerprintMatcher.Candidate second = candidate("second", (short) 70);

        assertTrue(TerrainFingerprintMatcher.match(probes(4, (short) 70), List.of(first, second))
            .profileId().isEmpty());
        assertTrue(TerrainFingerprintMatcher.match(probes(1, (short) 70), List.of(first))
            .profileId().isEmpty());
    }

    private static List<ChunkSnapshot> probes(final int count, final short height) {
        final List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int chunkX = 0; chunkX < count; chunkX++) {
            final short[] heights = new short[ChunkSnapshot.COLUMNS];
            final String[] biomes = new String[ChunkSnapshot.COLUMNS];
            final byte[] fluids = new byte[ChunkSnapshot.COLUMNS];
            final byte[] kinds = new byte[ChunkSnapshot.COLUMNS];
            Arrays.fill(heights, height);
            Arrays.fill(biomes, "minecraft:plains");
            Arrays.fill(kinds, (byte) 2);
            snapshots.add(new ChunkSnapshot(
                chunkX, 0, 0L, heights, biomes, fluids,
                new int[ChunkSnapshot.COLUMNS], new int[ChunkSnapshot.COLUMNS],
                new int[ChunkSnapshot.COLUMNS], kinds, new byte[ChunkSnapshot.COLUMNS]
            ));
        }
        return snapshots;
    }

    private static TerrainFingerprintMatcher.Candidate candidate(final String id, final short height) {
        final int columns = RegionFileCodec.COLUMN_COUNT;
        final short[] heights = new short[columns];
        final String[] biomes = new String[columns];
        final byte[] kinds = new byte[columns];
        Arrays.fill(heights, height);
        Arrays.fill(biomes, "minecraft:plains");
        Arrays.fill(kinds, (byte) 2);
        final RegionFileCodec.RegionData region = new RegionFileCodec.RegionData(
            0, 0, 0L,
            new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            new int[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            heights,
            new byte[columns],
            kinds,
            biomes,
            new int[columns],
            new int[columns],
            new int[columns],
            new byte[columns]
        );
        return new TerrainFingerprintMatcher.Candidate(
            id, Map.of(new TerrainFingerprintMatcher.RegionPos(0, 0), region)
        );
    }
}
