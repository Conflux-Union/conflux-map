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
    private final Supplier<List<SiblingWaypoint>> siblingWaypoints;
    private final ConfluxConfig config;

    public WaypointRenderCatalog(
        final WaypointService localWaypoints,
        final Supplier<List<SharedWaypoint>> sharedWaypoints,
        final ConfluxConfig config
    ) {
        this(localWaypoints, sharedWaypoints, List::of, config);
    }

    public WaypointRenderCatalog(
        final WaypointService localWaypoints,
        final Supplier<List<SharedWaypoint>> sharedWaypoints,
        final Supplier<List<SiblingWaypoint>> siblingWaypoints,
        final ConfluxConfig config
    ) {
        this.localWaypoints = Objects.requireNonNull(localWaypoints, "localWaypoints");
        this.sharedWaypoints = Objects.requireNonNull(sharedWaypoints, "sharedWaypoints");
        this.siblingWaypoints = Objects.requireNonNull(siblingWaypoints, "siblingWaypoints");
        this.config = Objects.requireNonNull(config, "config");
    }

    /** Immutable render-ready snapshot using the current visibility settings. */
    public List<WaypointRenderEntry> snapshot() {
        final List<Waypoint> local = config.localWaypointsVisible ? localWaypoints.list() : List.of();
        final List<SharedWaypoint> shared = config.sharedWaypointsVisible ? sharedWaypoints.get() : List.of();
        final List<SiblingWaypoint> siblings =
            config.localWaypointsVisible && config.crossWorldWaypointsVisible
                ? siblingWaypoints.get()
                : List.of();
        return merge(
            local, shared, siblings, config.localWaypointsVisible, config.sharedWaypointsVisible,
            config::isSharedWaypointCrossDimensionVisible
        );
    }

    /**
     * Immutable render-ready snapshot of every waypoint visible from the
     * requested dimension. Entries that allow cross-dimension display are
     * included per {@link DimensionScale#isVisibleFrom}
     * with their horizontal coordinates converted into the requested dimension's
     * coordinate space, so renderers can use x/z as plain world positions;
     * {@link WaypointRenderEntry#dimensionId()} keeps the stored dimension for
     * labels and store lookups. Otherwise only exact-dimension entries appear.
     */
    public List<WaypointRenderEntry> snapshot(final DimensionId dimension) {
        return visibleFrom(snapshot(), dimension);
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
            matching.add(new WaypointRenderEntry(
                entry.id(),
                entry.name(),
                entry.dimensionId(),
                DimensionScale.convertHorizontal(entry.x(), entry.dimensionId(), dimension),
                entry.y(),
                DimensionScale.convertHorizontal(entry.z(), entry.dimensionId(), dimension),
                entry.colorArgb(),
                entry.iconItemId(),
                entry.markerLabel(),
                entry.type(),
                entry.source(),
                true,
                entry.originWorldLabel()
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
        return merge(
            localWaypoints, sharedWaypoints, List.of(), localVisible, sharedVisible,
            ignored -> false
        );
    }

    public static List<WaypointRenderEntry> merge(
        final List<Waypoint> localWaypoints,
        final List<SharedWaypoint> sharedWaypoints,
        final boolean localVisible,
        final boolean sharedVisible,
        final Predicate<UUID> sharedCrossDimensionVisible
    ) {
        return merge(
            localWaypoints, sharedWaypoints, List.of(), localVisible, sharedVisible,
            sharedCrossDimensionVisible
        );
    }

    public static List<WaypointRenderEntry> merge(
        final List<Waypoint> localWaypoints,
        final List<SharedWaypoint> sharedWaypoints,
        final List<SiblingWaypoint> siblingWaypoints,
        final boolean localVisible,
        final boolean sharedVisible,
        final Predicate<UUID> sharedCrossDimensionVisible
    ) {
        Objects.requireNonNull(localWaypoints, "localWaypoints");
        Objects.requireNonNull(sharedWaypoints, "sharedWaypoints");
        Objects.requireNonNull(siblingWaypoints, "siblingWaypoints");
        Objects.requireNonNull(sharedCrossDimensionVisible, "sharedCrossDimensionVisible");
        final List<WaypointRenderEntry> entries = new ArrayList<>(
            localWaypoints.size() + sharedWaypoints.size() + siblingWaypoints.size()
        );
        // Publishing a waypoint stores a server-side copy under a fresh id, so the publisher
        // (and anyone who kept a private point at the same block) otherwise renders two labels
        // at one spot - and the aim/highlight animation is per entry, so exactly one of the two
        // expands while the other stays collapsed. The server already keys shared waypoints by
        // this block; the render side collapses onto the local entry with the same rule.
        // Sibling entries join the same collapse last, so the current world's own points always
        // win a block contested with a seed-sibling world's copy.
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
        for (final SiblingWaypoint sibling : siblingWaypoints) {
            final Waypoint waypoint = sibling.waypoint();
            if (!waypoint.visible) {
                continue;
            }
            final SharedWaypointLocationKey block = blockKeyOrNull(
                waypoint.dimensionId, waypoint.x, waypoint.y, waypoint.z
            );
            if (block != null && !renderedBlocks.add(block)) {
                continue;
            }
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
                WaypointRenderEntry.Source.SIBLING,
                waypoint.crossDimensionVisible,
                sibling.worldLabel()
            ));
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
