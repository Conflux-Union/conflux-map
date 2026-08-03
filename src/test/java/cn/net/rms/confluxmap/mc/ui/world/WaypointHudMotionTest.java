package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WaypointHudMotionTest {
    private static final double EPSILON = 0.0001;

    @Test
    void alignmentUsesMinecraftCameraAxes() {
        assertEquals(1.0, WaypointHudMotion.alignment(0f, 0f, 0.0, 0.0, 10.0), EPSILON);
        assertEquals(1.0, WaypointHudMotion.alignment(-90f, 0f, 10.0, 0.0, 0.0), EPSILON);
        assertEquals(1.0, WaypointHudMotion.alignment(0f, -90f, 0.0, 10.0, 0.0), EPSILON);
    }

    @Test
    void targetConeIsMoreForgivingForNearbyWaypoints() {
        final double threeDegreeAlignment = Math.cos(Math.toRadians(3.0));
        assertTrue(WaypointHudMotion.insideTargetCone(threeDegreeAlignment, 4.0));
        assertFalse(WaypointHudMotion.insideTargetCone(threeDegreeAlignment, 100.0));
    }

    @Test
    void animationCanReverseFromItsCurrentProgress() {
        final float collapsing = WaypointHudMotion.advance(0.6f, false, 0.011f);
        assertEquals(0.5f, collapsing, 0.0001f);
        assertEquals(0.6f, WaypointHudMotion.advance(collapsing, true, 0.014f), 0.0001f);
    }

    @Test
    void easingKeepsStableEndpoints() {
        assertEquals(0.0f, WaypointHudMotion.smoothStep(-1.0f));
        assertEquals(0.5f, WaypointHudMotion.smoothStep(0.5f));
        assertEquals(1.0f, WaypointHudMotion.smoothStep(2.0f));
    }
}
