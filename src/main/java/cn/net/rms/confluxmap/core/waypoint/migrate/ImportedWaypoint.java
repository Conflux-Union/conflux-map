package cn.net.rms.confluxmap.core.waypoint.migrate;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.Objects;

/**
 * One waypoint parsed from another mod's on-disk data, before it is deduped
 * and adopted into a {@link cn.net.rms.confluxmap.core.waypoint.WaypointStore}.
 * Carries only source facts; identity (UUID) and creation time are assigned
 * at import time by {@link WaypointImporter}.
 */
public record ImportedWaypoint(
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int colorArgb,
    String setName,
    boolean visible,
    Waypoint.Type type
) {
    public ImportedWaypoint {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(setName, "setName");
        Objects.requireNonNull(type, "type");
    }
}
