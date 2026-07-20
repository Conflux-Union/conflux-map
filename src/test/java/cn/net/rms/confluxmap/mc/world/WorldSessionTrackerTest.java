package cn.net.rms.confluxmap.mc.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
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
}
