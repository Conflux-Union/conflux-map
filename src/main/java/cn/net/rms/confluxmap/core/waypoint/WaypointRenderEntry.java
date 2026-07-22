package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Objects;
import java.util.UUID;

/**
 * Immutable, storage-agnostic waypoint view consumed by every rendering surface.
 * The source flag is deliberately retained so UI gestures can never mistake a
 * server-owned waypoint for an editable local one.
 */
public record WaypointRenderEntry(
    UUID id,
    String name,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int colorArgb,
    Waypoint.Type type,
    Source source,
    boolean locked
) {
    public enum Source { LOCAL, SHARED }

    public WaypointRenderEntry {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(source, "source");
        if (source == Source.LOCAL && locked) {
            throw new IllegalArgumentException("local waypoint cannot be server-locked");
        }
    }

    public boolean local() {
        return source == Source.LOCAL;
    }

    public boolean shared() {
        return source == Source.SHARED;
    }
}
