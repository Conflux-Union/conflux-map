package cn.net.rms.confluxmap.server.shared;

import java.io.IOException;

/** Persistence boundary used to make shared-waypoint mutations durable before they become visible. */
public interface SharedWaypointPersistence {
    SharedWaypointStore.Snapshot load() throws IOException;

    void save(SharedWaypointStore.Snapshot snapshot) throws IOException;
}
