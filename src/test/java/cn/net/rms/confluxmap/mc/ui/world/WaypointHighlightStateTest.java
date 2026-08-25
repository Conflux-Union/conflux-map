package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import java.util.List;
import java.util.UUID;
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
    void temporaryLocationDoesNotSelectARealWaypointAtTheSameCoordinates() {
        final WaypointHighlightState state = new WaypointHighlightState();
        state.select(WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 10.5, 64.0, -20.5, true
        ));
        final WaypointRenderEntry realWaypoint = entry(UUID.randomUUID(), 10.5, -20.5);
        final WaypointRenderEntry syntheticLocation = entry(
            WaypointHighlightState.SELECTED_LOCATION_ID, 10.5, -20.5
        );

        assertFalse(state.matchesEntry(realWaypoint, DimensionId.OVERWORLD));
        assertTrue(state.matchesEntry(syntheticLocation, DimensionId.OVERWORLD));
    }

    @Test
    void savedWaypointTargetMatchesItsIdOnly() {
        final UUID id = UUID.randomUUID();
        final WaypointHighlightState state = new WaypointHighlightState();
        state.select(WaypointHighlightState.Target.waypoint(
            entry(id, 10.5, -20.5), DimensionId.OVERWORLD
        ));

        assertTrue(state.matchesEntry(entry(id, 99.5, 99.5), DimensionId.OVERWORLD));
        assertFalse(state.matchesEntry(entry(UUID.randomUUID(), 10.5, -20.5), DimensionId.OVERWORLD));
    }

    @Test
    void savedWaypointHighlightIsInactiveWhenTargetIsNotRenderable() {
        final WaypointHighlightState state = new WaypointHighlightState();
        state.select(WaypointHighlightState.Target.waypoint(
            entry(UUID.randomUUID(), 10.5, -20.5), DimensionId.OVERWORLD
        ));

        assertFalse(state.hasRenderableTarget(
            List.of(entry(UUID.randomUUID(), 20.5, -30.5)),
            DimensionId.OVERWORLD
        ));
    }

    @Test
    void locationHighlightRemainsActiveForItsSyntheticEntry() {
        final WaypointHighlightState state = new WaypointHighlightState();
        state.select(WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 10.5, 64.0, -20.5, false
        ));

        assertTrue(state.hasRenderableTarget(
            List.of(entry(WaypointHighlightState.SELECTED_LOCATION_ID, 10.5, -20.5)),
            DimensionId.OVERWORLD
        ));
    }

    @Test
    void unknownLocationHeightDoesNotIncreaseRenderDistance() {
        final WaypointHighlightState state = new WaypointHighlightState();
        state.select(WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 13.0, 64.0, 24.0, false
        ));
        final WaypointRenderEntry location = entry(
            WaypointHighlightState.SELECTED_LOCATION_ID, 13.0, 24.0
        );

        assertEquals(
            5.0,
            state.renderDistance(location, DimensionId.OVERWORLD, 10.0, 300.0, 20.0),
            0.001
        );
    }

    @Test
    void knownWaypointHeightStillUsesThreeDimensionalRenderDistance() {
        final UUID id = UUID.randomUUID();
        final WaypointHighlightState state = new WaypointHighlightState();
        final WaypointRenderEntry waypoint = entry(id, 13.0, 24.0);
        state.select(WaypointHighlightState.Target.waypoint(
            waypoint, DimensionId.OVERWORLD
        ));

        assertEquals(
            Math.sqrt(3.0 * 3.0 + 236.0 * 236.0 + 4.0 * 4.0),
            state.renderDistance(waypoint, DimensionId.OVERWORLD, 10.0, 300.0, 20.0),
            0.001
        );
    }

    @Test
    void synthesizedLocationUsesTheCurrentTranslatedName() {
        final WaypointHighlightState.Target target = WaypointHighlightState.Target.location(
            DimensionId.OVERWORLD, 10.5, 64.0, -20.5, false
        );

        assertEquals(
            "Selected location",
            WaypointHighlightState.locationEntry(
                target, "Selected location", 64.0, 0xFFFFE066
            ).name()
        );
        assertEquals(
            "Current translation",
            WaypointHighlightState.locationEntry(
                target, "Current translation", 64.0, 0xFFFFE066
            ).name()
        );
    }

    private static WaypointRenderEntry entry(final UUID id, final double x, final double z) {
        return new WaypointRenderEntry(
            id, "test", DimensionId.OVERWORLD, x, 64.0, z, 0xFFFFFFFF,
            Waypoint.Type.NORMAL, WaypointRenderEntry.Source.LOCAL
        );
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
