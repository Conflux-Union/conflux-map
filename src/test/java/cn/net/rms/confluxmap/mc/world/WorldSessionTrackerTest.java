package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasRegistry;
import cn.net.rms.confluxmap.core.multiworld.ServerAliasResolver;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WorldSessionTrackerTest {
    @Test
    void pendingHandshakeSuspendsAndThenRecreatesTheSession() {
        final SessionGuard guard = new SessionGuard();
        final WorldIdentity world = WorldIdentity.multiplayer(
            "example.net", "11111111-2222-3333-4444-555555555555"
        );
        final SessionGuard.Session original = guard.begin(world, DimensionId.OVERWORLD);
        final WorldSessionTracker tracker = new WorldSessionTracker(guard, new CompanionSession());
        final List<SessionGuard.Session> events = new ArrayList<>();
        tracker.addListener(events::add);

        tracker.updateSession(Optional.empty(), DimensionId.OVERWORLD);

        assertFalse(guard.current().active());
        assertEquals(1, events.size());
        assertFalse(events.get(0).active());

        tracker.updateSession(Optional.of(world), DimensionId.OVERWORLD);

        assertTrue(guard.current().active());
        assertEquals(world, guard.current().world());
        assertNotEquals(original.token(), guard.current().token());
        assertEquals(2, events.size());
        assertEquals(guard.current(), events.get(1));
    }

    @Test
    void withoutAnAliasResolverTheTypedAddressIsUsedVerbatim() {
        final WorldSessionTracker tracker = new WorldSessionTracker(
            new SessionGuard(), new CompanionSession()
        );

        assertEquals("MC.Example.com", tracker.canonicalAddress("MC.Example.com"));
    }

    @Test
    void aSecondAddressOfTheSameCompanionServerResolvesToTheFirstNamespace() {
        final Set<String> storage = new HashSet<>();
        final ServerAliasResolver aliases = new ServerAliasResolver(
            new ServerAliasRegistry(), storage::contains, () -> { }
        );
        final WorldSessionTracker tracker = new WorldSessionTracker(
            new SessionGuard(), activeCompanion(), null, aliases
        );

        final String byHostname = tracker.canonicalAddress("mc.example.com");
        storage.add(byHostname);

        assertEquals("mc.example.com", byHostname);
        assertEquals(byHostname, tracker.canonicalAddress("192.0.2.10"));
        assertEquals(byHostname, tracker.canonicalAddress("play.example.com:25565"));
    }

    @Test
    void withNoCompanionTwoAddressesKeepTheirOwnNamespaces() {
        final ServerAliasResolver aliases = new ServerAliasResolver(
            new ServerAliasRegistry(), storageId -> false, () -> { }
        );
        final WorldSessionTracker tracker = new WorldSessionTracker(
            new SessionGuard(), new CompanionSession(), null, aliases
        );

        assertNotEquals(
            tracker.canonicalAddress("mc.example.com"),
            tracker.canonicalAddress("192.0.2.10")
        );
    }

    private static CompanionSession activeCompanion() {
        final CompanionSession companion = new CompanionSession();
        companion.onHelloSent();
        companion.onPolicy(new HelloPolicyS2C(
            new HelloPolicyS2C.Flags(false, true, false, false, false, false, false, false),
            "11111111-2222-3333-4444-555555555555",
            "1.17.1",
            new HelloPolicyS2C.Budgets(65_536, 8, 300, 4),
            List.of()
        ));
        return companion;
    }

    @Test
    void explicitEndNotifiesListenersExactlyOnce() {
        final SessionGuard guard = new SessionGuard();
        guard.begin(WorldIdentity.singleplayer("world"), DimensionId.OVERWORLD);
        final WorldSessionTracker tracker = new WorldSessionTracker(guard, new CompanionSession());
        final List<SessionGuard.Session> events = new ArrayList<>();
        tracker.addListener(events::add);

        tracker.endSession();
        tracker.endSession();

        assertFalse(guard.current().active());
        assertEquals(List.of(SessionGuard.Session.NONE), events);
    }
}
