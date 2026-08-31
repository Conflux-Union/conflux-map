package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WaypointHudItemProjectionTest {
    @Test
    void projectsAForwardMarkerToTheScreenCenter() {
        final WaypointHudItemProjection.Placement placement =
            WaypointHudItemProjection.project(
                0f, 0f, 0.0, 0.0, 10.0,
                1920, 1080, 70.0, 10.0, 12f, 100
            ).orElseThrow();

        assertEquals(960f, placement.centerX(), 0.001f);
        assertEquals(540f, placement.centerY(), 0.001f);
        assertEquals(placement.size(), placement.unitScale() * 12f, 0.001f);
    }

    @Test
    void followsTheCameraYaw() {
        final WaypointHudItemProjection.Placement placement =
            WaypointHudItemProjection.project(
                -90f, 0f, 10.0, 0.0, 0.0,
                1920, 1080, 70.0, 10.0, 12f, 100
            ).orElseThrow();

        assertEquals(960f, placement.centerX(), 0.001f);
        assertEquals(540f, placement.centerY(), 0.001f);
    }

    @Test
    void keepsMinecraftsHorizontalScreenOrientation() {
        final WaypointHudItemProjection.Placement east = WaypointHudItemProjection.project(
            0f, 0f, 1.0, 0.0, 10.0,
            1920, 1080, 70.0, 10.0, 12f, 100
        ).orElseThrow();
        final WaypointHudItemProjection.Placement west = WaypointHudItemProjection.project(
            0f, 0f, -1.0, 0.0, 10.0,
            1920, 1080, 70.0, 10.0, 12f, 100
        ).orElseThrow();

        assertTrue(east.centerX() < 960f);
        assertTrue(west.centerX() > 960f);
    }

    @Test
    void rejectsMarkersBehindTheCamera() {
        assertTrue(WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, -10.0,
            1920, 1080, 70.0, 10.0, 12f, 100
        ).isEmpty());
    }

    @Test
    void keepsTheSameApparentSizeAtDifferentDistances() {
        final float near = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 10.0,
            1920, 1080, 70.0, 10.0, 12f, 100
        ).orElseThrow().size();
        final float far = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 100.0,
            1920, 1080, 70.0, 100.0, 12f, 100
        ).orElseThrow().size();

        assertEquals(near, far, 0.001f);
    }
}
