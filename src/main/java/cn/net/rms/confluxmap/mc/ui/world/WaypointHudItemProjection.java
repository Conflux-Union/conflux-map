package cn.net.rms.confluxmap.mc.ui.world;

import java.util.Optional;

/** Projects a world-space waypoint anchor into the flat GUI coordinate system. */
final class WaypointHudItemProjection {
    private static final double MIN_FORWARD_DISTANCE = 0.001;

    private WaypointHudItemProjection() {
    }

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
        final double scaleMultiplier = Math.max(
            WaypointWorldRenderer.LABEL_MIN_SCALE_MULT,
            Math.min(
                WaypointWorldRenderer.LABEL_MAX_SCALE_MULT,
                renderedDistance / WaypointWorldRenderer.LABEL_REFERENCE_DISTANCE
            )
        );
        final float unitScale = (float) (
            WaypointWorldRenderer.LABEL_BASE_SCALE
                * scaleMultiplier
                * focalLength
                / renderedDistance
                * scalePercent / 100.0
        );
        return Optional.of(new Placement(centerX, centerY, markerSize * unitScale, unitScale));
    }

    record Placement(float centerX, float centerY, float size, float unitScale) {
    }
}
