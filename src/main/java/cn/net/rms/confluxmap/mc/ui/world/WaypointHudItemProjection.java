package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.mc.render.WaypointHudFrameMatrices;
import java.util.Optional;

/** Projects a world-space waypoint anchor into the flat GUI coordinate system. */
final class WaypointHudItemProjection {
    private static final double MIN_FORWARD_DISTANCE = 0.001;

    private WaypointHudItemProjection() {
    }

    /**
     * Projects through the world pass's captured projection and camera view. The captured
     * projection carries the nausea sway (and view bobbing/hurt tilt from 1.21.2 on), so the
     * label moves exactly like the 3D labels do; the hand-rolled basis below is the fallback
     * for frames without a capture.
     */
    static Optional<Placement> project(
        final WaypointHudFrameMatrices.Snapshot frame,
        final double dx,
        final double dy,
        final double dz,
        final int screenWidth,
        final int screenHeight,
        final double renderedDistance,
        final float easedTargetProgress,
        final float markerSize,
        final int scalePercent
    ) {
        if (screenWidth <= 0 || screenHeight <= 0 || renderedDistance <= 0.0) {
            return Optional.empty();
        }
        final double[] projection = frame.projection();
        final double[] view = frame.view();
        // Row-major: view first, then projection; for a perspective projection clipW is the
        // forward view distance - the same culling term the hand-rolled basis computes.
        final double vx = view[0] * dx + view[1] * dy + view[2] * dz + view[3];
        final double vy = view[4] * dx + view[5] * dy + view[6] * dz + view[7];
        final double vz = view[8] * dx + view[9] * dy + view[10] * dz + view[11];
        final double vw = view[12] * dx + view[13] * dy + view[14] * dz + view[15];
        final double clipX = projection[0] * vx + projection[1] * vy + projection[2] * vz + projection[3] * vw;
        final double clipY = projection[4] * vx + projection[5] * vy + projection[6] * vz + projection[7] * vw;
        final double clipW = projection[12] * vx + projection[13] * vy + projection[14] * vz + projection[15] * vw;
        if (clipW <= MIN_FORWARD_DISTANCE) {
            return Optional.empty();
        }
        final double focalLength = screenHeight / 2.0 * projection[5];
        if (focalLength <= 0.0) {
            return Optional.empty();
        }
        final float centerX = (float) ((clipX / clipW + 1.0) * 0.5 * screenWidth);
        final float centerY = (float) ((1.0 - clipY / clipW) * 0.5 * screenHeight);
        final float unitScale = unitScale(focalLength, renderedDistance, easedTargetProgress, scalePercent);
        return Optional.of(new Placement(centerX, centerY, markerSize * unitScale, unitScale));
    }

    /** Hand-rolled camera-basis fallback for frames the world pass did not capture. */
    static Optional<Placement> project(
        final float cameraYawDegrees,
        final float cameraPitchDegrees,
        final double dx,
        final double dy,
        final double dz,
        final int screenWidth,
        final int screenHeight,
        final double verticalFovDegrees,
        final double renderedDistance,
        final float easedTargetProgress,
        final float markerSize,
        final int scalePercent
    ) {
        if (screenWidth <= 0 || screenHeight <= 0
            || verticalFovDegrees <= 0.0 || verticalFovDegrees >= 180.0
            || renderedDistance <= 0.0) {
            return Optional.empty();
        }
        final double yaw = Math.toRadians(cameraYawDegrees);
        final double pitch = Math.toRadians(cameraPitchDegrees);
        final double sinYaw = Math.sin(yaw);
        final double cosYaw = Math.cos(yaw);
        final double sinPitch = Math.sin(pitch);
        final double cosPitch = Math.cos(pitch);
        final double forward = -sinYaw * cosPitch * dx
            - sinPitch * dy
            + cosYaw * cosPitch * dz;
        if (forward <= MIN_FORWARD_DISTANCE) {
            return Optional.empty();
        }
        final double right = -cosYaw * dx - sinYaw * dz;
        final double up = -sinPitch * sinYaw * dx
            + cosPitch * dy
            + sinPitch * cosYaw * dz;
        final double focalLength = screenHeight
            / (2.0 * Math.tan(Math.toRadians(verticalFovDegrees) / 2.0));
        final float centerX = (float) (screenWidth / 2.0 + right / forward * focalLength);
        final float centerY = (float) (screenHeight / 2.0 - up / forward * focalLength);
        final float unitScale = unitScale(focalLength, renderedDistance, easedTargetProgress, scalePercent);
        return Optional.of(new Placement(centerX, centerY, markerSize * unitScale, unitScale));
    }

    /**
     * Same perspective multiplier the 3D label path uses, so both label flavors size
     * identically; {@code focalLength} is the projection's screen-height focal in GUI pixels.
     */
    private static float unitScale(
        final double focalLength,
        final double renderedDistance,
        final float easedTargetProgress,
        final int scalePercent
    ) {
        return (float) (
            WaypointLabelStyle.LABEL_BASE_SCALE
                * WaypointLabelMetrics.labelWorldScaleMult(renderedDistance, easedTargetProgress)
                * focalLength
                / renderedDistance
                * scalePercent / 100.0
        );
    }

    record Placement(float centerX, float centerY, float size, float unitScale) {
    }
}
