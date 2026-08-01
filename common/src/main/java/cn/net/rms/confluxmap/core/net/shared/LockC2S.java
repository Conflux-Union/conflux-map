package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Changes a shared waypoint's operator lock if its revision still matches. */
public record LockC2S(UUID operationId, UUID id, long expectedRevision, boolean locked)
    implements SharedWaypointMessage {
    public LockC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(id, "id");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_LOCK_C2S;
    }
}
