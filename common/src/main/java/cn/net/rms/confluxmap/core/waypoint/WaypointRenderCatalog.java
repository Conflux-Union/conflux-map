package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Read-only join of client-owned and server-owned waypoints for rendering.
 * It never exposes a {@link SharedWaypoint} as a mutable {@link Waypoint}, so
 * renderers cannot accidentally write public data into {@link WaypointStore}.
 */
public final class WaypointRenderCatalog {
    private final WaypointService localWaypoints;
    private final Supplier<List<SharedWaypoint>> sharedWaypoints;
    private final ConfluxConfig config;

    public WaypointRenderCatalog(
        final WaypointService localWaypoints,
        final Supplier<List<SharedWaypoint>> sharedWaypoints,
        final ConfluxConfig config
    ) {
        this.localWaypoints = Objects.requireNonNull(localWaypoints, "localWaypoints");
        this.sharedWaypoints = Objects.requireNonNull(sharedWaypoints, "sharedWaypoints");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Immutable render-ready snapshot using the current visibility settings. */
    public List<WaypointRenderEntry> snapshot() {
        final List<Waypoint> local = config.localWaypointsVisible ? localWaypoints.list() : List.of();
        final List<SharedWaypoint> shared = config.sharedWaypointsVisible ? sharedWaypoints.get() : List.of();
        return merge(local, shared, config.localWaypointsVisible, config.sharedWaypointsVisible);
    }

    /**
     * Immutable render-ready snapshot of every waypoint visible from the
     * requested dimension. With cross-dimension display enabled
     * ({@link ConfluxConfig#waypointCrossDimensionEnabled}, off by default)
     * portal-linked entries are included per {@link DimensionScale#isVisibleFrom}
     * with their horizontal coordinates converted into the requested dimension's
     * coordinate space, so renderers can use x/z as plain world positions;
     * {@link WaypointRenderEntry#dimensionId()} keeps the stored dimension for
     * labels and store lookups. Otherwise only exact-dimension entries appear.
     */
    public List<WaypointRenderEntry> snapshot(final DimensionId dimension) {
        return visibleFrom(snapshot(), dimension, config.waypointCrossDimensionEnabled);
    }

    /** Pure visibility filter and coordinate conversion kept public for deterministic unit coverage. */
    public static List<WaypointRenderEntry> visibleFrom(
        final List<WaypointRenderEntry> entries,
        final DimensionId dimension,
        final boolean crossDimension
    ) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(dimension, "dimension");
        final List<WaypointRenderEntry> matching = new ArrayList<>(entries.size());
        for (final WaypointRenderEntry entry : entries) {
            if (entry.dimensionId().equals(dimension)) {
                matching.add(entry);
                continue;
            }
            if (!crossDimension || !DimensionScale.isVisibleFrom(entry.dimensionId(), dimension)) {
                continue;
            }
            matching.add(new WaypointRenderEntry(
                entry.id(),
                entry.name(),
                entry.dimensionId(),
                DimensionScale.convertHorizontal(entry.x(), entry.dimensionId(), dimension),
                entry.y(),
                DimensionScale.convertHorizontal(entry.z(), entry.dimensionId(), dimension),
                entry.colorArgb(),
                entry.type(),
                entry.source(),
                entry.locked()
            ));
        }
        return List.copyOf(matching);
    }

    /** Pure merge function kept public for deterministic unit coverage. */
    public static List<WaypointRenderEntry> merge(
        final List<Waypoint> localWaypoints,
        final List<SharedWaypoint> sharedWaypoints,
        final boolean localVisible,
        final boolean sharedVisible
    ) {
        Objects.requireNonNull(localWaypoints, "localWaypoints");
        Objects.requireNonNull(sharedWaypoints, "sharedWaypoints");
        final List<WaypointRenderEntry> entries = new ArrayList<>(localWaypoints.size() + sharedWaypoints.size());

        if (localVisible) {
            for (final Waypoint waypoint : localWaypoints) {
                if (waypoint.visible) {
                    entries.add(new WaypointRenderEntry(
                        waypoint.id,
                        waypoint.name,
                        waypoint.dimensionId,
                        waypoint.x,
                        waypoint.y,
                        waypoint.z,
                        waypoint.colorArgb,
                        waypoint.type,
                        WaypointRenderEntry.Source.LOCAL,
                        false
                    ));
                }
            }
        }
        if (sharedVisible) {
            for (final SharedWaypoint waypoint : sharedWaypoints) {
                entries.add(new WaypointRenderEntry(
                    waypoint.id(),
                    waypoint.name(),
                    waypoint.dimensionId(),
                    waypoint.x(),
                    waypoint.y(),
                    waypoint.z(),
                    waypoint.colorArgb(),
                    waypoint.type(),
                    WaypointRenderEntry.Source.SHARED,
                    waypoint.locked()
                ));
            }
        }
        return List.copyOf(entries);
    }
}
