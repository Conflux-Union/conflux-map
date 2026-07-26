package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void structureMarkersDoNotLeakBetweenWorlds() {
        final Path cacheRoot = tempDir.resolve("cache");
        final long firstWorldMarker = pack(32, 48);
        final long secondWorldMarker = pack(96, 112);

        final StructureIndex firstWorld = new StructureIndex(
            cacheRoot,
            WorldIdentity.singleplayer("first-world"),
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {firstWorldMarker} : new long[0]
        );
        assertEquals(1, firstWorld.query(0, 128, 0, 128).size());
        firstWorld.save();

        final StructureIndex secondWorld = new StructureIndex(
            cacheRoot,
            WorldIdentity.singleplayer("second-world"),
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {secondWorldMarker} : new long[0]
        );
        final List<StructureIndex.Marker> markers = secondWorld.query(0, 128, 0, 128);

        assertEquals(1, markers.size());
        assertFalse(markers.stream().anyMatch(marker -> marker.blockX() == 32 && marker.blockZ() == 48));
        assertEquals(96, markers.get(0).blockX());
        assertEquals(112, markers.get(0).blockZ());
    }

    @Test
    void structureMarkersDoNotLeakBetweenGameVersions() {
        final Path cacheRoot = tempDir.resolve("cache");
        final WorldIdentity world = WorldIdentity.singleplayer("upgraded-world");
        final StructureIndex oldVersion = new StructureIndex(
            cacheRoot,
            world,
            DimensionId.OVERWORLD,
            21,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {pack(32, 48)} : new long[0]
        );
        oldVersion.query(0, 128, 0, 128);
        oldVersion.save();

        final StructureIndex upgradedVersion = new StructureIndex(
            cacheRoot,
            world,
            DimensionId.OVERWORLD,
            30,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {pack(96, 112)} : new long[0]
        );
        final List<StructureIndex.Marker> markers = upgradedVersion.query(
            0,
            128,
            0,
            128,
            EnumSet.of(StructureIndex.StructureType.VILLAGE)
        );

        assertFalse(markers.stream().anyMatch(marker -> marker.blockX() == 32 && marker.blockZ() == 48));
        assertTrue(markers.stream().anyMatch(marker -> marker.blockX() == 96 && marker.blockZ() == 112));
    }

    @Test
    void queryUsesStructureSpecificRegionsAndDoesNotRepeatCoveredQueries() {
        final List<String> requests = new ArrayList<>();
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("world"),
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> {
                requests.add(type.id() + ":" + regionX + ":" + regionZ);
                return new long[0];
            }
        );

        final EnumSet<StructureIndex.StructureType> types = EnumSet.of(
            StructureIndex.StructureType.VILLAGE,
            StructureIndex.StructureType.OCEAN_MONUMENT,
            StructureIndex.StructureType.WOODLAND_MANSION,
            StructureIndex.StructureType.PILLAGER_OUTPOST,
            StructureIndex.StructureType.RUINED_PORTAL
        );
        index.query(0, 639, 0, 639, types);
        final List<String> firstQuery = List.copyOf(requests);
        index.query(0, 639, 0, 639, types);

        assertEquals(14, firstQuery.size());
        assertEquals(List.of(
            "village:0:0",
            "village:1:0",
            "village:0:1",
            "village:1:1",
            "ocean_monument:0:0",
            "ocean_monument:1:0",
            "ocean_monument:0:1",
            "ocean_monument:1:1",
            "woodland_mansion:0:0",
            "pillager_outpost:0:0",
            "pillager_outpost:1:0",
            "pillager_outpost:0:1",
            "pillager_outpost:1:1",
            "ruined_portal:0:0"
        ), firstQuery);
        assertEquals(firstQuery, requests);
    }

    @Test
    void persistentIdentityMigratesDirectoryBasedStructureIndex() throws IOException {
        final Path saveRoot = tempDir.resolve("saves").resolve("New World");
        Files.createDirectories(saveRoot);
        Files.writeString(saveRoot.resolve("level.dat"), "test save");
        final WorldIdentity currentWorld = WorldIdentity.singleplayerSave(saveRoot);
        final WorldIdentity directoryBasedWorld = new WorldIdentity("local", currentWorld.legacyStorageIds().get(0));
        final Path cacheRoot = tempDir.resolve("cache");
        final StructureIndex oldIndex = new StructureIndex(
            cacheRoot,
            directoryBasedWorld,
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> type == StructureIndex.StructureType.VILLAGE
                ? new long[] {pack(32, 48)} : new long[0]
        );
        oldIndex.query(0, 64, 0, 64);
        oldIndex.save();
        final Path oldRoot = cacheRoot.resolve("structures").resolve("local").resolve(directoryBasedWorld.worldId());

        final StructureIndex currentIndex = new StructureIndex(
            cacheRoot,
            currentWorld,
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> new long[0]
        );

        assertTrue(currentIndex.query(0, 64, 0, 64).stream()
            .anyMatch(marker -> marker.blockX() == 32 && marker.blockZ() == 48));
        assertFalse(Files.exists(oldRoot));
    }

    @Test
    void structureTypesAreRestrictedToTheirGenerationDimension() {
        assertTrue(StructureIndex.StructureType.VILLAGE.supports(DimensionId.OVERWORLD));
        assertFalse(StructureIndex.StructureType.VILLAGE.supports(DimensionId.END));
        assertTrue(StructureIndex.StructureType.END_CITY.supports(DimensionId.END));
        assertFalse(StructureIndex.StructureType.END_CITY.supports(DimensionId.OVERWORLD));
    }

    @Test
    void minecraft117CatalogCoversEveryVanillaStructureSet() {
        assertEquals(EnumSet.of(
            StructureIndex.StructureType.DESERT_PYRAMID,
            StructureIndex.StructureType.JUNGLE_TEMPLE,
            StructureIndex.StructureType.SWAMP_HUT,
            StructureIndex.StructureType.IGLOO,
            StructureIndex.StructureType.VILLAGE,
            StructureIndex.StructureType.OCEAN_RUIN,
            StructureIndex.StructureType.SHIPWRECK,
            StructureIndex.StructureType.OCEAN_MONUMENT,
            StructureIndex.StructureType.WOODLAND_MANSION,
            StructureIndex.StructureType.PILLAGER_OUTPOST,
            StructureIndex.StructureType.RUINED_PORTAL,
            StructureIndex.StructureType.BURIED_TREASURE,
            StructureIndex.StructureType.MINESHAFT,
            StructureIndex.StructureType.STRONGHOLD
        ), StructureIndex.StructureType.availableIn(21, DimensionId.OVERWORLD));
        assertEquals(EnumSet.of(
            StructureIndex.StructureType.RUINED_PORTAL_NETHER,
            StructureIndex.StructureType.NETHER_FOSSIL,
            StructureIndex.StructureType.FORTRESS,
            StructureIndex.StructureType.BASTION_REMNANT
        ), StructureIndex.StructureType.availableIn(21, DimensionId.NETHER));
        assertEquals(
            EnumSet.of(StructureIndex.StructureType.END_CITY),
            StructureIndex.StructureType.availableIn(21, DimensionId.END)
        );
    }

    @Test
    void modernCatalogAddsEveryPost117Structure() {
        final EnumSet<StructureIndex.StructureType> overworld =
            StructureIndex.StructureType.availableIn(30, DimensionId.OVERWORLD);

        assertTrue(overworld.contains(StructureIndex.StructureType.ANCIENT_CITY));
        assertTrue(overworld.contains(StructureIndex.StructureType.TRAIL_RUINS));
        assertTrue(overworld.contains(StructureIndex.StructureType.TRIAL_CHAMBERS));
        assertEquals(17, overworld.size());
    }

    @Test
    void catalogUsesPinnedCubiomesStructureOrdinals() {
        assertEquals(5, StructureIndex.StructureType.VILLAGE.nativeId());
        assertEquals(8, StructureIndex.StructureType.OCEAN_MONUMENT.nativeId());
        assertEquals(20, StructureIndex.StructureType.END_CITY.nativeId());
        assertEquals(25, StructureIndex.StructureType.STRONGHOLD.nativeId());
        assertEquals(26, StructureIndex.StructureType.NETHER_FOSSIL.nativeId());
    }

    @Test
    void nearestSearchAddsAndReturnsTheLocatedCandidate() {
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("world"),
            DimensionId.OVERWORLD,
            new StructureIndex.CandidateProvider() {
                @Override
                public long[] candidates(
                    final StructureIndex.StructureType type,
                    final int regionX,
                    final int regionZ
                ) {
                    return new long[0];
                }

                @Override
                public OptionalLong nearest(
                    final StructureIndex.StructureType type,
                    final int blockX,
                    final int blockZ,
                    final int maxRadius
                ) {
                    return type == StructureIndex.StructureType.STRONGHOLD
                        ? OptionalLong.of(pack(1200, -800))
                        : OptionalLong.empty();
                }
            }
        );

        final StructureIndex.Marker marker = index.findNearest(
            StructureIndex.StructureType.STRONGHOLD, 0, 0, 2000
        ).orElseThrow();

        assertEquals(StructureIndex.StructureType.STRONGHOLD, marker.type());
        assertEquals(1200, marker.blockX());
        assertEquals(-800, marker.blockZ());
    }

    private static long pack(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }
}
