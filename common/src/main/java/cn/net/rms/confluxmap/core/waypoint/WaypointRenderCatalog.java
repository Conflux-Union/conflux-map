package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.shared.SharedWaypoint;
import cn.net.rms.confluxmap.core.shared.SharedWaypointLocationKey;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
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
        return merge(
            local, shared, config.localWaypointsVisible, config.sharedWaypointsVisible,
            config::isSharedWaypointCrossDimensionVisible
        );
    }

    /**
     * Immutable render-ready snapshot of every waypoint visible from the requested dimension:
     * exact-dimension entries plus portal-linked entries whose per-waypoint cross-dimension
     * opt-in allows it, with horizontal coordinates converted into the requested dimension's
     * coordinate space so renderers can use x/z as plain world positions, while
     * {@link WaypointRenderEntry#dimensionId()} keeps the stored dimension for labels and
     * store lookups. When the management screen pins a display dimension
     * (see {@link ConfluxConfig#waypointViewDimension}), that dimension's set replaces the
     * current one entirely, projected the same way.
     */
    public List<WaypointRenderEntry> snapshot(final DimensionId dimension) {
        final DimensionId pinned = config.waypointViewDimensionOrNull();
        if (pinned == null) {
            return visibleFrom(snapshot(), dimension);
        }
        return viewFrom(snapshot(), pinned, dimension);
    }

    /** Pure visibility filter and coordinate conversion kept public for deterministic unit coverage. */
    public static List<WaypointRenderEntry> visibleFrom(
        final List<WaypointRenderEntry> entries,
        final DimensionId dimension
    ) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(dimension, "dimension");
        final List<WaypointRenderEntry> matching = new ArrayList<>(entries.size());
        for (final WaypointRenderEntry entry : entries) {
            if (entry.dimensionId().equals(dimension)) {
                matching.add(entry);
                continue;
            }
            if (!entry.crossDimensionVisible()
                || !DimensionScale.isVisibleFrom(entry.dimensionId(), dimension)) {
                continue;
            }
            matching.add(projected(entry, dimension));
        }
        return List.copyOf(matching);
    }

    /**
     * Pure pinned-view projection kept public for deterministic unit coverage: the entries
     * stored in {@code viewDimension}, and nothing else, projected into
     * {@code currentDimension}'s coordinate space. The per-waypoint cross-dimension opt-out is
     * deliberately ignored here - pinning a dimension in the management screen is an explicit
     * request to see that dimension's whole set from the other side - and every projected
     * entry is marked cross-dimension so renderers keep treating it as foreign. Two cases
     * degrade to the plain visibility filter instead: a view dimension with no portal
     * correlation to {@code currentDimension} (the End, modded dimensions) would place raw
     * coordinates meaninglessly on the map, and a pin that owns no entries here - typically
     * carried over from another world - would otherwise blank every render surface.
     */
    public static List<WaypointRenderEntry> viewFrom(
        final List<WaypointRenderEntry> entries,
        final DimensionId viewDimension,
        final DimensionId currentDimension
    ) {
        Objects.requireNonNull(entries, "entries");
        Objects.requireNonNull(viewDimension, "viewDimension");
        Objects.requireNonNull(currentDimension, "currentDimension");
        if (viewDimension.equals(currentDimension)
            || !DimensionScale.isVisibleFrom(viewDimension, currentDimension)) {
            return visibleFrom(entries, currentDimension);
        }
        final List<WaypointRenderEntry> matching = new ArrayList<>(entries.size());
        for (final WaypointRenderEntry entry : entries) {
            if (entry.dimensionId().equals(viewDimension)) {
                matching.add(projected(entry, currentDimension));
            }
        }
        return matching.isEmpty()
            ? visibleFrom(entries, currentDimension)
            : List.copyOf(matching);
    }

    /**
     * One entry as seen from {@code targetDimension}'s coordinate space: horizontal
     * coordinates converted, {@link WaypointRenderEntry#crossDimensionVisible() } set so
     * renderers keep treating it as foreign. Stored dimension is retained for labels and
     * store lookups; Y is never scaled.
     */
    private static WaypointRenderEntry projected(
        final WaypointRenderEntry entry,
        final DimensionId targetDimension
    ) {
        return new WaypointRenderEntry(
            entry.id(),
            entry.name(),
            entry.dimensionId(),
            DimensionScale.convertHorizontal(entry.x(), entry.dimensionId(), targetDimension),
            entry.y(),
            DimensionScale.convertHorizontal(entry.z(), entry.dimensionId(), targetDimension),
            entry.colorArgb(),
            entry.iconItemId(),
            entry.markerLabel(),
            entry.type(),
            entry.source(),
            true
        );
    }

    /** Pure merge function kept public for deterministic unit coverage. */
    public static List<WaypointRenderEntry> merge(
        final List<Waypoint> localWaypoints,
        final List<SharedWaypoint> sharedWaypoints,
        final boolean localVisible,
        final boolean sharedVisible
    ) {
        return merge(localWaypoints, sharedWaypoints, localVisible, sharedVisible, ignored -> false);
    }

    public static List<WaypointRenderEntry> merge(
        final List<Waypoint> localWaypoints,
        final List<SharedWaypoint> sharedWaypoints,
        final boolean localVisible,
        final boolean sharedVisible,
        final Predicate<UUID> sharedCrossDimensionVisible
    ) {
        Objects.requireNonNull(localWaypoints, "localWaypoints");
        Objects.requireNonNull(sharedWaypoints, "sharedWaypoints");
        Objects.requireNonNull(sharedCrossDimensionVisible, "sharedCrossDimensionVisible");
        final List<WaypointRenderEntry> entries = new ArrayList<>(localWaypoints.size() + sharedWaypoints.size());
        // Publishing a waypoint stores a server-side copy under a fresh id, so the publisher
        // (and anyone who kept a private point at the same block) otherwise renders two labels
        // at one spot - and the aim/highlight animation is per entry, so exactly one of the two
        // expands while the other stays collapsed. The server already keys shared waypoints by
        // this block; the render side collapses onto the local entry with the same rule.
        final Set<SharedWaypointLocationKey> renderedBlocks = new HashSet<>(localWaypoints.size() * 2);

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
                        waypoint.iconItemId,
                        waypoint.markerLabel,
                        waypoint.type,
                        WaypointRenderEntry.Source.LOCAL,
                        waypoint.crossDimensionVisible
                    ));
                    final SharedWaypointLocationKey block = blockKeyOrNull(
                        waypoint.dimensionId, waypoint.x, waypoint.y, waypoint.z
                    );
                    if (block != null) {
                        renderedBlocks.add(block);
                    }
                }
            }
        }
        if (sharedVisible) {
            for (final SharedWaypoint waypoint : sharedWaypoints) {
                final SharedWaypointLocationKey block = blockKeyOrNull(
                    waypoint.dimensionId(), waypoint.x(), waypoint.y(), waypoint.z()
                );
                if (block != null && !renderedBlocks.add(block)) {
                    continue;
                }
                entries.add(new WaypointRenderEntry(
                    waypoint.id(),
                    waypoint.name(),
                    waypoint.dimensionId(),
                    waypoint.x(),
                    waypoint.y(),
                    waypoint.z(),
                    waypoint.colorArgb(),
                    waypoint.iconItemId(),
                    waypoint.markerLabel(),
                    waypoint.type(),
                    WaypointRenderEntry.Source.SHARED,
                    sharedCrossDimensionVisible.test(waypoint.id())
                ));
            }
        }
        return List.copyOf(entries);
    }

    /**
     * {@link SharedWaypointLocationKey#from} rejects non-finite or out-of-range coordinates;
     * such a waypoint simply renders without collapsing (same as before this dedup existed)
     * instead of failing the whole HUD snapshot.
     */
    private static SharedWaypointLocationKey blockKeyOrNull(
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z
    ) {
        try {
            return SharedWaypointLocationKey.from(dimensionId, x, y, z);
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }
}
