package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

class ClientWorldProfileTerrainVisitTest {
    @Test
    void movingAVisitDoesNotRelabelItsExistingTerrainCenter() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");
        final ClientWorldTerrainFingerprint fingerprint = fingerprint(4, -2);
        profile.rememberVisit(observation(new ClientWorldPosition(64, 64, -32), fingerprint));

        profile.rememberVisit(observation(new ClientWorldPosition(256, 64, -32), null));

        final ClientWorldVisit visit = profile.visit("minecraft_overworld");
        assertEquals(256, visit.lastPosition().x());
        assertTrue(visit.terrainFingerprint().hasCenter());
        assertEquals(4, visit.terrainFingerprint().centerChunkX());
        assertEquals(-2, visit.terrainFingerprint().centerChunkZ());
    }

    @Test
    void movementWithoutHistoricalTerrainCanStillUpdateVisitPosition() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");
        profile.rememberVisit(observation(new ClientWorldPosition(64, 64, -32), null));

        profile.rememberVisit(observation(new ClientWorldPosition(256, 64, -32), null));

        assertEquals(256, profile.visit("minecraft_overworld").lastPosition().x());
    }

    @Test
    void incompleteTerrainCannotRelabelAnExistingVisit() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");
        final ClientWorldTerrainFingerprint saved = fingerprint(4, -2);
        profile.rememberVisit(observation(new ClientWorldPosition(64, 64, -32), saved));
        final ClientWorldTerrainFingerprint incomplete = ClientWorldTerrainFingerprint.from(
            List.of(snapshot(4, -2, (short) 90)), 4, -2
        );

        profile.rememberVisit(observation(new ClientWorldPosition(256, 64, -32), incomplete));

        final ClientWorldVisit visit = profile.visit("minecraft_overworld");
        assertEquals(256, visit.lastPosition().x());
        assertTrue(visit.terrainFingerprint().sameCenter(saved));
    }

    @Test
    void completeTerrainAtAnOldCenterCannotBePairedWithANewPosition() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");
        final ClientWorldTerrainFingerprint saved = fingerprint(4, -2);
        profile.rememberVisit(observation(new ClientWorldPosition(64, 64, -32), saved));

        profile.rememberVisit(observation(new ClientWorldPosition(256, 64, -32), saved));

        final ClientWorldVisit visit = profile.visit("minecraft_overworld");
        assertEquals(256, visit.lastPosition().x());
        assertTrue(visit.terrainFingerprint().sameCenter(saved));
    }

    @Test
    void completeTerrainWithoutAPlayerPositionCannotCreateAnAnchor() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");

        profile.rememberVisit(observation(null, fingerprint(4, -2)));

        assertTrue(profile.visit("minecraft_overworld").terrainAnchors().isEmpty());
    }

    @Test
    void profileSpecificTerrainEvidenceIsUsedOnlyForCandidateMatching() {
        final ClientWorldTerrainFingerprint current = fingerprint(0, 0);
        final ClientWorldTerrainFingerprint savedCenterSample = fingerprint(4, -2);
        final ClientWorldObservation observation = new ClientWorldObservation(
            OptionalLong.of(1L), Map.of("brand", "stable"), "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(64, 64, -32), current, Map.of("profile", savedCenterSample)
        );

        assertTrue(observation.terrainFingerprintFor("profile").sameCenter(savedCenterSample));
        assertTrue(observation.terrainFingerprintFor("other").sameCenter(current));
    }

    @Test
    void multipleTerrainAnchorsRemainBoundToTheirOwnCenters() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");
        final ClientWorldTerrainFingerprint first = fingerprint(0, 0);
        final ClientWorldTerrainFingerprint second = fingerprint(4, -2);
        profile.rememberVisit(observation(new ClientWorldPosition(0, 64, 0), first));
        profile.rememberVisit(observation(new ClientWorldPosition(64, 64, -32), second));
        profile.rememberVisit(observation(new ClientWorldPosition(512, 64, 512), null));

        final ClientWorldVisit visit = profile.visit("minecraft_overworld");
        assertEquals(2, visit.terrainAnchors().size());
        assertTrue(visit.terrainAnchorFor(first).fingerprint().sameCenter(first));
        assertTrue(visit.terrainAnchorFor(second).fingerprint().sameCenter(second));
        assertEquals(512, visit.lastPosition().x());
    }

    @Test
    void changingDimensionKeepsTerrainHistoryButOnlyOneLatestPosition() {
        final ClientWorldProfile profile = new ClientWorldProfile("id", "world", "World");
        final ClientWorldTerrainFingerprint overworldTerrain = fingerprint(0, 0);
        profile.rememberVisit(new ClientWorldObservation(
            OptionalLong.of(1L), Map.of("brand", "stable"), "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 70, 0), overworldTerrain
        ));

        profile.rememberVisit(new ClientWorldObservation(
            OptionalLong.of(1L), Map.of("brand", "stable"), "minecraft_the_nether", "SURVIVAL",
            new ClientWorldPosition(4, 91, -33), null
        ));

        final ClientWorldVisit overworld = profile.visit("minecraft_overworld");
        final ClientWorldVisit nether = profile.visit("minecraft_the_nether");
        assertNull(overworld.lastPosition());
        assertTrue(overworld.trajectorySamples().isEmpty());
        assertEquals(1, overworld.terrainAnchors().size());
        assertEquals(nether, profile.lastObservedVisit());
        assertEquals(91, profile.lastObservedVisit().lastPosition().y());
        assertNull(profile.lastObservedVisit("minecraft_overworld"));
        assertEquals(nether, profile.lastObservedVisit("minecraft_the_nether"));
    }

    private static ClientWorldObservation observation(
        final ClientWorldPosition position,
        final ClientWorldTerrainFingerprint terrainFingerprint
    ) {
        return new ClientWorldObservation(
            OptionalLong.of(1L), Map.of("brand", "stable"), "minecraft_overworld", "SURVIVAL",
            position, terrainFingerprint
        );
    }

    private static ClientWorldTerrainFingerprint fingerprint(final int centerChunkX, final int centerChunkZ) {
        final List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
            for (int offsetX = -1; offsetX <= 1; offsetX++) {
                final short[] heights = new short[ChunkSnapshot.COLUMNS];
                final String[] biomes = new String[ChunkSnapshot.COLUMNS];
                final byte[] fluids = new byte[ChunkSnapshot.COLUMNS];
                final byte[] kinds = new byte[ChunkSnapshot.COLUMNS];
                Arrays.fill(heights, (short) 70);
                Arrays.fill(biomes, "minecraft:plains");
                Arrays.fill(kinds, (byte) 2);
                snapshots.add(new ChunkSnapshot(
                    centerChunkX + offsetX, centerChunkZ + offsetZ, 0L,
                    heights, biomes, fluids, new int[ChunkSnapshot.COLUMNS], new int[ChunkSnapshot.COLUMNS],
                    new int[ChunkSnapshot.COLUMNS], kinds, new byte[ChunkSnapshot.COLUMNS]
                ));
            }
        }
        return ClientWorldTerrainFingerprint.from(snapshots, centerChunkX, centerChunkZ);
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final short height) {
        final short[] heights = new short[ChunkSnapshot.COLUMNS];
        final String[] biomes = new String[ChunkSnapshot.COLUMNS];
        final byte[] fluids = new byte[ChunkSnapshot.COLUMNS];
        final byte[] kinds = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(heights, height);
        Arrays.fill(biomes, "minecraft:plains");
        Arrays.fill(kinds, (byte) 2);
        return new ChunkSnapshot(
            chunkX, chunkZ, 0L, heights, biomes, fluids,
            new int[ChunkSnapshot.COLUMNS], new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS], kinds, new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
