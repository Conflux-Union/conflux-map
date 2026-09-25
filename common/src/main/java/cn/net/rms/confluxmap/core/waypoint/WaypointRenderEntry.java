package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, storage-agnostic waypoint view consumed by every rendering surface.
 * The source flag is deliberately retained so UI gestures route mutations through
 * the correct local or server authority; sibling entries are client-owned data
 * from another world namespace and are never mutable from the current session.
 */
public record WaypointRenderEntry(
    UUID id,
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int colorArgb,
    String iconItemId,
    String markerLabel,
    Waypoint.Type type,
    Source source,
    boolean crossDimensionVisible,
    String originWorldLabel
) {
    public enum Source { LOCAL, SHARED, SIBLING }

    public WaypointRenderEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        iconItemId = iconItemId == null ? "" : iconItemId;
        markerLabel = markerLabel == null ? "" : markerLabel;
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        originWorldLabel = originWorldLabel == null ? "" : originWorldLabel;
    }

    public WaypointRenderEntry(
        final UUID id,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final Waypoint.Type type,
        final Source source
    ) {
        this(id, name, dimensionId, x, y, z, colorArgb, "", "", type, source, false);
    }

    public WaypointRenderEntry(
        final UUID id,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final Waypoint.Type type,
        final Source source,
        final boolean crossDimensionVisible
    ) {
        this(
            id, name, dimensionId, x, y, z, colorArgb, "", "", type, source,
            crossDimensionVisible
        );
    }

    public WaypointRenderEntry(
        final UUID id,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final String iconItemId,
        final String markerLabel,
        final Waypoint.Type type,
        final Source source,
        final boolean crossDimensionVisible
    ) {
        this(
            id, name, dimensionId, x, y, z, colorArgb, iconItemId, markerLabel,
            type, source, crossDimensionVisible, ""
        );
    }

    public boolean local() {
        return source == Source.LOCAL;
    }

    public boolean shared() {
        return source == Source.SHARED;
    }

    public boolean sibling() {
        return source == Source.SIBLING;
    }
}
