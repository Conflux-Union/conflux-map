package cn.net.rms.confluxmap.mc.ui.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.mc.render.WaypointHudFrameMatrices;
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
            0f, 0f, 0.0, 0.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
        ).orElseThrow().size();

        assertEquals(reference, close, 0.001f);
    }

    @Test
    void shrinksWithInverseDistanceBeyondTheReferenceDistance() {
        final float reference = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
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
            0f, 0f, 0.0, 0.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
        ).orElseThrow().size();
        final float far = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 100.0,
            1920, 1080, 70.0, 100.0, 0f, 12f, 100
        ).orElseThrow().size();
        final float veryFar = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, 460.0,
            1920, 1080, 70.0, 460.0, 0f, 12f, 100
        ).orElseThrow().size();

        assertEquals(reference * WaypointLabelMetrics.LABEL_FAR_SCREEN_FLOOR, far, 0.001f);
        assertEquals(far, veryFar, 0.001f);
    }

    @Test
    void targetZoomIsNotDampenedByThePerspectiveShrink() {
        final float reference = WaypointHudItemProjection.project(
            0f, 0f, 0.0, 0.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE,
            1920, 1080, 70.0, WaypointLabelMetrics.LABEL_REFERENCE_DISTANCE, 0f, 12f, 100
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
        // Half aim progress lerps midway between the floored perspective term and full size.
        assertEquals(
            reference * (1f + WaypointLabelMetrics.LABEL_FAR_SCREEN_FLOOR) / 2f,
            halfTargeted, 0.001f
        );
    }

    @Test
    void matrixPathMatchesTheHandRolledBasis() {
        assertSamePlacement(0f, 0f, 0.0, 0.0, 10.0);
        assertSamePlacement(-90f, 0f, 10.0, 0.0, 0.0);
        assertSamePlacement(37f, -15f, 4.0, 2.0, 9.0);
        assertSamePlacement(200f, 40f, 3.0, -2.0, -14.0);
    }

    @Test
    void matrixPathFollowsTheCapturedSway() {
        final double[] projection = perspective(70.0, 1920.0 / 1080.0);
        final double[] view = cameraView(0f, 0f);
        final double[] swayed = multiply(rowRotationZ(Math.toRadians(6.0)), projection);
        final double dy = 2.0;

        final WaypointHudItemProjection.Placement still =
            WaypointHudItemProjection.project(
                new WaypointHudFrameMatrices.Snapshot(projection, view),
                1.0, dy, 10.0,
                1920, 1080, 10.0, 0f, 12f, 100
            ).orElseThrow();
        final WaypointHudItemProjection.Placement swayedPlacement =
            WaypointHudItemProjection.project(
                new WaypointHudFrameMatrices.Snapshot(swayed, view),
                1.0, dy, 10.0,
                1920, 1080, 10.0, 0f, 12f, 100
            ).orElseThrow();

        assertNotEquals(still.centerX(), swayedPlacement.centerX(), 0.001f);
        // The sway only rotates the clip-space position: the screen point rotates around the
        // screen center by the same angle, and the label size follows the projection's y-scale
        // row, which the rotation mixes with its x-scale row.
        final double angle = Math.toRadians(6.0);
        final double ndcX = still.centerX() / 1920f * 2.0 - 1.0;
        final double ndcY = 1.0 - still.centerY() / 1080f * 2.0;
        final double expectedX = ((ndcX * Math.cos(angle) - ndcY * Math.sin(angle)) + 1.0) * 0.5 * 1920.0;
        final double expectedY = (1.0 - (ndcX * Math.sin(angle) + ndcY * Math.cos(angle))) * 0.5 * 1080.0;
        assertEquals(expectedX, swayedPlacement.centerX(), 0.01f);
        assertEquals(expectedY, swayedPlacement.centerY(), 0.01f);
        assertEquals(still.unitScale() * (swayed[5] / projection[5]), swayedPlacement.unitScale(), 0.0001f);
    }

    @Test
    void matrixPathRejectsMarkersBehindTheCamera() {
        final WaypointHudFrameMatrices.Snapshot frame = new WaypointHudFrameMatrices.Snapshot(
            perspective(70.0, 1920.0 / 1080.0), cameraView(0f, 0f)
        );

        assertTrue(WaypointHudItemProjection.project(
            frame, 0.0, 0.0, -10.0, 1920, 1080, 10.0, 0f, 12f, 100
        ).isEmpty());
    }

    @Test
    void frameSnapshotCopiesItsMatricesAndClears() {
        final double[] projection = perspective(70.0, 1920.0 / 1080.0);
        final double[] view = cameraView(0f, 0f);
        WaypointHudFrameMatrices.update(projection, view);
        final WaypointHudFrameMatrices.Snapshot snapshot = WaypointHudFrameMatrices.current();
        assertEquals(1.0, snapshot.view()[15], 0.0);
        projection[0] = 99.0;
        view[0] = 99.0;
        assertEquals(0.803333, snapshot.projection()[0], 0.001);

        WaypointHudFrameMatrices.clear();
        assertNull(WaypointHudFrameMatrices.current());
    }

    private static void assertSamePlacement(
        final float yaw,
        final float pitch,
        final double dx,
        final double dy,
        final double dz
    ) {
        final WaypointHudItemProjection.Placement fallback =
            WaypointHudItemProjection.project(
                yaw, pitch, dx, dy, dz,
                1920, 1080, 70.0, 10.0, 0f, 12f, 100
            ).orElseThrow();
        final WaypointHudItemProjection.Placement matrix =
            WaypointHudItemProjection.project(
                new WaypointHudFrameMatrices.Snapshot(
                    perspective(70.0, 1920.0 / 1080.0), cameraView(yaw, pitch)
                ),
                dx, dy, dz,
                1920, 1080, 10.0, 0f, 12f, 100
            ).orElseThrow();

        assertEquals(fallback.centerX(), matrix.centerX(), 0.01f);
        assertEquals(fallback.centerY(), matrix.centerY(), 0.01f);
        assertEquals(fallback.unitScale(), matrix.unitScale(), 0.0001f);
    }

    /** Row-major OpenGL-style perspective matching the hand-rolled focal-length math. */
    private static double[] perspective(
        final double verticalFovDegrees,
        final double aspect
    ) {
        final double f = 1.0 / Math.tan(Math.toRadians(verticalFovDegrees) / 2.0);
        final double near = 0.05;
        final double far = 1000.0;
        final double[] m = new double[16];
        m[0] = f / aspect;
        m[5] = f;
        m[10] = (far + near) / (near - far);
        m[11] = 2.0 * far * near / (near - far);
        m[14] = -1.0;
        return m;
    }

    /**
     * Row-major camera view whose rows are the hand-rolled basis (right, up, -forward), so
     * the matrix path and the fallback describe the same camera.
     */
    private static double[] cameraView(final float yawDegrees, final float pitchDegrees) {
        final double yaw = Math.toRadians(yawDegrees);
        final double pitch = Math.toRadians(pitchDegrees);
        final double[] m = new double[16];
        m[0] = -Math.cos(yaw);
        m[2] = -Math.sin(yaw);
        m[4] = -Math.sin(pitch) * Math.sin(yaw);
        m[5] = Math.cos(pitch);
        m[6] = Math.sin(pitch) * Math.cos(yaw);
        m[8] = Math.sin(yaw) * Math.cos(pitch);
        m[9] = Math.sin(pitch);
        m[10] = -Math.cos(yaw) * Math.cos(pitch);
        m[15] = 1.0;
        return m;
    }

    private static double[] rowRotationZ(final double angle) {
        final double[] m = new double[16];
        m[0] = Math.cos(angle);
        m[1] = -Math.sin(angle);
        m[4] = Math.sin(angle);
        m[5] = Math.cos(angle);
        m[10] = 1.0;
        m[15] = 1.0;
        return m;
    }

    private static double[] multiply(final double[] a, final double[] b) {
        final double[] out = new double[16];
        for (int row = 0; row < 4; row++) {
            for (int col = 0; col < 4; col++) {
                double sum = 0.0;
                for (int k = 0; k < 4; k++) {
                    sum += a[row * 4 + k] * b[k * 4 + col];
                }
                out[row * 4 + col] = sum;
            }
        }
        return out;
    }
}
