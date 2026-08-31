package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import java.util.Objects;
import java.util.UUID;

/** Requests creation of one shared waypoint. */
public record CreateC2S(
    UUID operationId,
    long expectedRevision,
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int color,
    Waypoint.Type type,
    String iconItemId,
    String markerLabel
) implements SharedWaypointMessage {
    public CreateC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        iconItemId = WaypointMarkerStyle.iconItemId(iconItemId);
        markerLabel = WaypointMarkerStyle.markerLabel(markerLabel);
    }

    public CreateC2S(
        final UUID operationId,
        final long expectedRevision,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int color,
        final Waypoint.Type type
    ) {
        this(operationId, expectedRevision, name, dimensionId, x, y, z, color, type, "", "");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_CREATE_C2S;
    }
}
