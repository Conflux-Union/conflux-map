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
        assertFalse(transitional.candidates().isEmpty());
        assertTrue(transitional.candidates().get(0).blockers().contains("same_seed_proxy_transition"));
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
        assertEquals(2, collision.candidates().size());
        assertTrue(collision.candidates().stream().allMatch(candidate ->
            candidate.blockers().contains("same_seed_requires_discriminator")
        ));
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
    void manualCreationKeepsSameSeedMembershipOnBothProfiles() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation observation = observation(11L, "survival");
        resolver.resolve(SERVER, observation);

        final ClientWorldResolution createdResolution = resolver.createAndSelect(
            SERVER, "Correct world", observation
        );
        final ClientWorldResolution revisited = resolver.resolve(SERVER, observation);

        assertEquals(ClientWorldResolution.State.RESOLVED, createdResolution.state());
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, revisited.state());
        assertEquals(2, resolver.profileCountWithSeed(SERVER, 11L));
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
    void velocityServerNameSeparatesAndRevisitsSameSeedBackends() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation shared = observation(11L, "velocity");

        final ClientWorldProfile survival = resolver.resolveVelocityServer(
            SERVER, "Survival", shared, null, false
        ).profile();
        final ClientWorldProfile creative = resolver.resolveVelocityServer(
            SERVER, "Creative", shared, null, false
        ).profile();

        assertNotEquals(survival.id(), creative.id());
        assertEquals("survival", survival.velocityServerName().orElseThrow());
        assertEquals("creative", creative.velocityServerName().orElseThrow());
        assertEquals(survival.id(), resolver.resolveVelocityServer(
            SERVER, "SURVIVAL", shared, null, false
        ).profile().id());
    }

    @Test
    void firstVelocityQueryCanAttachToTheUniqueSeedBoundLegacyProfile() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation observation = observation(11L, "velocity");
        final ClientWorldProfile legacy = resolver.resolve(SERVER, observation).profile();

        final ClientWorldResolution resolved = resolver.resolveVelocityServer(
            SERVER, "survival", observation, null, true
        );

        assertEquals(legacy.id(), resolved.profile().id());
        assertEquals("survival", resolved.profile().velocityServerName().orElseThrow());
        assertEquals("survival", resolved.profile().displayName());
        assertEquals(1, resolver.profiles(SERVER).size());
    }

    @Test
    void velocityServerNameBecomesTheManagedWorldName() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation observation = observation(11L, "velocity");

        final ClientWorldProfile profile = resolver.resolveVelocityServer(
            SERVER, "survival", observation, null, false
        ).profile();

        assertEquals("survival", profile.displayName());
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
        final ClientWorldResolution outsideResult = resolver.resolve(SERVER, outsideBoundary);
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, outsideResult.state());

        final ClientWorldObservation atBoundary = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(48, 64, 0), fingerprint((short) 70)
        );
        final ClientWorldResolution atBoundaryResult = resolver.resolve(SERVER, atBoundary);
        assertTrue(atBoundaryResult.state() == ClientWorldResolution.State.RESOLVED
            ? atBoundaryResult.profile().id().equals(near.id())
            : atBoundaryResult.candidates().get(0).profileId().equals(near.id()));
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
        final ClientWorldResolution atBoundaryResult = resolver.resolve(SERVER, atBoundary);
        assertEquals(ClientWorldResolution.State.AMBIGUOUS, atBoundaryResult.state());
        assertEquals(near.id(), atBoundaryResult.candidates().get(0).profileId());
    }

    @Test
    void customDimensionContainingNetherUsesTheDefaultFortyEightBlockRadius() {
        assertEquals(48.0D, ClientWorldProfileResolver.positionRadius("example_netherish"));
        assertEquals(6.0D, ClientWorldProfileResolver.positionRadius("minecraft_the_nether"));
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
    void uniqueSeedWithVisitContextCanRestoreProvisionallyWithoutTerrain() {
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

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(result.provisional());
        assertEquals(profile.id(), result.profile().id());
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
    void optionalTerrainCanDisambiguateOtherwiseSimilarAuxiliaryEvidence() {
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

        assertNotEquals(auxiliaryWinner.id(), resolver.resolve(SERVER, current).profile().id());
    }

    @Test
    void candidatesWithinThreePercentagePointsRequireManualSelection() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile first = new ClientWorldProfile("first", "world", "First");
        final ClientWorldProfile second = new ClientWorldProfile(
            "second", "client-00000000-0000-0000-0000-000000000002", "Second"
        );
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, UUID::randomUUID);
        final Map<String, String> signals = Map.of(
            "brand", "shared",
            "dimension_type", "overworld"
        );
        first.bind(visitObservation(
            1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70)
        ));
        second.bind(visitObservation(
            2L, signals, "minecraft_overworld", "SURVIVAL", 1, 0, fingerprint((short) 70)
        ));
        registry.mutableProfiles(SERVER).addAll(List.of(first, second));
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70)
        );

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, resolver.resolve(SERVER, current).state());
    }

    @Test
    void weakTerrainDoesNotStablyConfirmAnOtherwiseContinuousCandidate() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared");
        resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "CREATIVE", 0, 0, fingerprint((short) 72)
            )
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 70)
        );

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(result.provisional());
    }

    @Test
    void provisionalRecoveryConfirmsAndLearnsOnlyAfterMatchingTerrainArrives() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared");
        final ClientWorldTerrainFingerprint saved = fingerprint((short) 72);
        final ClientWorldProfile profile = resolver.resolve(
            SERVER,
            visitObservation(1L, signals, "minecraft_overworld", "CREATIVE", 0, 0, saved)
        ).profile();

        final ClientWorldResolution provisional = resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0,
                fingerprint((short) 70)
            )
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, provisional.state());
        assertTrue(provisional.provisional());
        assertEquals("CREATIVE", profile.visit("minecraft_overworld").gameMode());

        final ClientWorldResolution confirmed = resolver.resolve(
            SERVER,
            visitObservation(
                1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, saved
            )
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, confirmed.state());
        assertFalse(confirmed.provisional());
        assertEquals("SURVIVAL", resolver.profiles(SERVER).get(0)
            .visit("minecraft_overworld").gameMode());
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

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(result.provisional());
    }

    @Test
    void terrainMatchCacheDoesNotReuseDifferentEvidenceOrLeakBetweenResolutions() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile first = new ClientWorldProfile("first", "world", "First");
        final ClientWorldProfile second = new ClientWorldProfile(
            "second", "client-00000000-0000-0000-0000-000000000002", "Second"
        );
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, UUID::randomUUID);
        final Map<String, String> signals = Map.of("brand", "shared", "dimension_type", "overworld");
        final ClientWorldTerrainFingerprint firstTerrain = fingerprint((short) 70);
        final ClientWorldTerrainFingerprint secondTerrain = fingerprint((short) 120);
        first.bind(visitObservation(
            1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, firstTerrain
        ));
        second.bind(visitObservation(
            2L, signals, "minecraft_overworld", "SURVIVAL", 1, 0, secondTerrain
        ));
        registry.mutableProfiles(SERVER).addAll(List.of(first, second));

        final ClientWorldResolution firstResult = resolver.resolve(SERVER, new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), firstTerrain
        ));
        final ClientWorldResolution secondResult = resolver.resolve(SERVER, new ClientWorldObservation(
            OptionalLong.empty(), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(1, 64, 0), secondTerrain
        ));

        assertEquals(ClientWorldResolution.State.RESOLVED, firstResult.state());
        assertEquals(first.id(), firstResult.profile().id());
        assertEquals(ClientWorldResolution.State.RESOLVED, secondResult.state());
        assertEquals(second.id(), secondResult.profile().id());
    }

    @Test
    void proxySwitchDoesNotLetOldStableProfileBeatTheExactCurrentTrajectory() {
        final long seed = 77L;
        final Map<String, String> signals = Map.of(
            "brand", "shared", "commands", "shared", "dimensions", "shared",
            "dimension_type", "overworld", "spawn", "shared"
        );
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile expected = new ClientWorldProfile("a", "world", "same-seed-a");
        final ClientWorldProfile oldStable = new ClientWorldProfile(
            "b", "client-00000000-0000-0000-0000-000000000002", "same-seed-b"
        );
        final ClientWorldTerrainFingerprint terrainA = fingerprint((short) 70, 1, -10);
        final ClientWorldTerrainFingerprint terrainB = fingerprint((short) 72, 1, -14);
        expected.bind(trajectoryTerrainObservation(
            seed, signals, 22.5D, 76.0D, -153.5D, 1_000L, 500L, 13L, terrainA
        ));
        oldStable.bind(trajectoryTerrainObservation(
            seed, signals, 25.999D, 84.99D, -214.67D, 2_000L, 500L, 14L, terrainB
        ));
        registry.mutableProfiles(SERVER).addAll(List.of(expected, oldStable));
        registry.setLastStableProfile(SERVER, oldStable.id(), 2_000L, 14L);
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );
        final ClientWorldTrajectory currentTrajectory = new ClientWorldTrajectory();
        currentTrajectory.append(ClientWorldTrajectorySample.observed(
            22.5D, 76.0D, -153.5D, 0.0D, 0.0D, -145.2D, 3.75D,
            400_000L, 8_000L, "minecraft_overworld", 8_000L, 2_000L, 15L
        ));
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(seed), signals, "minecraft_overworld", "CREATIVE",
            new ClientWorldPosition(22, 76, -154), null,
            Map.of(expected.id(), terrainA, oldStable.id(), terrainB), currentTrajectory
        );

        final ClientWorldResolution result = resolver.resolveAfterProxyWorldJoin(
            SERVER, OptionalLong.of(seed), current
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(result.provisional());
        assertEquals(expected.id(), result.profile().id());
        final ClientWorldResolution.Candidate expectedCandidate = result.candidates().stream()
            .filter(candidate -> candidate.profileId().equals(expected.id())).findFirst().orElseThrow();
        final ClientWorldResolution.Candidate oldStableCandidate = result.candidates().stream()
            .filter(candidate -> candidate.profileId().equals(oldStable.id())).findFirst().orElseThrow();
        assertTrue(expectedCandidate.confidencePercent() > oldStableCandidate.confidencePercent());
        assertTrue(expectedCandidate.factors().stream().anyMatch(factor ->
            factor.key().equals("trajectory") && factor.rawScore() == 1.0D
        ));
        assertTrue(oldStableCandidate.factors().stream().anyMatch(factor ->
            factor.key().equals("last_stable")
                && factor.availability() == ClientWorldResolution.FactorAvailability.UNAVAILABLE
        ));
    }

    @Test
    void proxySwitchUsesProfileOwnedCheckpointForUnsavedDimensionAndExcludesDepartedProfile() {
        final long seed = 77L;
        final Map<String, String> stableSignals = Map.of(
            "brand", "shared", "commands", "shared"
        );
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile arriving = new ClientWorldProfile("a", "world", "same-seed-a");
        final ClientWorldProfile departed = new ClientWorldProfile(
            "b", "client-00000000-0000-0000-0000-000000000002", "same-seed-b"
        );
        arriving.bind(visitObservation(
            seed, stableSignals, "minecraft_overworld", "CREATIVE", 30, -157, null
        ));
        departed.bind(visitObservation(
            seed, stableSignals, "minecraft_the_nether", "CREATIVE", 6, -33, null
        ));
        registry.mutableProfiles(SERVER).addAll(List.of(arriving, departed));
        registry.setLastStableProfile(SERVER, departed.id(), 2_000L, 3L);
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );

        final ClientWorldTrajectory arrivingCheckpoint = trajectoryAt(
            4.27D, 74.0D, -26.73D, 1_000L, "minecraft_the_nether", 2L
        );
        final ClientWorldTrajectory departedCheckpoint = trajectoryAt(
            6.94D, 91.0D, -33.0D, 2_000L, "minecraft_the_nether", 3L
        );
        final ClientWorldTrajectory currentTrajectory = trajectoryAt(
            4.27D, 74.0D, -26.73D, 3_000L, "minecraft_the_nether", 4L
        );
        final ClientWorldObservation current = new ClientWorldObservation(
            OptionalLong.of(seed), stableSignals, "minecraft_the_nether", "CREATIVE",
            new ClientWorldPosition(4, 74, -27), null, Map.of(), currentTrajectory,
            Map.of(arriving.id(), arrivingCheckpoint, departed.id(), departedCheckpoint)
        );

        final ClientWorldResolution result = resolver.resolveAfterProxyWorldJoin(
            SERVER, OptionalLong.of(seed), current, departed.id()
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(result.provisional());
        assertEquals(arriving.id(), result.profile().id());
        final ClientWorldResolution.Candidate arrivingCandidate = result.candidates().stream()
            .filter(candidate -> candidate.profileId().equals(arriving.id())).findFirst().orElseThrow();
        final ClientWorldResolution.Candidate departedCandidate = result.candidates().stream()
            .filter(candidate -> candidate.profileId().equals(departed.id())).findFirst().orElseThrow();
        assertTrue(arrivingCandidate.reasons().contains("candidate_dimension_checkpoint"));
        assertTrue(departedCandidate.reasons().contains("departed_profile_boundary"));
        assertTrue(departedCandidate.factors().stream().anyMatch(factor ->
            factor.key().equals("proxy_boundary") && factor.conflict()
        ));
    }

    @Test
    void oldCoordinateInCurrentDimensionCannotCompeteWithProfilesSoleLatestDimension() {
        final long seed = 77L;
        final Map<String, String> signals = Map.of("brand", "shared", "commands", "shared");
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile netherLatest = new ClientWorldProfile("a", "world", "same-seed-a");
        final ClientWorldProfile overworldLatest = new ClientWorldProfile(
            "b", "client-00000000-0000-0000-0000-000000000002", "same-seed-b"
        );
        netherLatest.bind(visitObservation(
            seed, signals, "minecraft_overworld", "CREATIVE", 22, -154, null
        ));
        netherLatest.bind(visitObservation(
            seed, signals, "minecraft_the_nether", "CREATIVE", 4, -27, null
        ));
        overworldLatest.bind(visitObservation(
            seed, signals, "minecraft_overworld", "CREATIVE", 26, -303, null
        ));
        registry.mutableProfiles(SERVER).addAll(List.of(netherLatest, overworldLatest));
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );

        final ClientWorldResolution result = resolver.resolve(
            SERVER, visitObservation(
                seed, signals, "minecraft_overworld", "CREATIVE", 26, -303, null
            )
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertEquals(overworldLatest.id(), result.profile().id());
        final ClientWorldResolution.Candidate stale = result.candidates().stream()
            .filter(candidate -> candidate.profileId().equals(netherLatest.id()))
            .findFirst().orElseThrow();
        assertTrue(stale.conflicted());
        assertTrue(stale.reasons().contains("last_dimension_mismatch"));
        assertTrue(stale.factors().stream().anyMatch(factor ->
            factor.key().equals("latest_dimension") && factor.conflict()
        ));
    }

    @Test
    void lastStableTrajectoryCanRestoreWithoutTerrainButDoesNotPersistUntilConfirmed() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile expected = new ClientWorldProfile("expected", "world", "Expected");
        final ClientWorldProfile other = new ClientWorldProfile(
            "other", "client-00000000-0000-0000-0000-000000000002", "Other"
        );
        final Map<String, String> signals = Map.of("brand", "shared", "dimension_type", "overworld");
        expected.bind(trajectoryObservation(1L, signals, 100, 1_000L));
        other.bind(trajectoryObservation(1L, signals, 700, 1_000L));
        registry.mutableProfiles(SERVER).addAll(List.of(expected, other));
        registry.setLastStableProfile(SERVER, expected.id(), 1_000L, 1L);
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(registry, UUID::randomUUID);
        final ClientWorldObservation current = trajectoryObservation(1L, signals, 120, 2_000L);

        final ClientWorldResolution result = resolver.resolve(SERVER, current);

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertTrue(result.provisional());
        assertEquals(expected.id(), result.profile().id());
        assertEquals(100, resolver.profiles(SERVER).get(0).visit("minecraft_overworld")
            .lastPosition().x());
    }

    @Test
    void explicitTerrainMismatchBlocksAutomaticRecovery() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "shared", "dimension_type", "overworld");
        resolver.resolve(SERVER, visitObservation(
            1L, signals, "minecraft_overworld", "SURVIVAL", 0, 0, fingerprint((short) 70)
        ));

        final ClientWorldResolution result = resolver.resolve(SERVER, new ClientWorldObservation(
            OptionalLong.of(1L), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(0, 64, 0), fingerprint((short) 140)
        ));

        assertEquals(ClientWorldResolution.State.AMBIGUOUS, result.state());
        assertTrue(result.candidates().get(0).conflicted());
        final ClientWorldResolution.Candidate candidate = result.candidates().get(0);
        assertTrue(candidate.factors().stream().anyMatch(factor ->
            factor.key().equals("terrain") && factor.veto()
        ));
    }

    @Test
    void defaultPortAliasMergePreservesProfilesAndTheirPhysicalStorageOwners() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfile canonical = new ClientWorldProfile("canonical", "world", "Canonical");
        final ClientWorldProfile legacy = new ClientWorldProfile(
            "legacy", "world", "Legacy explicit port"
        );
        registry.mutableProfiles("proxy.example.net").add(canonical);
        registry.mutableProfiles("proxy.example.net_25565").add(legacy);
        final ClientWorldProfileResolver resolver = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );

        final ClientWorldProfileResolver.ServerAliasResult result = resolver.adoptServerAliases(
            "proxy.example.net", List.of("proxy.example.net_25565")
        );

        assertTrue(result.applied());
        assertTrue(result.changed());
        assertEquals(2, resolver.profiles("proxy.example.net").size());
        assertEquals("proxy.example.net", resolver.profiles("proxy.example.net").stream()
            .filter(profile -> profile.id().equals("canonical")).findFirst().orElseThrow()
            .storageServerId("proxy.example.net"));
        assertEquals("proxy.example.net_25565", resolver.profiles("proxy.example.net").stream()
            .filter(profile -> profile.id().equals("legacy")).findFirst().orElseThrow()
            .storageServerId("proxy.example.net"));
        assertTrue(resolver.profiles("proxy.example.net_25565").isEmpty());
    }

    @Test
    void candidateDiagnosticsExposeDynamicWeightsContributionsAndMissingTerrain() {
        final ClientWorldProfileResolver resolver = resolver();
        final Map<String, String> signals = Map.of("brand", "stable", "commands", "stable");
        resolver.resolve(SERVER, trajectoryObservation(1L, signals, 100, 1_000L));

        final ClientWorldResolution result = resolver.resolve(
            SERVER, trajectoryObservation(1L, signals, 120, 2_000L)
        );

        assertEquals(ClientWorldResolution.State.RESOLVED, result.state());
        assertFalse(result.candidates().isEmpty());
        final ClientWorldResolution.Candidate candidate = result.candidates().get(0);
        assertTrue(candidate.scored());
        assertTrue(candidate.requiredConfidencePercent() > 0);
        assertTrue(candidate.factors().stream().anyMatch(factor ->
            factor.key().equals("trajectory")
                && factor.availability() == ClientWorldResolution.FactorAvailability.AVAILABLE
                && factor.effectiveWeight() > 0.0D
                && factor.contribution() > 0.0D
        ));
        assertTrue(candidate.factors().stream().anyMatch(factor ->
            factor.key().equals("terrain")
                && factor.availability() == ClientWorldResolution.FactorAvailability.UNAVAILABLE
                && factor.effectiveWeight() == 0.0D
        ));
        final double contributions = candidate.factors().stream()
            .mapToDouble(ClientWorldResolution.Factor::contribution).sum();
        assertEquals(candidate.confidencePercent(), (int) Math.round(contributions * 100.0D));
    }

    @Test
    void failedTrajectoryCheckpointDoesNotPublishPositionOrLastStableMutation() {
        final ClientWorldProfileRegistry registry = new ClientWorldProfileRegistry();
        final ClientWorldProfileResolver initial = new ClientWorldProfileResolver(
            registry, UUID::randomUUID
        );
        final Map<String, String> signals = Map.of("brand", "stable", "commands", "stable");
        final ClientWorldProfile profile = initial.resolve(
            SERVER, trajectoryObservation(1L, signals, 10, 1_000L)
        ).profile();
        final ClientWorldProfileResolver failing = new ClientWorldProfileResolver(
            registry, UUID::randomUUID,
            ignored -> ClientWorldProfileIo.SaveResult.failure("disk full")
        );

        final ClientWorldProfileResolver.MutationResult result = failing.rememberVisit(
            SERVER, profile.id(), trajectoryObservation(1L, signals, 200, 2_000L)
        );

        assertFalse(result.applied());
        assertEquals(10, registry.profiles(SERVER).get(0).visit("minecraft_overworld")
            .lastPosition().x());
        assertEquals(profile.id(), registry.lastStableProfileId(SERVER));
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
        assertEquals(
            ClientWorldProfileResolver.positionConfidence(48.0D),
            ClientWorldProfileResolver.corridorPositionConfidence(48.0D, 48.0D)
        );
        assertTrue(ClientWorldProfileResolver.corridorPositionConfidence(48.0D, 48.0D)
            > ClientWorldProfileResolver.corridorPositionConfidence(49.0D, 48.0D));
        assertTrue(ClientWorldProfileResolver.corridorPositionConfidence(1_023.0D, 48.0D) > 0.0D);
        assertEquals(0.0D, ClientWorldProfileResolver.corridorPositionConfidence(1_024.0D, 48.0D));
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

    private static ClientWorldObservation trajectoryObservation(
        final long seed,
        final Map<String, String> signals,
        final int x,
        final long timeMs
    ) {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(ClientWorldTrajectorySample.observed(
            x, 64, 0, 20, 0, -90, 0, timeMs, timeMs / 50L,
            "minecraft_overworld", timeMs / 50L, ClientWorldTrajectorySample.NO_SERVER_ACK, 1L
        ));
        return new ClientWorldObservation(
            OptionalLong.of(seed), signals, "minecraft_overworld", "SURVIVAL",
            new ClientWorldPosition(x, 64, 0), null, Map.of(), trajectory
        );
    }

    private static ClientWorldTrajectory trajectoryAt(
        final double x,
        final double y,
        final double z,
        final long timeMs,
        final String dimension,
        final long connectionGeneration
    ) {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(ClientWorldTrajectorySample.observed(
            x, y, z, 0.0D, 0.0D, 0.0D, 0.0D, timeMs, timeMs / 50L,
            dimension, timeMs / 50L, ClientWorldTrajectorySample.NO_SERVER_ACK,
            connectionGeneration
        ));
        return trajectory;
    }

    private static ClientWorldObservation trajectoryTerrainObservation(
        final long seed,
        final Map<String, String> signals,
        final double x,
        final double y,
        final double z,
        final long timeMs,
        final long correctionTimeMs,
        final long connectionGeneration,
        final ClientWorldTerrainFingerprint terrain
    ) {
        final ClientWorldTrajectory trajectory = new ClientWorldTrajectory();
        trajectory.append(ClientWorldTrajectorySample.observed(
            x, y, z, 0.0D, 0.0D, 0.0D, 0.0D, timeMs, timeMs / 50L,
            "minecraft_overworld", timeMs / 50L, correctionTimeMs, connectionGeneration
        ));
        return new ClientWorldObservation(
            OptionalLong.of(seed), signals, "minecraft_overworld", "CREATIVE",
            new ClientWorldPosition((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)),
            terrain, Map.of(), trajectory
        );
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
