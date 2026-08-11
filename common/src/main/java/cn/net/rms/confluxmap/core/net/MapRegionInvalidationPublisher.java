package cn.net.rms.confluxmap.core.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Coalesces source-region changes for one exact chunk viewport per player. */
public final class MapRegionInvalidationPublisher {
    private final Map<UUID, Subscription> subscriptions = new HashMap<>();

    public synchronized boolean subscribe(
        final UUID player, final MapRegionSyncSubscribeC2S request
    ) {
        if (player == null || request == null) {
            return false;
        }
        if (!request.active()) {
            subscriptions.remove(player);
            return true;
        }
        if (!valid(request)) {
            return false;
        }
        final Subscription previous = subscriptions.get(player);
        final Subscription replacement = new Subscription(request);
        if (previous != null && previous.request.dimIndex() == request.dimIndex()
            && previous.request.lod() == request.lod()) {
            copyVisible(previous.pending, replacement.pending, request);
        }
        subscriptions.put(player, replacement);
        return true;
    }

    public synchronized void remove(final UUID player) {
        subscriptions.remove(player);
    }

    public synchronized void clear() {
        subscriptions.clear();
    }

    public synchronized void invalidateRegion(
        final int dimIndex, final int regionX, final int regionZ
    ) {
        final MapRegionInvalidateS2C.Region region = new MapRegionInvalidateS2C.Region(regionX, regionZ);
        for (final Subscription subscription : subscriptions.values()) {
            if (subscription.request.dimIndex() == dimIndex
                && contains(subscription.request, region)) {
                subscription.pending.add(region);
            }
        }
    }

    public synchronized MapRegionInvalidateS2C poll(final UUID player) {
        final Subscription subscription = subscriptions.get(player);
        if (subscription == null || subscription.pending.isEmpty()) {
            return null;
        }
        final List<MapRegionInvalidateS2C.Region> regions = new ArrayList<>(Math.min(
            subscription.pending.size(), Proto.MAX_REGION_INVALIDATIONS
        ));
        final java.util.Iterator<MapRegionInvalidateS2C.Region> iterator =
            subscription.pending.iterator();
        while (iterator.hasNext() && regions.size() < Proto.MAX_REGION_INVALIDATIONS) {
            final MapRegionInvalidateS2C.Region region = iterator.next();
            iterator.remove();
            regions.add(region);
        }
        return new MapRegionInvalidateS2C(
            subscription.request.dimIndex(), subscription.request.lod(), regions
        );
    }

    public synchronized void acknowledge(
        final UUID player, final MapRegionViewReqC2S request
    ) {
        final Subscription subscription = subscriptions.get(player);
        if (subscription == null || subscription.request.dimIndex() != request.dimIndex()
            || subscription.request.lod() != request.lod()) {
            return;
        }
        for (final MapRegionViewReqC2S.RegionReq region : request.regions()) {
            final MapRegionInvalidateS2C.Region key = new MapRegionInvalidateS2C.Region(
                region.regionX(), region.regionZ()
            );
            subscription.pending.remove(key);
        }
    }

    /** A web tile refresh acknowledges every source region covered by that tile. */
    public synchronized void acknowledge(final UUID player, final MapViewReqC2S request) {
        final Subscription subscription = subscriptions.get(player);
        if (subscription == null
            || subscription.request.dimIndex() != request.dimIndex()
            || subscription.request.lod() != request.lod()) {
            return;
        }
        final int regionsPerTile = 1 << request.lod();
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            final long baseX = (long) tile.tileX() * regionsPerTile;
            final long baseZ = (long) tile.tileZ() * regionsPerTile;
            for (int dz = 0; dz < regionsPerTile; dz++) {
                for (int dx = 0; dx < regionsPerTile; dx++) {
                    final long regionX = baseX + dx;
                    final long regionZ = baseZ + dz;
                    if (regionX < Integer.MIN_VALUE || regionX > Integer.MAX_VALUE
                        || regionZ < Integer.MIN_VALUE || regionZ > Integer.MAX_VALUE) {
                        continue;
                    }
                    final MapRegionInvalidateS2C.Region key =
                        new MapRegionInvalidateS2C.Region((int) regionX, (int) regionZ);
                    subscription.pending.remove(key);
                }
            }
        }
    }

    public synchronized boolean watches(
        final int dimIndex, final int lod, final int regionX, final int regionZ
    ) {
        final MapRegionInvalidateS2C.Region region = new MapRegionInvalidateS2C.Region(regionX, regionZ);
        for (final Subscription subscription : subscriptions.values()) {
            if (subscription.request.dimIndex() == dimIndex
                && subscription.request.lod() == lod
                && contains(subscription.request, region)) {
                return true;
            }
        }
        return false;
    }

    private static boolean valid(final MapRegionSyncSubscribeC2S request) {
        if (request.dimIndex() < 0 || request.dimIndex() >= Proto.MAX_DIM_ENTRIES
            || request.lod() < 0 || request.lod() > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD
            || request.minChunkX() > request.maxChunkX()
            || request.minChunkZ() > request.maxChunkZ()) {
            return false;
        }
        final long width = (long) request.maxChunkX() - request.minChunkX() + 1L;
        final long height = (long) request.maxChunkZ() - request.minChunkZ() + 1L;
        return width <= Proto.MAX_REGION_SYNC_SPAN_CHUNKS
            && height <= Proto.MAX_REGION_SYNC_SPAN_CHUNKS;
    }

    private static boolean contains(
        final MapRegionSyncSubscribeC2S request,
        final MapRegionInvalidateS2C.Region region
    ) {
        final long minX = (long) region.regionX() * 16L;
        final long minZ = (long) region.regionZ() * 16L;
        final long maxX = minX + 15L;
        final long maxZ = minZ + 15L;
        return minX <= request.maxChunkX() && maxX >= request.minChunkX()
            && minZ <= request.maxChunkZ() && maxZ >= request.minChunkZ();
    }

    private static void copyVisible(
        final Set<MapRegionInvalidateS2C.Region> source,
        final Set<MapRegionInvalidateS2C.Region> target,
        final MapRegionSyncSubscribeC2S request
    ) {
        for (final MapRegionInvalidateS2C.Region region : source) {
            if (contains(request, region)) {
                target.add(region);
            }
        }
    }

    private static final class Subscription {
        final MapRegionSyncSubscribeC2S request;
        final Set<MapRegionInvalidateS2C.Region> pending = new LinkedHashSet<>();

        Subscription(final MapRegionSyncSubscribeC2S request) {
            this.request = request;
        }
    }
}
