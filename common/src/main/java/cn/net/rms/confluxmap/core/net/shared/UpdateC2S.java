package cn.net.rms.confluxmap.core.net.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointDescription;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import java.util.Objects;
import java.util.UUID;

/**
 * Requests an update to one existing shared waypoint.
 *
 * <p>{@code groupSpecified} distinguishes an explicit group assignment
 * ({@code groupId} null = ungroup) from leaving the membership untouched,
 * which is all a wire shape below minor 6 can express.
 * {@code description}/{@code imageId} are only representable on wire minor 7
 * and newer; encoding normalizes them, and older peers simply never see them.
 */
public record UpdateC2S(
    UUID operationId,
    UUID id,
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
    UUID groupId,
    boolean groupSpecified
) implements SharedWaypointMessage {
    public UpdateC2S {
        Objects.requireNonNull(operationId, "operationId");
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        iconItemId = WaypointMarkerStyle.iconItemId(iconItemId);
        markerLabel = WaypointMarkerStyle.markerLabel(markerLabel);
        description = WaypointDescription.normalize(description);
        imageId = WaypointDescription.normalizeImageId(imageId);
    }

    public UpdateC2S(
        final UUID operationId,
        final UUID id,
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
        final UUID groupId,
        final boolean groupSpecified
    ) {
        this(
            operationId, id, expectedRevision, name, dimensionId, x, y, z,
            color, type, iconItemId, markerLabel, "", "", groupId, groupSpecified
        );
    }

    public UpdateC2S(
        final UUID operationId,
        final UUID id,
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
            operationId, id, expectedRevision, name, dimensionId, x, y, z,
            color, type, iconItemId, markerLabel, null, false
        );
    }

    public UpdateC2S(
        final UUID operationId,
        final UUID id,
        final long expectedRevision,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int color,
        final Waypoint.Type type
    ) {
        this(operationId, id, expectedRevision, name, dimensionId, x, y, z, color, type, "", "", null, false);
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_UPDATE_C2S;
    }
}
