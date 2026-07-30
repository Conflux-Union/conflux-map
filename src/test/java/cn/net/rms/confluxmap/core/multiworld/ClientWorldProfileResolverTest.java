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
