package cn.net.rms.confluxmap.core.waypoint.library;

/** Rotation in clockwise screen degrees, canonicalized for persistence. */
public final class PhotoRotation {
    private PhotoRotation() {}

    public static double normalize(double degrees) {
        if (!Double.isFinite(degrees)) return 0;
        double normalized = degrees % 360;
        if (normalized < 0) normalized += 360;
        return normalized == 0 || normalized == 360 ? 0 : normalized;
    }
}
