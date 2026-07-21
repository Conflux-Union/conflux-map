package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;
import java.util.UUID;

/** Removes one waypoint from a subscribed client cache. */
public record RemoveS2C(long revision, UUID id) implements SharedWaypointMessage {
    public RemoveS2C {
        Objects.requireNonNull(id, "id");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_REMOVE_S2C;
    }
}
