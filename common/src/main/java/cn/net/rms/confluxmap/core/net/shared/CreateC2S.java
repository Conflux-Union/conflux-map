package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
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
    Waypoint.Type type
) implements SharedWaypointMessage {
    public CreateC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_CREATE_C2S;
    }
}
