package cn.net.rms.confluxmap.core.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable snapshot of a server-owned waypoint shared with connected players.
 *
 * <p>Coordinates are stored in the local coordinate system of {@link #dimensionId()}.
 * {@link #revision()} is the server-assigned waypoint revision used for optimistic updates.
 * {@link #groupId()} references the server-owned {@link SharedWaypointGroup} this waypoint
 * belongs to, or {@code null} when it is ungrouped.
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
    String description,
    String imageId,
    long createdAtEpochMs,
    long revision,
    UUID groupId
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
        description = WaypointDescription.normalize(description);
        imageId = WaypointDescription.normalizeImageId(imageId);
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
        final String iconItemId,
        final String markerLabel,
        final long createdAtEpochMs,
        final long revision,
        final UUID groupId
    ) {
        this(
            id, publisherId, publisherName, name, dimensionId, x, y, z,
            colorArgb, type, iconItemId, markerLabel, "", "", createdAtEpochMs, revision, groupId
        );
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
        final String iconItemId,
        final String markerLabel,
        final long createdAtEpochMs,
        final long revision
    ) {
        this(
            id, publisherId, publisherName, name, dimensionId, x, y, z,
            colorArgb, type, iconItemId, markerLabel, createdAtEpochMs, revision, null
        );
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
            colorArgb, type, "", "", createdAtEpochMs, revision, null
        );
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
        final long revision,
        final UUID groupId
    ) {
        this(
            id, publisherId, publisherName, name, dimensionId, x, y, z,
            colorArgb, type, "", "", createdAtEpochMs, revision, groupId
        );
    }

    /** Returns a copy of this waypoint with the group membership replaced. */
    public SharedWaypoint withGroupId(final UUID newGroupId) {
        return new SharedWaypoint(
            id, publisherId, publisherName, name, dimensionId, x, y, z,
            colorArgb, type, iconItemId, markerLabel, description, imageId,
            createdAtEpochMs, revision, newGroupId
        );
    }
}
