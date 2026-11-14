package cn.net.rms.confluxmap.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Read-only access to the server-published ("shared") waypoints of the current world.
 * Reads return immutable snapshots and are safe from any thread; the catalog changes
 * as the companion server publishes updates, observable through
 * {@link ConfluxMapEvents#sharedWaypointsChanged()}.
 *
 * <p>Deliberately read-only for now: creating, editing, and deleting shared waypoints
 * (and their permission model) are not part of the public surface yet. The mod's own
 * waypoint-list screen remains the write path.
 */
@ApiStatus.Experimental
public interface SharedWaypointApi {
    /** All shared waypoints of the current world, in server order. Empty while unsynchronized. */
    List<ApiSharedWaypoint> list();

    Optional<ApiSharedWaypoint> get(UUID id);

    /** Whether the companion server publishes shared waypoints for this session. */
    SharedWaypointAccess availability();

    /** One server-published location. Coordinates are raw local coordinates in {@code dimensionId}. */
    record ApiSharedWaypoint(
        UUID id,
        String publisherName,
        String name,
        String dimensionId,
        double x,
        double y,
        double z,
        int colorArgb,
        WaypointApi.WaypointType type,
        String iconItemId,
        String markerLabel,
        String description,
        String imageId,
        long createdAtEpochMs
    ) {
    }

    /**
     * Snapshot of the shared-waypoint handshake. {@code supported} means the server
     * understands the protocol, {@code enabled} that it is turned on, and
     * {@code synchronizedSnapshot} that the list has been fetched at least once.
     */
    record SharedWaypointAccess(boolean supported, boolean enabled, boolean synchronizedSnapshot) {
    }
}
