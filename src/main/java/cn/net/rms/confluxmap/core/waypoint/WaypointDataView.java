package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Read-only waypoint data boundary for future overlays such as a waypoint HUD.
 * Consumers receive immutable value snapshots and never a live {@link Waypoint}
 * or mutable {@link WaypointStore} reference.
 */
public interface WaypointDataView {
    /**
     * Captures values on the owning client thread. The returned snapshot is
     * immutable and may be handed to render or HUD consumers afterwards.
     */
    Snapshot snapshot();

    record Snapshot(WorldIdentity world, List<WaypointSet> sets, List<Entry> waypoints) {
        public Snapshot {
            Objects.requireNonNull(world, "world");
            sets = List.copyOf(sets);
            waypoints = List.copyOf(waypoints);
        }

        public static Snapshot empty() {
            return new Snapshot(WorldIdentity.NONE, List.of(WaypointSet.DEFAULT), List.of());
        }
    }

    record Entry(
        UUID id,
        String name,
        DimensionId dimensionId,
        double x,
        double y,
        double z,
        int colorArgb,
        String setName,
        boolean visible,
        Waypoint.Type type,
        long createdAtEpochMs
    ) {
        public Entry {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(dimensionId, "dimensionId");
            Objects.requireNonNull(setName, "setName");
            Objects.requireNonNull(type, "type");
        }

        static Entry from(final Waypoint waypoint) {
            return new Entry(
                waypoint.id, waypoint.name, waypoint.dimensionId,
                waypoint.x, waypoint.y, waypoint.z, waypoint.colorArgb,
                waypoint.group, waypoint.visible, waypoint.type, waypoint.createdAtEpochMs
            );
        }
    }
}
