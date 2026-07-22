package cn.net.rms.confluxmap.core.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.Objects;

/** Identifies the Minecraft block occupied by a local or public waypoint. */
public record SharedWaypointLocationKey(DimensionId dimensionId, long blockX, long blockY, long blockZ) {
    public SharedWaypointLocationKey {
        Objects.requireNonNull(dimensionId, "dimensionId");
    }

    public static SharedWaypointLocationKey from(final Waypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        return from(waypoint.dimensionId, waypoint.x, waypoint.y, waypoint.z);
    }

    public static SharedWaypointLocationKey from(final SharedWaypoint waypoint) {
        Objects.requireNonNull(waypoint, "waypoint");
        return from(waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z());
    }

    public static SharedWaypointLocationKey from(
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        Objects.requireNonNull(dimensionId, "dimensionId");
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("shared waypoint coordinates must be finite");
        }
        return new SharedWaypointLocationKey(
            dimensionId,
            floorToLong(x),
            floorToLong(y),
            floorToLong(z)
        );
    }

    private static long floorToLong(final double coordinate) {
        final double floored = Math.floor(coordinate);
        if (floored < Long.MIN_VALUE || floored > Long.MAX_VALUE) {
            throw new IllegalArgumentException("shared waypoint coordinate is outside the supported range");
        }
        return (long) floored;
    }
}
