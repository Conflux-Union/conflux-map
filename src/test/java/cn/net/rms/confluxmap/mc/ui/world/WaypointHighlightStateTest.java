package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import org.junit.jupiter.api.Test;

class WaypointHighlightStateTest {
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("highlight-test");

    @Test
    void temporaryLocationMatchesOnlyItsDimensionAndCoordinates() {
        final WaypointHighlightState state = new WaypointHighlightState();
        state.select(WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 10.5, 64.0, -20.5, true
        ));

        assertTrue(state.activeIn(DimensionId.OVERWORLD));
        assertTrue(state.matches(10.5, -20.5, DimensionId.OVERWORLD));
        assertFalse(state.matches(10.5, -20.5, DimensionId.NETHER));
        assertFalse(state.matches(11.5, -20.5, DimensionId.OVERWORLD));
    }

    @Test
    void sessionChangeClearsTheSelection() {
        final WaypointHighlightState state = new WaypointHighlightState();
        state.onSessionChanged(new SessionGuard.Session(1L, WORLD, DimensionId.OVERWORLD));
        state.select(WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 10.5, 64.0, -20.5, true
        ));

        state.onSessionChanged(new SessionGuard.Session(2L, WORLD, DimensionId.NETHER));

        assertFalse(state.active());
    }

    @Test
    void repeatedNotificationForTheSameSessionKeepsTheSelection() {
        final WaypointHighlightState state = new WaypointHighlightState();
        final SessionGuard.Session session = new SessionGuard.Session(
            1L, WORLD, DimensionId.OVERWORLD
        );
        state.onSessionChanged(session);
        state.select(WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 10.5, 64.0, -20.5, true
        ));

        state.onSessionChanged(session);

        assertTrue(state.active());
    }
}
