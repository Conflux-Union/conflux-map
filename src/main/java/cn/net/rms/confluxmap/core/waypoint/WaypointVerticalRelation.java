package cn.net.rms.confluxmap.core.waypoint;

/** Relative vertical direction shown beside waypoint markers in navigation views. */
public enum WaypointVerticalRelation {
    NONE,
    ABOVE,
    LEVEL,
    BELOW;

    public static final int LEVEL_THRESHOLD_BLOCKS = 1;

    public static WaypointVerticalRelation between(final double waypointY, final double playerY) {
        final int delta = (int) Math.floor(waypointY) - (int) Math.floor(playerY);
        if (delta > LEVEL_THRESHOLD_BLOCKS) {
            return ABOVE;
        }
        if (delta < -LEVEL_THRESHOLD_BLOCKS) {
            return BELOW;
        }
        return LEVEL;
    }
}
