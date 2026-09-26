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

    /**
     * Immutable render-ready snapshot using the current visibility settings, collapsed to
     * one entry per stored block in the entry's own dimension. Prefer
     * {@link #snapshot(DimensionId)} whenever the viewing dimension is known: cross-dimension
     * visibility is decided per entry with different data per source, so the per-view collapse
     * there can keep a shared copy that this own-dimension collapse drops.
     */
    public List<WaypointRenderEntry> snapshot() {
        return collapseByStoredBlock(uncollapsedSnapshot());
    }

    /**
     * Immutable render-ready snapshot of every waypoint visible from the
     * requested dimension. Entries that allow cross-dimension display are
     * included per {@link DimensionScale#isVisibleFrom}
     * with their horizontal coordinates converted into the requested dimension's
     * coordinate space, so renderers can use x/z as plain world positions;
     * {@link WaypointRenderEntry#dimensionId()} keeps the stored dimension for
     * labels and store lookups. Otherwise only exact-dimension entries appear.
     * Copies collapsed onto the same rendered block keep the first entry, which
     * the merge emits in local &gt; shared &gt; sibling priority order, so a block is
     * visible from a dimension whenever any copy at it allows cross-dimension
     * display.
     */
    public List<WaypointRenderEntry> snapshot(final DimensionId dimension) {
        return visibleFrom(uncollapsedSnapshot(), dimension);
    }

    private List<WaypointRenderEntry> uncollapsedSnapshot() {
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
        // Cross-dimension visibility is decided per entry with different data per source
        // (the local waypoint's own flag vs the per-id shared allowlist), so the block
        // collapse must run after this filter: collapsing earlier let a local entry that
        // cannot render from the viewed dimension mask a shared copy that can.
        return collapseByRenderedBlock(matching);
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
        // Emission order is the collapse priority used by the snapshot methods: local
        // entries first, then shared, then seed-sibling copies, so the current world's own
        // point always wins a block contested with a server-owned or sibling copy. The
        // merge itself keeps every visible entry because which copy renders depends on the
        // viewing dimension and each entry's own cross-dimension preference.

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
     * One entry per stored block in its own dimension. Publishing a waypoint stores a
     * server-side copy under a fresh id, so the publisher (and anyone who kept a private
     * point at the same block) otherwise renders two labels at one spot - and the
     * aim/highlight animation is per entry, so exactly one of the two expands while the
     * other stays collapsed. The server already keys shared waypoints by this block.
     */
    private static List<WaypointRenderEntry> collapseByStoredBlock(final List<WaypointRenderEntry> entries) {
        if (entries.size() < 2) {
            return List.copyOf(entries);
        }
        final Set<SharedWaypointLocationKey> renderedBlocks = new HashSet<>(entries.size() * 2);
        final List<WaypointRenderEntry> out = new ArrayList<>(entries.size());
        for (final WaypointRenderEntry entry : entries) {
            final SharedWaypointLocationKey block = blockKeyOrNull(
                entry.dimensionId(), entry.x(), entry.y(), entry.z()
            );
            if (block == null || renderedBlocks.add(block)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    /**
     * Same collapse keyed by the coordinates the entries render at in the requested
     * dimension, so entries whose source blocks converge through coordinate conversion
     * (for example two Overworld blocks mapping to one Nether block) also collapse.
     */
    private static List<WaypointRenderEntry> collapseByRenderedBlock(final List<WaypointRenderEntry> entries) {
        if (entries.size() < 2) {
            return List.copyOf(entries);
        }
        final Set<RenderedBlock> renderedBlocks = new HashSet<>(entries.size() * 2);
        final List<WaypointRenderEntry> out = new ArrayList<>(entries.size());
        for (final WaypointRenderEntry entry : entries) {
            final RenderedBlock block = renderedBlockOrNull(entry.x(), entry.y(), entry.z());
            if (block == null || renderedBlocks.add(block)) {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }

    private record RenderedBlock(long blockX, long blockY, long blockZ) {
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

    private static RenderedBlock renderedBlockOrNull(final double x, final double y, final double z) {
        try {
            return new RenderedBlock(floorToLong(x), floorToLong(y), floorToLong(z));
        } catch (final IllegalArgumentException e) {
            return null;
        }
    }

    private static long floorToLong(final double coordinate) {
        final double floored = Math.floor(coordinate);
        if (!Double.isFinite(floored) || floored < Long.MIN_VALUE || floored > Long.MAX_VALUE) {
            throw new IllegalArgumentException("rendered coordinate is outside the supported range");
        }
        return (long) floored;
    }
}
