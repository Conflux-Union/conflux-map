package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import java.util.Objects;
import java.util.UUID;

/**
 * Requests creation of one shared waypoint.
 *
 * <p>{@code groupId} assigns the waypoint to a server group on creation
 * (null means ungrouped); it is only representable on wire minor 6 and newer.
 * {@code description}/{@code imageId} are only representable on wire minor 7
 * and newer; encoding normalizes them, and older peers simply never see them.
 */
public record CreateC2S(
    UUID operationId,
    long expectedRevision,
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int color,
    Waypoint.Type type,
    String iconItemId,
    String markerLabel,
    String description,
    String imageId,
    UUID groupId
) implements SharedWaypointMessage {
    public CreateC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        iconItemId = WaypointMarkerStyle.iconItemId(iconItemId);
        markerLabel = WaypointMarkerStyle.markerLabel(markerLabel);
        description = WaypointDescription.normalize(description);
        imageId = WaypointDescription.normalizeImageId(imageId);
    }

    public CreateC2S(
        final UUID operationId,
        final long expectedRevision,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int color,
        final Waypoint.Type type,
        final String iconItemId,
        final String markerLabel,
        final UUID groupId
    ) {
        this(
            operationId, expectedRevision, name, dimensionId, x, y, z, color, type,
            iconItemId, markerLabel, "", "", groupId
        );
    }

    public CreateC2S(
        final UUID operationId,
        final long expectedRevision,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int color,
        final Waypoint.Type type,
        final String iconItemId,
        final String markerLabel
    ) {
        this(
            operationId, expectedRevision, name, dimensionId, x, y, z, color, type,
            iconItemId, markerLabel, null
        );
    }

    public CreateC2S(
        final UUID operationId,
        final long expectedRevision,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int color,
        final Waypoint.Type type
    ) {
        this(operationId, expectedRevision, name, dimensionId, x, y, z, color, type, "", "", null);
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_CREATE_C2S;
    }
}
