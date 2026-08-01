package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.Objects;

/** Adds or replaces one waypoint in a subscribed client cache. */
public record UpsertS2C(long revision, SharedWaypoint waypoint) implements SharedWaypointMessage {
    public UpsertS2C {
        Objects.requireNonNull(waypoint, "waypoint");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_UPSERT_S2C;
    }
}
