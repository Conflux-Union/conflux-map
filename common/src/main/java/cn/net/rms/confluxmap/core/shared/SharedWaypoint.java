package cn.net.rms.confluxmap.core.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a server-owned waypoint shared with connected players.
 *
 * <p>Coordinates are stored in the local coordinate system of {@link #dimensionId()}.
 * {@link #revision()} is the server-assigned waypoint revision used for optimistic updates.
 */
public record SharedWaypoint(
    UUID id,
    UUID publisherId,
    String publisherName,
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int colorArgb,
    Waypoint.Type type,
    String iconItemId,
    String markerLabel,
    long createdAtEpochMs,
    long revision
) {
    public SharedWaypoint {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(publisherId, "publisherId");
        Objects.requireNonNull(publisherName, "publisherName");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        iconItemId = WaypointMarkerStyle.iconItemId(iconItemId);
        markerLabel = WaypointMarkerStyle.markerLabel(markerLabel);
    }

    public SharedWaypoint(
        final UUID id,
        final UUID publisherId,
        final String publisherName,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final Waypoint.Type type,
        final long createdAtEpochMs,
        final long revision
    ) {
        this(
            id, publisherId, publisherName, name, dimensionId, x, y, z,
            colorArgb, type, "", "", createdAtEpochMs, revision
        );
    }
}
