package cn.net.rms.confluxmap.core.api;

import cn.net.rms.confluxmap.api.WaypointApi.ApiWaypoint;
import cn.net.rms.confluxmap.api.WaypointApi.WaypointType;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointSet;
import java.util.ArrayList;
import java.util.List;

/** Conversions between the api DTO records and the core waypoint model. */
final class ApiMappers {
    private ApiMappers() {
    }

    static ApiWaypoint toApi(final Waypoint waypoint) {
        return new ApiWaypoint(
            waypoint.id,
            waypoint.name,
            waypoint.dimensionId.toString(),
            waypoint.x,
            waypoint.y,
            waypoint.z,
            waypoint.colorArgb,
            waypoint.group,
            waypoint.visible,
            waypoint.crossDimensionVisible,
            fromCore(waypoint.type),
            waypoint.iconItemId,
            waypoint.markerLabel,
            waypoint.description,
            waypoint.imageId,
            waypoint.createdAtEpochMs
        );
    }

    static List<ApiWaypoint> toApi(final List<Waypoint> waypoints) {
        final List<ApiWaypoint> out = new ArrayList<>(waypoints.size());
        for (final Waypoint waypoint : waypoints) {
            out.add(toApi(waypoint));
        }
        return out;
    }

    static List<String> setNames(final List<WaypointSet> sets) {
        final List<String> out = new ArrayList<>(sets.size());
        for (final WaypointSet set : sets) {
            out.add(set.name());
        }
        return out;
    }

    static Waypoint.Type toCore(final WaypointType type) {
        return type == WaypointType.DEATH ? Waypoint.Type.DEATH : Waypoint.Type.NORMAL;
    }

    static WaypointType fromCore(final Waypoint.Type type) {
        return type == Waypoint.Type.DEATH ? WaypointType.DEATH : WaypointType.NORMAL;
    }
}
