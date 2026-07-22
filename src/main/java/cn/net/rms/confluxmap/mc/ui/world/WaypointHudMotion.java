package cn.net.rms.confluxmap.mc.ui.world;

import net.minecraft.util.math.MathHelper;

/** Pure targeting and animation math for the in-world waypoint HUD. */
final class WaypointHudMotion {
    private static final float EXPAND_DURATION_SECONDS = 0.14f;
    private static final float COLLAPSE_DURATION_SECONDS = 0.11f;
    private static final double BASE_TARGET_CONE_DEGREES = 2.0;
    private static final double MAX_EXTRA_TARGET_CONE_DEGREES = 4.0;
    private static final double TARGET_CONE_DISTANCE_FACTOR = 16.0;

    private WaypointHudMotion() {
    }

    static double alignment(
        final float cameraYawDegrees,
        final float cameraPitchDegrees,
        final double dx,
        final double dy,
        final double dz
    ) {
        final double distance = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (distance <= 0.0) {
            return -1.0;
        }
        final double yaw = Math.toRadians(cameraYawDegrees);
        final double pitch = Math.toRadians(cameraPitchDegrees);
        final double cosPitch = Math.cos(pitch);
        final double lookX = -Math.sin(yaw) * cosPitch;
        final double lookY = -Math.sin(pitch);
        final double lookZ = Math.cos(yaw) * cosPitch;
        return (lookX * dx + lookY * dy + lookZ * dz) / distance;
    }

    static boolean insideTargetCone(final double alignment, final double distance) {
        final double coneDegrees = BASE_TARGET_CONE_DEGREES + Math.min(
            MAX_EXTRA_TARGET_CONE_DEGREES,
            TARGET_CONE_DISTANCE_FACTOR / Math.max(1.0, distance)
        );
        return alignment >= Math.cos(Math.toRadians(coneDegrees));
    }

    static float advance(final float current, final boolean targeted, final float deltaSeconds) {
        final float duration = targeted ? EXPAND_DURATION_SECONDS : COLLAPSE_DURATION_SECONDS;
        final float direction = targeted ? 1.0f : -1.0f;
        return MathHelper.clamp(current + direction * Math.max(0.0f, deltaSeconds) / duration, 0.0f, 1.0f);
    }

    static float smoothStep(final float progress) {
        final float clamped = MathHelper.clamp(progress, 0.0f, 1.0f);
        return clamped * clamped * (3.0f - 2.0f * clamped);
    }
}
