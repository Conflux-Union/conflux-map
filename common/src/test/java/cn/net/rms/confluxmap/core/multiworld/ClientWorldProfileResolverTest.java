package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientWorldProfileResolverTest {
    private static final String SERVER = "proxy.example.net_25565";

    @Test
    void firstObservationKeepsTheLegacyWorldAndNewSeedsGetIsolatedStorage() {
        final ClientWorldProfileResolver resolver = resolver();

        final ClientWorldResolution first = resolver.resolve(SERVER, observation(11L, "survival"));
        final ClientWorldResolution second = resolver.resolve(SERVER, observation(22L, "creative"));

        assertEquals(ClientWorldResolution.State.RESOLVED, first.state());
        assertEquals("world", first.profile().storageId());
        assertEquals(ClientWorldResolution.State.RESOLVED, second.state());
        assertTrue(second.profile().storageId().startsWith("client-"));
        assertNotEquals(first.profile().storageId(), second.profile().storageId());
        // A legacy observation without dimension/position/terrain cannot re-enter the automatic
        // queues, even when its seed is the only known seed.
        assertEquals(
            ClientWorldResolution.State.AMBIGUOUS,
            resolver.resolve(SERVER, observation(11L, "survival")).state()
        );
    }

    @Test
    void proxyJoinWithThePreviousSeedStaysSuspendedUntilTheRealSeedArrives() {
        final ClientWorldProfileResolver resolver = resolver();
        resolver.resolve(SERVER, observation(11L, "survival"));

        final ClientWorldResolution transitional = resolver.resolveAfterProxyWorldJoin(
            SERVER, OptionalLong.of(11L), observation(11L, "survival")
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, transitional.state());
    }

    @Test
    void proxyJoinWithADifferentSeedStillSeparatesAutomatically() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldProfile first = resolver.resolve(SERVER, observation(11L, "survival")).profile();

        final ClientWorldResolution second = resolver.resolveAfterProxyWorldJoin(
            SERVER, OptionalLong.of(11L), observation(22L, "creative")
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, second.state());
        assertNotEquals(first.id(), second.profile().id());
    }

    @Test
    void proxyJoinDoesNotGuessBetweenProfilesAlreadyBoundToTheNewSeed() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile previous = new ClientWorldProfile("previous", "world", "Previous");
        final ClientWorldProfile first = new ClientWorldProfile(
            "first", "client-00000000-0000-0000-0000-000000000001", "First"
        );
        final ClientWorldProfile competing = new ClientWorldProfile(
            "competing", "client-00000000-0000-0000-0000-000000000002", "Competing"
        );
        final ClientWorldObservation firstObservation = seededSignals(22L, "a");
        final ClientWorldObservation competingObservation = seededSignals(22L, "b");
        previous.bind(observation(11L, "survival"));
        first.bind(firstObservation);
        competing.bind(competingObservation);
        registry.mutableProfiles(SERVER).addAll(List.of(previous, first, competing));
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, UUID::randomUUID);

        final ClientWorldResolution collision = resolver.resolveAfterProxyWorldJoin(
            SERVER, OptionalLong.of(11L), competingObservation
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, collision.state());
    }

    @Test
    void manualCorrectionMovesTheObservationToTheSelectedProfile() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation firstWorld = observation(11L, "survival");
        resolver.resolve(SERVER, firstWorld);
        final ClientWorldProfile selected = resolver.resolve(SERVER, observation(22L, "creative")).profile();

        final ClientWorldResolution selectedResolution = resolver.select(SERVER, selected.id(), firstWorld);
        final ClientWorldResolution revisited = resolver.resolve(SERVER, firstWorld);

        assertEquals(ClientWorldResolution.State.RESOLVED, selectedResolution.state());
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, revisited.state());
    }

    @Test
    void manualCreationMovesTheObservationToTheNewProfile() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation observation = observation(11L, "survival");
        resolver.resolve(SERVER, observation);

        final ClientWorldResolution createdResolution = resolver.createAndSelect(
            SERVER, "Correct world", observation
        );
        final ClientWorldResolution revisited = resolver.resolve(SERVER, observation);

        assertEquals(ClientWorldResolution.State.RESOLVED, createdResolution.state());
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, revisited.state());
    }

    @Test
    void missingDimensionCannotUseTheSupportingSignalShortcut() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldProfile first = resolver.resolve(SERVER, observation(11L, "survival")).profile();
        resolver.resolve(SERVER, observation(22L, "creative"));
        final ClientWorldObservation learned = noSeed("vanilla", "commands-a", "dimensions-a");

        resolver.select(SERVER, first.id(), learned);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, learned).state());
    }

    @Test
    void weakOrNonUniqueEvidenceRemainsAmbiguous() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldProfile first = resolver.resolve(SERVER, observation(11L, "survival")).profile();
        final ClientWorldProfile second = resolver.resolve(SERVER, observation(22L, "creative")).profile();
        final ClientWorldObservation twoSignals = new ClientWorldObservation(
            OptionalLong.empty(), Map.of("brand", "same", "commands", "same")
        );
        final ClientWorldObservation threeSignals = noSeed("same", "same", "same");

        resolver.select(SERVER, first.id(), threeSignals);
        resolver.select(SERVER, second.id(), threeSignals);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, twoSignals).state());
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, threeSignals).state());
    }

    @Test
    void oneExistingProfileIsNotAssumedWhenStrongEvidenceIsMissing() {
        final ClientWorldProfileResolver resolver = resolver();
        resolver.resolve(SERVER, observation(11L, "survival"));

        final ClientWorldResolution result = resolver.resolve(
            SERVER, noSeed("unknown", "unknown", "unknown")
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
    }

    @Test
    void manualProfilesCanBeRenamedAndUnboundWithoutDeletingStorage() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation observation = observation(11L, "survival");
        final ClientWorldProfile profile = resolver.resolve(SERVER, observation).profile();

        resolver.rename(SERVER, profile.id(), "Survival");
        resolver.clearBindings(SERVER, profile.id());

        final List<ClientWorldProfile> profiles = resolver.profiles(SERVER);
        assertEquals("Survival", profiles.get(0).displayName());
        assertEquals(0, profiles.get(0).bindingCount());
        assertEquals("world", profiles.get(0).storageId());
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, observation).state());
        assertEquals(profile.id(), resolver.select(SERVER, profile.id(), observation).profile().id());
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, observation).state());
    }

    @Test
    void overworldCoordinateBoundaryUsesFortyEightBlocksForQueuePriority() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        final ClientWorldProfile near = resolver.resolve(
            SERVER, visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70))
        ).profile();
        resolver.resolve(
            SERVER, visitObservation(2L, signals, "minecraft_overworld", "SURVIVAL", 100, 0, fingerprint((short) 70))
        );

        final ClientWorldObservation outsideBoundary = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(49, 64, 0), fingerprint((short) 70)
        );
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, outsideBoundary).state());

        final ClientWorldObservation atBoundary = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(48, 64, 0), fingerprint((short) 70)
        );
        assertEquals(near.id(), resolver.resolve(SERVER, atBoundary).profile().id());
    }

    @Test
    void netherCoordinateBoundaryUsesSixBlocksForQueuePriority() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "nether"
        );
        final ClientWorldProfile near = resolver.resolve(
            SERVER, visitObservation(1L, signals, "minecraft_the_nether", "SURVIVAL", 0, 0, fingerprint((short) 70))
        ).profile();
        resolver.resolve(
            SERVER, visitObservation(2L, signals, "minecraft_the_nether", "SURVIVAL", 20, 0, fingerprint((short) 70))
        );

        final ClientWorldObservation outsideBoundary = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_the_nether", "SURVIVAL",
            new ClientWorldPosition(7, 64, 0), fingerprint((short) 70)
        );
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, outsideBoundary).state());

        final ClientWorldObservation atBoundary = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_the_nether", "SURVIVAL",
            new ClientWorldPosition(6, 64, 0), fingerprint((short) 70)
        );
        assertEquals(near.id(), resolver.resolve(SERVER, atBoundary).profile().id());
    }

    @Test
    void customDimensionContainingNetherUsesTheDefaultFortyEightBlockRadius() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "custom"
        );
        resolver.resolve(
            SERVER, visitObservation(1L, signals, "example_netherish", "CREATIVE", 0, 0, null)
        );
        resolver.resolve(
            SERVER, visitObservation(2L, signals, "example_netherish", "SURVIVAL", 100, 0, null)
        );

        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "example_netherish", "SURVIVAL",
            new ClientWorldPosition(7, 64, 0), null
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, current).state());
    }

    @Test
    void singleCandidateWithoutADimensionVisitStillUsesTheQueueRules() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "commands", "shared",
            "dimensions", "shared"
        );
        resolver.resolve(SERVER, new ClientWorldObservation(OptionalLong.of(1L), signals));

        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), null
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, current).state());
    }

    @Test
    void uniqueSeedWithVisitContextStillRequiresTheFirstQueueTerrainGate() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, null)
        ).profile();

        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), null
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
        assertEquals(profile.id(), result.candidates().get(0).profileId());
    }

    @Test
    void sameDimensionWithoutCurrentPositionCannotEnterTheFirstQueue() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70))
        ).profile();

        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_overworld", "SURVIVAL", null,
            fingerprint((short) 70)
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
        assertEquals(profile.id(), result.candidates().get(0).profileId());
        assertFalse(result.candidates().get(0).reasons().contains("position_near"));
    }

    @Test
    void differentDimensionCannotBePromotedByStrongSupportingEvidence() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70))
        ).profile();

        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_the_nether", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70)
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
        assertEquals(profile.id(), result.candidates().get(0).profileId());
        assertTrue(result.candidates().get(0).reasons().contains("legacy_profile"));
    }

    @Test
    void uniqueSeedWithoutEnoughSupportingSignalsRequiresManualSelection() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation weak = new ClientWorldObservation(
            OptionalLong.of(1L), Map.of("brand", "shared")
        );
        resolver.resolve(SERVER, weak);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, weak).state());
    }

    @Test
    void stableVisitRefreshPersistsCurrentTerrainInsteadOfCandidateProbe() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared");
        final ClientWorldTerrainFingerprint saved = fingerprint((short) 70, 0, 0);
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, saved)
        ).profile();
        final ClientWorldTerrainFingerprint currentTerrain = fingerprint((short) 80, 4, -2);
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(64, 64, -32), currentTerrain,
            Map.of(profile.id(), saved)
        );

        assertTrue(resolver.rememberVisit(SERVER, profile.id(), current).applied());
        final ClientWorldTerrainFingerprint persisted = resolver.profiles(SERVER).get(0)
            .visit("minecraft_overworld").terrainFingerprint();
        assertTrue(persisted.sameCenter(currentTerrain));
        assertEquals(1.0D, persisted.match(currentTerrain).score());
    }

    @Test
    void automaticSelectionDoesNotPersistCandidateHistoricalTerrain() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared", "dimension_type", "overworld");
        final ClientWorldTerrainFingerprint savedCenter = fingerprint((short) 70, 2, 0);
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 32, 0, savedCenter)
        ).profile();
        final ClientWorldTerrainFingerprint currentCenter = fingerprint((short) 80, 0, 0);
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), currentCenter,
            Map.of(profile.id(), savedCenter)
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(resolver.profiles(SERVER).get(0).visit("minecraft_overworld")
            .terrainFingerprint().sameCenter(currentCenter));
    }

    @Test
    void manualSelectionDoesNotPersistCandidateHistoricalTerrain() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared");
        final ClientWorldTerrainFingerprint savedCenter = fingerprint((short) 70, 2, 0);
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 32, 0, savedCenter)
        ).profile();
        final ClientWorldTerrainFingerprint currentCenter = fingerprint((short) 80, 0, 0);
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), currentCenter,
            Map.of(profile.id(), savedCenter)
        );

        assertEquals(profile.id(), resolver.select(SERVER, profile.id(), current).profile().id());
        assertTrue(resolver.profiles(SERVER).get(0).visit("minecraft_overworld")
            .terrainFingerprint().sameCenter(currentCenter));
    }

    @Test
    void auxiliaryAndTerrainEvidenceUseSixtyFortyWeighting() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        final ClientWorldProfile auxiliaryWinner = resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70, (byte) 1)
            )
        ).profile();
        resolver.resolve(
            SERVER,
            visitObservation(
                2L, signals, "minecraft_overworld", "CREATIVE", 0, 0, fingerprint((short) 70)
            )
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70)
        );

        assertEquals(auxiliaryWinner.id(), resolver.resolve(SERVER, current).profile().id());
    }

    @Test
    void candidatesWithinThreePercentagePointsRequireManualSelection() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70)
            )
        );
        resolver.resolve(
            SERVER,
            visitObservation(
                2L, signals, "minecraft_overworld", "SURVIVAL", 1, 0, fingerprint((short) 70)
            )
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70)
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, current).state());
    }

    @Test
    void completeTerrainBelowEightyFivePercentDropsOutOfTheFirstQueue() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared");
        resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "CREATIVE", 0, 0, fingerprint((short) 120)
            )
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70)
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
        assertTrue(result.candidates().get(0).reasons().contains("terrain_below_threshold"));
    }

    @Test
    void terrainFingerprintRequiresEvidenceCapturedAtTheSavedCenter() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared");
        resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "CREATIVE", 0, 0, fingerprint((short) 70)
            )
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70, 1, 0)
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
        assertTrue(result.candidates().get(0).reasons().contains("terrain_unavailable"));
    }

    @Test
    void terrainMatchCacheDoesNotReuseDifferentEvidenceOrLeakBetweenResolutions() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared", "dimension_type", "overworld");
        final ClientWorldTerrainFingerprint firstTerrain = fingerprint((short) 70, (byte) 0);
        final ClientWorldTerrainFingerprint secondTerrain = fingerprint((short) 70, (byte) 2);
        final ClientWorldProfile first = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, firstTerrain)
        ).profile();
        final ClientWorldProfile second = resolver.resolve(
            SERVER,
            visitObservation(2L, signals, "minecraft_overworld", "SURVIVAL", 1, 0, secondTerrain)
        ).profile();

        final ClientWorldResolution firstResult = resolver.resolve(SERVER, new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), firstTerrain
        ));
        final ClientWorldResolution secondResult = resolver.resolve(SERVER, new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(1, 64, 0), secondTerrain
        ));

        assertEquals(first.id(), firstResult.profile().id());
        assertEquals(second.id(), secondResult.profile().id());
    }

    @Test
    void terrainMatchCacheRequiresDimensionDistanceAndExactEvidence() {
        final ClientWorldPosition cachedPosition = new ClientWorldPosition(0, 64, 0);
        final ClientWorldPosition adjacentDiagonal = new ClientWorldPosition(1, 64, 1);
        final ClientWorldPosition twoBlocksAway = new ClientWorldPosition(2, 64, 0);
        final ClientWorldTerrainFingerprint cachedObserved = fingerprint((short) 70, 0, 0);
        final ClientWorldTerrainFingerprint cachedCandidate = fingerprint((short) 80, 0, 0);
        final ClientWorldTerrainFingerprint sameObservedEvidence = fingerprint((short) 70, 4, -2);
        final ClientWorldTerrainFingerprint sameCandidateEvidence = fingerprint((short) 80, 4, -2);

        assertTrue(ClientWorldProfileResolver.terrainCacheCompatible(
            "minecraft_overworld", cachedPosition, cachedObserved, cachedCandidate,
            "minecraft_overworld", adjacentDiagonal, sameObservedEvidence, sameCandidateEvidence
        ));
        assertFalse(ClientWorldProfileResolver.terrainCacheCompatible(
            "minecraft_overworld", cachedPosition, cachedObserved, cachedCandidate,
            "minecraft_the_nether", adjacentDiagonal, sameObservedEvidence, sameCandidateEvidence
        ));
        assertFalse(ClientWorldProfileResolver.terrainCacheCompatible(
            "minecraft_overworld", cachedPosition, cachedObserved, cachedCandidate,
            "minecraft_overworld", twoBlocksAway, sameObservedEvidence, sameCandidateEvidence
        ));
        assertFalse(ClientWorldProfileResolver.terrainCacheCompatible(
            "minecraft_overworld", cachedPosition, cachedObserved, cachedCandidate,
            "minecraft_overworld", adjacentDiagonal, fingerprint((short) 71), sameCandidateEvidence
        ));
        assertFalse(ClientWorldProfileResolver.terrainCacheCompatible(
            "minecraft_overworld", cachedPosition, cachedObserved, cachedCandidate,
            "minecraft_overworld", adjacentDiagonal, sameObservedEvidence, fingerprint((short) 81)
        ));
    }

    @Test
    void duplicateChunkCoordinatesAreNotACompleteThreeByThreeFingerprint() {
        final List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int index = 0; index < 9; index++) {
            snapshots.add(snapshot(0, 0, (short) 70));
        }

        assertFalse(ClientWorldTerrainFingerprint.from(snapshots, 0, 0).complete());
    }

    @Test
    void positionConfidenceUsesAnExponentAndClampsAtOneThousandTwentyFourBlocks() {
        final double withinOverworldRadius = ClientWorldProfileResolver.positionConfidence(48.0D);
        final double middle = ClientWorldProfileResolver.positionConfidence(512.0D);
        final double far = ClientWorldProfileResolver.positionConfidence(1_023.0D);

        assertEquals(1.0D, ClientWorldProfileResolver.positionConfidence(0.0D));
        assertTrue(withinOverworldRadius > middle && middle > far);
        assertTrue(ClientWorldProfileResolver.positionConfidence(49.0D) > 0.0D);
        assertEquals(0.0D, ClientWorldProfileResolver.positionConfidence(1_024.0D));
        assertEquals(0.0D, ClientWorldProfileResolver.positionConfidence(1_025.0D));
    }

    private static ClientWorldProfileResolver resolver() {
        final List<UUID> ids = List.of(
            UUID.fromString("00000000-0000-0000-0000-000000000001"),
            UUID.fromString("00000000-0000-0000-0000-000000000002"),
            UUID.fromString("00000000-0000-0000-0000-000000000003")
        );
        final int[] index = {0};
        return new ClientWorldProfileResolver(new ClientWorldProfileRegistry(), () -> ids.get(index[0]++));
    }

    private static ClientWorldObservation observation(final long seed, final String brand) {
        return new ClientWorldObservation(
            OptionalLong.of(seed),
            Map.of(
                "brand", brand,
                "commands", "commands-" + brand,
                "dimensions", "dimensions-" + brand
            )
        );
    }

    private static ClientWorldObservation visitObservation(
        final long seed,
        final Map<String, String> signals,
        final String dimension,
        final int x,
        final int z
    ) {
        return visitObservation(seed, signals, dimension, "SURVIVAL", x, z, null);
    }

    private static ClientWorldObservation visitObservation(
        final long seed,
        final Map<String, String> signals,
        final String dimension,
        final String gameMode,
        final int x,
        final int z,
        final ClientWorldTerrainFingerprint terrainFingerprint
    ) {
        return new ClientWorldObservation(
            OptionalLong.of(seed), signals, dimension, gameMode,
            new ClientWorldPosition(x, 64, z), terrainFingerprint
        );
    }

    private static ClientWorldTerrainFingerprint fingerprint(final short height) {
        return fingerprint(height, 0, 0, (byte) 0);
    }

    private static ClientWorldTerrainFingerprint fingerprint(final short height, final byte fluidDepth) {
        return fingerprint(height, 0, 0, fluidDepth);
    }

    private static ClientWorldTerrainFingerprint fingerprint(
        final short height,
        final int centerChunkX,
        final int centerChunkZ
    ) {
        return fingerprint(height, centerChunkX, centerChunkZ, (byte) 0);
    }

    private static ClientWorldTerrainFingerprint fingerprint(
        final short height,
        final int centerChunkX,
        final int centerChunkZ,
        final byte fluidDepth
    ) {
        final List<ChunkSnapshot> snapshots = new ArrayList<>();
        for (int chunkZ = -1; chunkZ <= 1; chunkZ++) {
            for (int chunkX = -1; chunkX <= 1; chunkX++) {
                snapshots.add(snapshot(centerChunkX + chunkX, centerChunkZ + chunkZ, height, fluidDepth));
            }
        }
        return ClientWorldTerrainFingerprint.from(snapshots, centerChunkX, centerChunkZ);
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final short height) {
        return snapshot(chunkX, chunkZ, height, (byte) 0);
    }

    private static ChunkSnapshot snapshot(
        final int chunkX,
        final int chunkZ,
        final short height,
        final byte fluidDepth
    ) {
        final short[] heights = new short[ChunkSnapshot.COLUMNS];
        final String[] biomes = new String[ChunkSnapshot.COLUMNS];
        final byte[] fluids = new byte[ChunkSnapshot.COLUMNS];
        final byte[] kinds = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(heights, height);
        Arrays.fill(biomes, "minecraft:plains");
        Arrays.fill(fluids, fluidDepth);
        Arrays.fill(kinds, (byte) 2);
        return new ChunkSnapshot(
            chunkX, chunkZ, 0L, heights, biomes, fluids,
            new int[ChunkSnapshot.COLUMNS], new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS], kinds, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static ClientWorldObservation seededSignals(final long seed, final String suffix) {
        return new ClientWorldObservation(
            OptionalLong.of(seed),
            Map.of("brand", "brand-" + suffix, "commands", "commands-" + suffix,
                "dimensions", "dimensions-" + suffix)
        );
    }

    private static ClientWorldObservation noSeed(
        final String brand,
        final String commands,
        final String dimensions
    ) {
        return new ClientWorldObservation(
            OptionalLong.empty(),
            Map.of("brand", brand, "commands", commands, "dimensions", dimensions)
        );
    }
}
