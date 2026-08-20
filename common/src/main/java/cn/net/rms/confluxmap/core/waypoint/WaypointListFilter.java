package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Dimension filtering for waypoint-management lists. */
public final class WaypointListFilter {
    private WaypointListFilter() {
    }

    /**
     * Returns every local waypoint when cross-dimension display is enabled;
     * otherwise returns only waypoints stored in the current dimension.
     */
    public static List<Waypoint> local(
        final List<Waypoint> waypoints,
        final DimensionId currentDimension,
        final boolean crossDimension
    ) {
        return filter(waypoints, waypoint -> waypoint.dimensionId, currentDimension, crossDimension);
    }

    public static List<Waypoint> local(
        final List<Waypoint> waypoints,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        return filter(
            waypoints, waypoint -> waypoint.dimensionId, currentDimension, dimensionFilter
        );
    }

    /**
     * Returns every shared waypoint when cross-dimension display is enabled;
     * otherwise returns only waypoints stored in the current dimension.
     */
    public static List<SharedWaypoint> shared(
        final List<SharedWaypoint> waypoints,
        final DimensionId currentDimension,
        final boolean crossDimension
    ) {
        return filter(waypoints, SharedWaypoint::dimensionId, currentDimension, crossDimension);
    }

    public static List<SharedWaypoint> shared(
        final List<SharedWaypoint> waypoints,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        return filter(
            waypoints, SharedWaypoint::dimensionId, currentDimension, dimensionFilter
        );
    }

    private static <T> List<T> filter(
        final List<T> waypoints,
        final Function<T, DimensionId> dimensionOf,
        final DimensionId currentDimension,
        final boolean crossDimension
    ) {
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(currentDimension, "currentDimension");
        return crossDimension
            ? List.copyOf(waypoints)
            : waypoints.stream()
                .filter(waypoint -> dimensionOf.apply(waypoint).equals(currentDimension))
                .toList();
    }

    private static <T> List<T> filter(
        final List<T> waypoints,
        final Function<T, DimensionId> dimensionOf,
        final DimensionId currentDimension,
        final WaypointDimensionFilter dimensionFilter
    ) {
        Objects.requireNonNull(waypoints, "waypoints");
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(dimensionFilter, "dimensionFilter");
        return waypoints.stream()
            .filter(waypoint -> dimensionFilter.matches(
                dimensionOf.apply(waypoint), currentDimension
            ))
            .toList();
    }
}
