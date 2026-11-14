package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.Objects;
import java.util.UUID;

/**
 * A single saved location. Mutable on purpose (unlike the immutable value
 * records used elsewhere in {@code core}) because the list/edit UI mutates
 * one in place while a form is open; {@link WaypointStore} is the only thing
 * that owns the "live" instances, everyone else works off {@link #copy()}
 * snapshots (see the store's class doc for the threading rationale).
 *
 * <p>{@link #x}/{@link #y}/{@link #z} are always the raw local coordinates in
 * {@link #dimensionId} - never pre-converted. {@link DimensionScale} converts
 * on display only when a waypoint is viewed from a different dimension. Y is
 * never scaled, so a cross-dimension view shows the stored Y as-is unless
 * {@link #crossDimensionY} overrides it.
 */
public final class Waypoint {
    /**
     * {@code TEMPORARY} waypoints are memory-only: {@link WaypointStore.State},
     * the sole persistence boundary, drops them, so they vanish on restart and
     * never reach disk readers such as the cross-world sibling scan.
     */
    public enum Type { NORMAL, DEATH, TEMPORARY }

    public final UUID id;
    public String name;
    public DimensionId dimensionId;
    public double x;
    public double y;
    public double z;
    public int colorArgb;
    public String group;
    public boolean visible;
    public boolean crossDimensionVisible;
    /** Y shown when this waypoint renders from the portal-linked dimension; null keeps {@link #y}. */
    public Double crossDimensionY;
    public Type type;
    public String iconItemId = "";
    public String markerLabel = "";
    /** Multi-line notes; {@link WaypointDescription} normalizes every write path. */
    public String description = "";
    /** Canonical UUID of the preview image on the owning side, or {@code ""} for none. */
    public String imageId = "";
    public final long createdAtEpochMs;

    public Waypoint(
        final UUID id,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final String group,
        final boolean visible,
        final boolean crossDimensionVisible,
        final Type type,
        final long createdAtEpochMs
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.name = Objects.requireNonNull(name, "name");
        this.dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        this.x = x;
        this.y = y;
        this.z = z;
        this.colorArgb = colorArgb;
        this.group = group == null ? "" : group;
        this.visible = visible;
        this.crossDimensionVisible = crossDimensionVisible;
        this.type = Objects.requireNonNull(type, "type");
        this.createdAtEpochMs = createdAtEpochMs;
    }

    public Waypoint(
        final UUID id,
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final String group,
        final boolean visible,
        final Type type,
        final long createdAtEpochMs
    ) {
        this(
            id, name, dimensionId, x, y, z, colorArgb, group, visible, false,
            type, createdAtEpochMs
        );
    }

    public static Waypoint create(
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final String group,
        final Type type
    ) {
        return new Waypoint(
            UUID.randomUUID(), name, dimensionId, x, y, z, colorArgb, group, true, false,
            type, System.currentTimeMillis()
        );
    }

    /** Deep (field-for-field, but always independent) copy. Never share a live instance across threads. */
    public Waypoint copy() {
        final Waypoint copy = new Waypoint(
            id, name, dimensionId, x, y, z, colorArgb, group, visible,
            crossDimensionVisible, type, createdAtEpochMs
        );
        copy.iconItemId = iconItemId;
        copy.markerLabel = markerLabel;
        copy.description = description;
        copy.imageId = imageId;
        copy.crossDimensionY = crossDimensionY;
        return copy;
    }
}
