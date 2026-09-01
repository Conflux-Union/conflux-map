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
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StructureIndexTest {
    @TempDir
    Path tempDir;

    @Test
    void detailedCandidatesKeepTheirVariantAcrossPersistence() {
        final Path cacheRoot = tempDir.resolve("cache");
        final WorldIdentity world = WorldIdentity.singleplayer("variant-world");
        final StructureIndex index = new StructureIndex(
            cacheRoot,
            world,
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
                public StructureIndex.Candidate[] detailedCandidates(
                    final StructureIndex.StructureType type,
                    final int minRegionX,
                    final int minRegionZ,
                    final int maxRegionX,
                    final int maxRegionZ
                ) {
                    return type == StructureIndex.StructureType.VILLAGE
                        ? new StructureIndex.Candidate[] {
                            new StructureIndex.Candidate(32, 48, 10)
                        }
                        : new StructureIndex.Candidate[0];
                }
            }
        );

        final StructureIndex.Marker marker = index.query(
            0, 128, 0, 128, EnumSet.of(StructureIndex.StructureType.VILLAGE)
        ).get(0);
        assertEquals(10, marker.variant());
        assertEquals("confluxmap.structure.village.zombie_savanna", marker.translationKey());
        index.save();

        final StructureIndex reloaded = new StructureIndex(
            cacheRoot,
            world,
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> new long[0]
        );
        final StructureIndex.Marker persisted = reloaded.query(
            0, 128, 0, 128, EnumSet.of(StructureIndex.StructureType.VILLAGE)
        ).get(0);
        assertEquals(10, persisted.variant());
        assertEquals("confluxmap.structure.village.zombie_savanna", persisted.translationKey());
    }

    @Test
    void nearestDetailedCandidateKeepsItsVariant() {
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("nearest-variant-world"),
            DimensionId.END,
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
                public Optional<StructureIndex.Candidate> nearestCandidate(
                    final StructureIndex.StructureType type,
                    final int blockX,
                    final int blockZ,
                    final int maxRadius
                ) {
                    return Optional.of(new StructureIndex.Candidate(80, 3280, 1));
                }
            }
        );

        final StructureIndex.Marker marker = index.findNearest(
            StructureIndex.StructureType.END_CITY, 0, 3000, 1000
        ).orElseThrow();

        assertEquals(1, marker.variant());
        assertEquals("confluxmap.structure.end_city.ship", marker.translationKey());
    }

    @Test
    void variantTranslationKeysDescribeEverySupportedVariant() {
        assertEquals(
            "confluxmap.structure.village.snowy",
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(4)
        );
        assertEquals(
            "confluxmap.structure.village.zombie_taiga",
            StructureIndex.StructureType.VILLAGE.variantTranslationKey(11)
        );
        assertEquals(
            "confluxmap.structure.igloo.basement",
            StructureIndex.StructureType.IGLOO.variantTranslationKey(1)
        );
        assertEquals(
            "confluxmap.structure.shipwreck.beached",
            StructureIndex.StructureType.SHIPWRECK.variantTranslationKey(1)
        );
        assertEquals(
            "confluxmap.structure.bastion_remnant.treasure",
            StructureIndex.StructureType.BASTION_REMNANT.variantTranslationKey(2)
        );
        assertEquals(
            "confluxmap.structure.ruined_portal.giant",
            StructureIndex.StructureType.RUINED_PORTAL.variantTranslationKey(1)
        );
        assertEquals(
            "confluxmap.structure.end_city.ship",
            StructureIndex.StructureType.END_CITY.variantTranslationKey(1)
        );
        assertEquals(
            StructureIndex.StructureType.STRONGHOLD.translationKey(),
            StructureIndex.StructureType.STRONGHOLD.variantTranslationKey(99)
        );
    }

    @Test
    void exposesEverySearchableStructureVariant() {
        assertEquals(
            List.of(0, 1, 2, 3, 4, 8, 9, 10, 11, 12),
            StructureIndex.StructureType.VILLAGE.variantCodes()
        );
        assertEquals(List.of(0, 1), StructureIndex.StructureType.IGLOO.variantCodes());
        assertEquals(List.of(0, 1), StructureIndex.StructureType.SHIPWRECK.variantCodes());
        assertEquals(List.of(0, 1, 2, 3), StructureIndex.StructureType.BASTION_REMNANT.variantCodes());
        assertEquals(List.of(0, 1), StructureIndex.StructureType.RUINED_PORTAL.variantCodes());
        assertEquals(List.of(0, 1), StructureIndex.StructureType.RUINED_PORTAL_NETHER.variantCodes());
        assertEquals(List.of(0, 1), StructureIndex.StructureType.END_CITY.variantCodes());
        assertTrue(StructureIndex.StructureType.STRONGHOLD.variantCodes().isEmpty());
    }

    @Test
    void radiusCandidateSearchFiltersVariantsBeforeApplyingTheResultLimit() {
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("variant-filter-world"),
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
                public StructureIndex.Candidate[] detailedCandidates(
                    final StructureIndex.StructureType type,
                    final int minRegionX,
                    final int minRegionZ,
                    final int maxRegionX,
                    final int maxRegionZ
                ) {
                    return new StructureIndex.Candidate[] {
                        new StructureIndex.Candidate(16, 16, 0),
                        new StructureIndex.Candidate(32, 32, 2)
                    };
                }
            }
        );

        final List<StructureIndex.Marker> result = index.findCandidates(
            StructureIndex.StructureType.VILLAGE,
            0,
            0,
            1_000,
            1,
            OptionalInt.of(2)
        );

        assertEquals(1, result.size());
        assertEquals(2, result.get(0).variant());
        assertEquals(32, result.get(0).blockX());
    }

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
    void queryDoesNotLoadOrReturnHiddenStructureTypes() {
        final List<StructureIndex.StructureType> requests = new ArrayList<>();
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("filtered-world"),
            DimensionId.OVERWORLD,
            (type, regionX, regionZ) -> {
                requests.add(type);
                if (type == StructureIndex.StructureType.VILLAGE) {
                    return new long[] {pack(32, 48)};
                }
                if (type == StructureIndex.StructureType.STRONGHOLD) {
                    return new long[] {pack(96, 112)};
                }
                return new long[0];
            }
        );

        final List<StructureIndex.Marker> markers = index.query(
            0,
            128,
            0,
            128,
            EnumSet.of(StructureIndex.StructureType.VILLAGE)
        );

        assertEquals(List.of(StructureIndex.StructureType.VILLAGE), requests);
        assertEquals(1, markers.size());
        assertEquals(StructureIndex.StructureType.VILLAGE, markers.get(0).type());
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

    @Test
    void candidateSearchReturnsNearestMarkersInDistanceOrderAndCapsTheResult() {
        final List<String> batchRequests = new ArrayList<>();
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("candidate-world"),
            DimensionId.OVERWORLD,
            new StructureIndex.CandidateProvider() {
                @Override
                public long[] candidates(
                    final StructureIndex.StructureType type,
                    final int regionX,
                    final int regionZ
                ) {
                    throw new AssertionError("candidate finder must use the bounded batch lookup");
                }

                @Override
                public long[] candidates(
                    final StructureIndex.StructureType type,
                    final int minRegionX,
                    final int minRegionZ,
                    final int maxRegionX,
                    final int maxRegionZ
                ) {
                    batchRequests.add(minRegionX + "," + minRegionZ + ":" + maxRegionX + "," + maxRegionZ);
                    final List<Long> positions = new ArrayList<>();
                    for (int regionZ = minRegionZ; regionZ <= maxRegionZ; regionZ++) {
                        for (int regionX = minRegionX; regionX <= maxRegionX; regionX++) {
                            positions.add(pack(regionX * 512, regionZ * 512));
                        }
                    }
                    final long[] packed = new long[positions.size()];
                    for (int index = 0; index < positions.size(); index++) {
                        packed[index] = positions.get(index);
                    }
                    return packed;
                }
            }
        );

        final List<StructureIndex.Marker> nearest = index.findNearestCandidates(
            StructureIndex.StructureType.VILLAGE, 0, 0, 3
        );

        assertEquals(3, nearest.size());
        assertEquals(0, nearest.get(0).blockX());
        assertEquals(0, nearest.get(0).blockZ());
        assertTrue(distanceSquared(nearest.get(0)) <= distanceSquared(nearest.get(1)));
        assertTrue(distanceSquared(nearest.get(1)) <= distanceSquared(nearest.get(2)));
        final List<StructureIndex.Marker> maximum = index.findNearestCandidates(
            StructureIndex.StructureType.VILLAGE, 0, 0, 100
        );
        assertEquals(32, maximum.size());
        assertEquals(2, batchRequests.size(), "each candidate rectangle needs one native batch");
    }

    @Test
    void normalStructureLabelsRemainVisibleAtPointOneTwoFiveZoom() {
        assertTrue(StructureIndex.StructureType.VILLAGE.displaysAt(16.0));
        assertFalse(StructureIndex.StructureType.VILLAGE.displaysAt(16.01));
        assertTrue(StructureIndex.StructureType.NETHER_FOSSIL.displaysAt(4.0));
        assertFalse(StructureIndex.StructureType.NETHER_FOSSIL.displaysAt(4.01));
    }

    @Test
    void radiusCandidateSearchKeepsNativeBatchesBounded() {
        final List<Long> requestedCells = new ArrayList<>();
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("bounded-radius-world"),
            DimensionId.OVERWORLD,
            new StructureIndex.CandidateProvider() {
                @Override
                public long[] candidates(
                    final StructureIndex.StructureType type,
                    final int regionX,
                    final int regionZ
                ) {
                    throw new AssertionError("radius finder must use batch lookup");
                }

                @Override
                public long[] candidates(
                    final StructureIndex.StructureType type,
                    final int minRegionX,
                    final int minRegionZ,
                    final int maxRegionX,
                    final int maxRegionZ
                ) {
                    requestedCells.add(
                        ((long) maxRegionX - minRegionX + 1L)
                            * ((long) maxRegionZ - minRegionZ + 1L)
                    );
                    return new long[] {pack(128, 128), pack(4_096, 4_096)};
                }
            }
        );

        final List<StructureIndex.Marker> result = index.findCandidates(
            StructureIndex.StructureType.VILLAGE, 0, 0, 100_000, 100
        );

        assertEquals(2, result.size());
        assertEquals(128, result.get(0).blockX());
        assertTrue(requestedCells.stream().allMatch(cells -> cells <= 1_024L));
        assertEquals(1, requestedCells.size(), "oversized expansion must stop before native lookup");
    }

    @Test
    void radiusCandidateSearchHandlesExtremeCoordinatesWithoutOverflow() {
        final StructureIndex index = new StructureIndex(
            tempDir.resolve("cache"),
            WorldIdentity.singleplayer("extreme-coordinate-world"),
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
                public long[] candidates(
                    final StructureIndex.StructureType type,
                    final int minRegionX,
                    final int minRegionZ,
                    final int maxRegionX,
                    final int maxRegionZ
                ) {
                    return new long[] {pack(Integer.MAX_VALUE - 8, Integer.MIN_VALUE + 8)};
                }
            }
        );

        final List<StructureIndex.Marker> result = index.findCandidates(
            StructureIndex.StructureType.VILLAGE,
            Integer.MAX_VALUE,
            Integer.MIN_VALUE,
            32,
            1
        );

        assertEquals(1, result.size());
        assertEquals(Integer.MAX_VALUE - 8, result.get(0).blockX());
        assertEquals(Integer.MIN_VALUE + 8, result.get(0).blockZ());
    }

    private static long distanceSquared(final StructureIndex.Marker marker) {
        return (long) marker.blockX() * marker.blockX() + (long) marker.blockZ() * marker.blockZ();
    }

    private static long pack(final int x, final int z) {
        return ((long) x << 32) | (z & 0xFFFF_FFFFL);
    }
}
