package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.Objects;
import java.util.UUID;

/** Requests an update to one existing shared waypoint. */
public record UpdateC2S(
    UUID operationId,
    UUID id,
    long expectedRevision,
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int color,
    Waypoint.Type type
) implements SharedWaypointMessage {
    public UpdateC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_UPDATE_C2S;
    }
}
