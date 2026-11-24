package cn.net.rms.confluxmap.core.waypoint.list;

import java.util.UUID;

/**
 * Client-owned personal state for waypoints whose authoritative data lives
 * elsewhere: per-waypoint eye state and per-group fold state of shared
 * (server-owned) waypoints. These are UX preferences of one player, so they
 * persist in the client configuration and never travel over the protocol.
 */
public interface WaypointPersonalPreferences {
    boolean isWaypointHidden(UUID waypointId);

    void setWaypointHidden(UUID waypointId, boolean hidden);

    boolean isGroupCollapsed(String groupKey);

    void setGroupCollapsed(String groupKey, boolean collapsed);

    /** Change counter so screens can poll for preference updates. */
    long version();
}
