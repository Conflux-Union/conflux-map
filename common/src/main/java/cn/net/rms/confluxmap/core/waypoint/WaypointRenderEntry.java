package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, storage-agnostic waypoint view consumed by every rendering surface.
 * The source flag is deliberately retained so UI gestures route mutations through
 * the correct local or server authority.
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
    Source source
) {
    public enum Source { LOCAL, SHARED }

    public WaypointRenderEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        iconItemId = iconItemId == null ? "" : iconItemId;
        markerLabel = markerLabel == null ? "" : markerLabel;
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
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
        this(id, name, dimensionId, x, y, z, colorArgb, "", "", type, source);
    }

    public boolean local() {
        return source == Source.LOCAL;
    }

    public boolean shared() {
        return source == Source.SHARED;
    }
}
