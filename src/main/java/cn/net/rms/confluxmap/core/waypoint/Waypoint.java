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
 * on display only when a waypoint is viewed from a different dimension.
 */
public final class Waypoint {
    public enum Type { NORMAL, DEATH }

    public final UUID id;
    public String name;
    public DimensionId dimensionId;
    public double x;
    public double y;
    public double z;
    public int colorArgb;
    public String group;
    public boolean visible;
    public Type type;
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
        this.type = Objects.requireNonNull(type, "type");
        this.createdAtEpochMs = createdAtEpochMs;
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
            UUID.randomUUID(), name, dimensionId, x, y, z, colorArgb, group, true, type, System.currentTimeMillis()
        );
    }

    /** Deep (field-for-field, but always independent) copy. Never share a live instance across threads. */
    public Waypoint copy() {
        return new Waypoint(id, name, dimensionId, x, y, z, colorArgb, group, visible, type, createdAtEpochMs);
    }
}
