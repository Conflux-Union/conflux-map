package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Deletes a shared waypoint if its revision still matches. */
public record DeleteC2S(UUID operationId, UUID id, long expectedRevision) implements SharedWaypointMessage {
    public DeleteC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(id, "id");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_DELETE_C2S;
    }
}
