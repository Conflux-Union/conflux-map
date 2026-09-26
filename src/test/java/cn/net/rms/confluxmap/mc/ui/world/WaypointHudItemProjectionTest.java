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
                1920, 1080, 70.0, 10.0, 0f, 12f, 100
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
                1920, 1080, 70.0, 10.0, 0f, 12f, 100
            ).orElseThrow();

        assertEquals(960f, placement.centerX(), 0.001f);
        assertEquals(540f, placement.centerY(), 0.001f);
    }

    @Test
    void keepsMinecraftsHorizontalScreenOrientation() {
        final WaypointHudItemProjection.Placement east = WaypointHudItemProjection.project(
            0f, 0f, 1.0, 0.0, 10.0,
            1920, 1080, 70.0, 10.0, 0f, 12f, 100
        ).orElseThrow();
        final WaypointHudItemProjection.Placement west = WaypointHudItemProjection.project(
            0f, 0f, -1.0, 0.0, 10.0,
            1920, 1080, 70.0, 10.0, 0f, 12f, 100
        ).orElseThrow();

        assertTrue(east.centerX() < 960f);
        assertTrue(west.centerX() > 960f);
    }

    @Test
    void rejectsMarkersBehindTheCamera() {
        assertTrue(WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, -10.0,
            1920, 1080, 70.0, 10.0, 0f, 12f, 100
        ).isEmpty());
    }

    @Test
    void keepsTheMaximumApparentSizeWithinTheReferenceDistance() {
        final float close = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 6.0,
            1920, 1080, 70.0, 6.0, 0f, 12f, 100
        ).orElseThrow().size();
        final float reference = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
        ).orElseThrow().size();

        assertEquals(reference, close, 0.001f);
    }

    @Test
    void shrinksWithInverseDistanceBeyondTheReferenceDistance() {
        final float reference = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
        ).orElseThrow().size();
        final float beyond = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 15.0,
            1920, 1080, 70.0, 15.0, 0f, 12f, 100
        ).orElseThrow().size();

        assertEquals(reference * (12f / 15f), beyond, 0.001f);
    }

    @Test
    void neverShrinksBelowTheFarFloor() {
        final float reference = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
        ).orElseThrow().size();
        final float far = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 100.0,
            1920, 1080, 70.0, 100.0, 0f, 12f, 100
        ).orElseThrow().size();
        final float veryFar = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 460.0,
            1920, 1080, 70.0, 460.0, 0f, 12f, 100
        ).orElseThrow().size();

        assertEquals(reference * WaypointWorldRenderer.LABEL_FAR_SCREEN_FLOOR, far, 0.001f);
        assertEquals(far, veryFar, 0.001f);
    }

    @Test
    void targetZoomIsNotDampenedByThePerspectiveShrink() {
        final float reference = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
        ).orElseThrow().size();
        final float targeted = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 100.0,
            1920, 1080, 70.0, 100.0, 1f, 12f, 100
        ).orElseThrow().size();
        final float halfTargeted = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 100.0,
            1920, 1080, 70.0, 100.0, 0.5f, 12f, 100
        ).orElseThrow().size();

        assertEquals(reference, targeted, 0.001f);
        assertEquals(reference * 0.8f, halfTargeted, 0.001f);
    }
}
