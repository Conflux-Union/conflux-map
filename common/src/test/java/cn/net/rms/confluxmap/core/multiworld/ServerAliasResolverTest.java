package cn.net.rms.confluxmap.core.multiworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ServerAliasResolverTest {
    private static final String WORLD_UUID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";
    private static final String OTHER_WORLD_UUID = "0b3d5f1a-1111-2222-3333-444455556666";
    private static final String INSTANCE = "aaaaaaaa-0000-0000-0000-000000000000";
    private static final String OTHER_INSTANCE = "bbbbbbbb-0000-0000-0000-000000000000";

    private final Set<String> storage = new HashSet<>();
    private final ServerAliasRegistry registry = new ServerAliasRegistry();
    private int saves;

    @Test
    void certainlyEquivalentSpellingsShareOneNamespaceWithoutAnyCompanion() {
        final ServerAliasResolver resolver = resolver();

        final String first = resolver.resolve("MC.Example.com");
        final String second = resolver.resolve("mc.example.com.");
        final String third = resolver.resolve("  mc.example.com  ");

        assertEquals("mc.example.com", first);
        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    void unrelatedAddressesStayApartWithoutEvidence() {
        final ServerAliasResolver resolver = resolver();

        assertNotEquals(resolver.resolve("mc.example.com"), resolver.resolve("192.0.2.10"));
    }

    @Test
    void aDifferentPortIsADifferentServer() {
        final ServerAliasResolver resolver = resolver();

        assertNotEquals(
            resolver.resolve("mc.example.com"),
            resolver.resolve("mc.example.com:25566")
        );
    }

    /**
     * An explicit default port skips the SRV lookup a bare host performs, so the two spellings may
     * be different machines. They merge on companion evidence, never on the spelling alone.
     */
    @Test
    void anExplicitDefaultPortIsNotMergedOnSpellingAlone() {
        final ServerAliasResolver resolver = resolver();
        final String bare = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(bare);

        assertNotEquals(bare, resolver.resolve("mc.example.com:25565", null, null).canonicalId());
    }

    @Test
    void anExplicitDefaultPortStillMergesOnCompanionEvidence() {
        final ServerAliasResolver resolver = resolver();
        final String bare = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(bare);

        assertEquals(bare, resolver.resolve("mc.example.com:25565", INSTANCE, WORLD_UUID).canonicalId());
    }

    @Test
    void existingUnnormalizedDataKeepsOwningItsDirectory() {
        storage.add("mc.example.com.");
        final ServerAliasResolver resolver = resolver();

        final ServerAliasResolver.Resolution resolution = resolver.resolve("mc.example.com.", null, null);

        assertEquals(ServerAliasResolver.Origin.ADOPTED_LEGACY, resolution.origin());
        assertEquals("mc.example.com.", resolution.canonicalId());
        assertEquals("mc.example.com.", resolver.resolve("mc.example.com"));
    }

    @Test
    void aSecondAddressOfACompanionServerJoinsTheExistingNamespace() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);

        final ServerAliasResolver.Resolution byIp = resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.LEARNED, byIp.origin());
        assertEquals(canonical, byIp.canonicalId());
        assertEquals(ServerAliasResolver.Origin.KNOWN, resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID).origin());
    }

    @Test
    void learningNeverSilentlyMergesTwoDirectoriesThatBothHoldData() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);
        storage.add("192.0.2.10");

        final ServerAliasResolver.Resolution byIp = resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.CONFLICT, byIp.origin());
        assertEquals("192.0.2.10", byIp.canonicalId());
        assertEquals(canonical, byIp.mergeTarget());
    }

    @Test
    void aDifferentCompanionInstanceDoesNotJoinAnotherServersNamespace() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();

        final ServerAliasResolver.Resolution other = resolver.resolve("192.0.2.10", OTHER_INSTANCE, OTHER_WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.NEW, other.origin());
        assertNotEquals(canonical, other.canonicalId());
    }

    @Test
    void anExplicitLinkMergesServersThatRunNoCompanion() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");

        resolver.link(canonical, "192.0.2.10");

        assertEquals(canonical, resolver.resolve("192.0.2.10"));
        assertTrue(resolver.addresses(canonical).contains("192.0.2.10"));
    }

    @Test
    void linkingAnAddressThatOwnsDataFailsLoudlyInsteadOfDoingNothing() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");
        resolver.resolve("192.0.2.10");

        assertThrows(IllegalArgumentException.class, () -> resolver.link(canonical, "192.0.2.10"));
        assertNotEquals(canonical, resolver.resolve("192.0.2.10"));
    }

    @Test
    void absorbingRedirectsEveryAddressOfTheMigratedNamespace() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");
        final String other = resolver.resolve("192.0.2.10");
        resolver.link(other, "backup.example.com");

        resolver.absorb(canonical, "192.0.2.10");

        assertEquals(canonical, resolver.resolve("192.0.2.10"));
        assertEquals(canonical, resolver.resolve("backup.example.com"));
        assertTrue(resolver.canonicalIds().contains(canonical));
        assertTrue(!resolver.canonicalIds().contains(other));
    }

    @Test
    void absorbingCarriesTheCompanionWorldSoLaterAddressesLearnFromIt() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");
        final String other = resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);
        storage.add(other);

        resolver.absorb(canonical, "192.0.2.10");

        assertEquals(canonical, resolver.resolve("play.example.com", INSTANCE, WORLD_UUID).canonicalId());
    }

    @Test
    void unlinkingRestoresTheAddressToItsOwnNamespace() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);
        resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);

        resolver.unlink("192.0.2.10");

        assertEquals("192.0.2.10", resolver.resolve("192.0.2.10"));
    }

    @Test
    void aDetachedAddressIsNotSilentlyRelearnedOnTheNextConnection() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);
        resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);
        resolver.unlink("192.0.2.10");

        final ServerAliasResolver.Resolution again = resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.NEW, again.origin());
        assertEquals("192.0.2.10", again.canonicalId());
    }

    @Test
    void anExplicitRelinkOverridesAnEarlierDetach() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);
        resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);
        resolver.unlink("192.0.2.10");

        resolver.link(canonical, "192.0.2.10");

        assertEquals(canonical, resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID).canonicalId());
    }

    @Test
    void theCanonicalAddressCannotBeUnlinkedFromItsOwnData() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");

        assertThrows(IllegalArgumentException.class, () -> resolver.unlink(canonical));
    }

    @Test
    void repeatedResolutionOfAKnownAddressPersistsNothingNew() {
        final ServerAliasResolver resolver = resolver();
        resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID);
        final int afterFirst = saves;

        resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID);
        resolver.resolve("MC.EXAMPLE.COM.", INSTANCE, WORLD_UUID);

        assertEquals(afterFirst, saves);
    }

    @Test
    void inspectDistinguishesTheFourThingsAnAddressCanBeDoing() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");
        resolver.link(canonical, "play.example.com");
        final String other = resolver.resolve("other.example.com");
        resolver.link(other, "backup.example.com");
        storage.add("192.0.2.10");

        assertEquals(
            ServerAliasResolver.AddressState.LINKED,
            resolver.inspect(canonical, "play.example.com").state()
        );
        assertEquals(
            ServerAliasResolver.AddressState.FREE,
            resolver.inspect(canonical, "new.example.com").state()
        );
        assertEquals(
            ServerAliasResolver.AddressState.TAKEN,
            resolver.inspect(canonical, "backup.example.com").state()
        );
        assertEquals(other, resolver.inspect(canonical, "backup.example.com").owner());
        assertEquals(
            ServerAliasResolver.AddressState.HOLDS_DATA,
            resolver.inspect(canonical, "other.example.com").state()
        );
        assertEquals(
            ServerAliasResolver.AddressState.HOLDS_DATA,
            resolver.inspect(canonical, "192.0.2.10").state()
        );
    }

    @Test
    void aDetachedAddressIsListedAndCanBeLinkedBackExplicitly() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);
        resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);
        resolver.unlink("192.0.2.10");

        assertEquals(List.of("192.0.2.10"), resolver.detachedAddresses(canonical));
        assertEquals(
            ServerAliasResolver.AddressState.FREE,
            resolver.inspect(canonical, "192.0.2.10").state()
        );

        resolver.link(canonical, "192.0.2.10");

        assertTrue(resolver.detachedAddresses(canonical).isEmpty());
        assertEquals(canonical, resolver.resolve("192.0.2.10"));
    }

    @Test
    void companionWorldsAreNumberedInTheOrderTheyWereSeen() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        resolver.resolve("mc.example.com", INSTANCE, OTHER_WORLD_UUID);

        assertEquals(1, resolver.worldOrdinal(canonical, WORLD_UUID));
        assertEquals(2, resolver.worldOrdinal(canonical, OTHER_WORLD_UUID));
    }

    @Test
    void anUnrecordedWorldHasNoOrdinal() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com");

        assertEquals(0, resolver.worldOrdinal(canonical, WORLD_UUID));
    }

    /**
     * A mirror server synced from a survival server carries the survival world's UUID inside the
     * copied save, and behind one proxy both are reached at the same address. Only the instance id
     * distinguishes them, so merging must never key on the world.
     */
    @Test
    void twoInstancesSharingACopiedWorldNeverMerge() {
        final ServerAliasResolver resolver = resolver();
        final String survival =
            resolver.resolve("play.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(survival);

        final ServerAliasResolver.Resolution mirror =
            resolver.resolve("mirror.example.com", OTHER_INSTANCE, WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.NEW, mirror.origin());
        assertNotEquals(survival, mirror.canonicalId());
    }

    @Test
    void aSecondAddressOfOneInstanceJoinsItsNamespace() {
        final ServerAliasResolver resolver = resolver();
        final String canonical =
            resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);

        final ServerAliasResolver.Resolution byIp =
            resolver.resolve("192.0.2.10", INSTANCE, WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.LEARNED, byIp.origin());
        assertEquals(canonical, byIp.canonicalId());
    }

    /** An instance that hosts a different world over time is still the same server. */
    @Test
    void oneInstanceChangingItsWorldKeepsItsNamespace() {
        final ServerAliasResolver resolver = resolver();
        final String canonical =
            resolver.resolve("mc.example.com", INSTANCE, WORLD_UUID).canonicalId();
        storage.add(canonical);

        assertEquals(
            canonical,
            resolver.resolve("192.0.2.10", INSTANCE, OTHER_WORLD_UUID).canonicalId()
        );
    }

    /** Servers predating the capability advertise no instance id and must not merge on the world. */
    @Test
    void withoutAnInstanceIdAddressesStayApartEvenOnAMatchingWorld() {
        final ServerAliasResolver resolver = resolver();
        final String canonical = resolver.resolve("mc.example.com", null, WORLD_UUID).canonicalId();
        storage.add(canonical);

        final ServerAliasResolver.Resolution byIp = resolver.resolve("192.0.2.10", null, WORLD_UUID);

        assertEquals(ServerAliasResolver.Origin.NEW, byIp.origin());
        assertNotEquals(canonical, byIp.canonicalId());
    }

    private ServerAliasResolver resolver() {
        return new ServerAliasResolver(registry, storage::contains, () -> saves++);
    }
}
