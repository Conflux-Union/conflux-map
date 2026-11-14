package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import org.junit.jupiter.api.Test;

/** The edit form's temporary-toggle conversion rule. */
class WaypointEditDraftTypeTest {
    @Test
    void toggleOnMakesNormalTemporary() {
        assertEquals(
            Waypoint.Type.TEMPORARY,
            WaypointEditScreen.resolveDraftType(Waypoint.Type.NORMAL, true));
    }

    @Test
    void toggleOffKeepsNormalNormal() {
        assertEquals(
            Waypoint.Type.NORMAL,
            WaypointEditScreen.resolveDraftType(Waypoint.Type.NORMAL, false));
    }

    @Test
    void toggleOffConvertsTemporaryBackToNormal() {
        assertEquals(
            Waypoint.Type.NORMAL,
            WaypointEditScreen.resolveDraftType(Waypoint.Type.TEMPORARY, false));
    }

    @Test
    void toggleOnKeepsTemporaryTemporary() {
        assertEquals(
            Waypoint.Type.TEMPORARY,
            WaypointEditScreen.resolveDraftType(Waypoint.Type.TEMPORARY, true));
    }

    @Test
    void toggleOffKeepsDeathDeath() {
        // The toggle is hidden for death waypoints; resolution must not invent NORMAL.
        assertEquals(
            Waypoint.Type.DEATH,
            WaypointEditScreen.resolveDraftType(Waypoint.Type.DEATH, false));
    }
}
