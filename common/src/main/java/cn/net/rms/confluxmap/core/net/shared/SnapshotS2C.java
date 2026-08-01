package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.List;
import java.util.Objects;

/** Full shared-waypoint state at one server revision. */
public record SnapshotS2C(long revision, boolean operator, List<SharedWaypoint> list)
    implements SharedWaypointMessage {
    public SnapshotS2C {
        Objects.requireNonNull(list, "list");
        list = List.copyOf(list);
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_SNAPSHOT_S2C;
    }
}
