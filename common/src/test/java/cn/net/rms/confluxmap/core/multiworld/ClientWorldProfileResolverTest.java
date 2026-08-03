package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        assertEquals(first.profile().id(), resolver.resolve(SERVER, observation(11L, "survival")).profile().id());
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
        final ClientWorldProfile first = new ClientWorldProfile("first", "client-first", "First");
        final ClientWorldProfile competing = new ClientWorldProfile("competing", "client-competing", "Competing");
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

        resolver.select(SERVER, selected.id(), firstWorld);
        final ClientWorldResolution revisited = resolver.resolve(SERVER, firstWorld);

        assertEquals(ClientWorldResolution.State.RESOLVED, revisited.state());
        assertEquals(selected.id(), revisited.profile().id());
    }

    @Test
    void manualCreationMovesTheObservationToTheNewProfile() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldObservation observation = observation(11L, "survival");
        resolver.resolve(SERVER, observation);

        final ClientWorldProfile created = resolver.createAndSelect(SERVER, "Correct world", observation).profile();
        final ClientWorldResolution revisited = resolver.resolve(SERVER, observation);

        assertEquals(ClientWorldResolution.State.RESOLVED, revisited.state());
        assertEquals(created.id(), revisited.profile().id());
    }

    @Test
    void threeLearnedSupportingSignalsCanResolveWhenTheSeedIsUnavailable() {
        final ClientWorldProfileResolver resolver = resolver();
        final ClientWorldProfile first = resolver.resolve(SERVER, observation(11L, "survival")).profile();
        resolver.resolve(SERVER, observation(22L, "creative"));
        final ClientWorldObservation learned = noSeed("vanilla", "commands-a", "dimensions-a");

        resolver.select(SERVER, first.id(), learned);

        assertEquals(first.id(), resolver.resolve(SERVER, learned).profile().id());
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
        assertEquals(profile.id(), resolver.resolve(SERVER, observation).profile().id());
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
        return new ClientWorldObservation(OptionalLong.of(seed), Map.of("brand", brand));
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
