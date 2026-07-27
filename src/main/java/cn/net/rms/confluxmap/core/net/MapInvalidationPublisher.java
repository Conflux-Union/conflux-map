package cn.net.rms.confluxmap.core.net;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Tracks one current map viewport per player and coalesces source-change invalidations. */
public final class MapInvalidationPublisher {
    private final Map<UUID, Subscription> subscriptions = new HashMap<>();

    public synchronized boolean subscribe(final UUID player, final MapSyncSubscribeC2S request) {
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
        if (previous != null
            && previous.request.dimIndex() == request.dimIndex()
            && previous.request.lod() == request.lod()) {
            copyVisible(previous.pending, replacement.pending, request);
            copyVisible(previous.notified, replacement.notified, request);
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

    public synchronized void invalidateRegion(final int dimIndex, final int regionX, final int regionZ) {
        for (final Subscription subscription : subscriptions.values()) {
            if (subscription.request.dimIndex() != dimIndex) {
                continue;
            }
            final int regionsPerTile = 1 << subscription.request.lod();
            invalidate(
                subscription,
                Math.floorDiv(regionX, regionsPerTile),
                Math.floorDiv(regionZ, regionsPerTile)
            );
        }
    }

    public synchronized MapInvalidateS2C poll(final UUID player) {
        final Subscription subscription = subscriptions.get(player);
        if (subscription == null || subscription.pending.isEmpty()) {
            return null;
        }
        final List<MapInvalidateS2C.Tile> tiles = new ArrayList<>(Math.min(
            subscription.pending.size(), Proto.MAX_MAP_INVALIDATION_TILES
        ));
        final java.util.Iterator<MapInvalidateS2C.Tile> iterator = subscription.pending.iterator();
        while (iterator.hasNext() && tiles.size() < Proto.MAX_MAP_INVALIDATION_TILES) {
            final MapInvalidateS2C.Tile tile = iterator.next();
            iterator.remove();
            subscription.notified.add(tile);
            tiles.add(tile);
        }
        return new MapInvalidateS2C(
            subscription.request.dimIndex(), subscription.request.lod(), tiles
        );
    }

    /** A correction request acknowledges the invalidation and permits a later source change through. */
    public synchronized void acknowledge(final UUID player, final MapViewReqC2S request) {
        final Subscription subscription = subscriptions.get(player);
        if (subscription == null
            || subscription.request.dimIndex() != request.dimIndex()
            || subscription.request.lod() != request.lod()) {
            return;
        }
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            final MapInvalidateS2C.Tile key = new MapInvalidateS2C.Tile(tile.tileX(), tile.tileZ());
            subscription.pending.remove(key);
            subscription.notified.remove(key);
        }
    }

    public synchronized boolean watches(
        final int dimIndex, final int lod, final int tileX, final int tileZ
    ) {
        for (final Subscription subscription : subscriptions.values()) {
            if (subscription.request.dimIndex() == dimIndex && subscription.request.lod() == lod
                && contains(subscription.request, tileX, tileZ)) {
                return true;
            }
        }
        return false;
    }

    private static void invalidate(final Subscription subscription, final int tileX, final int tileZ) {
        final MapInvalidateS2C.Tile tile = new MapInvalidateS2C.Tile(tileX, tileZ);
        if (contains(subscription.request, tileX, tileZ) && !subscription.notified.contains(tile)) {
            subscription.pending.add(tile);
        }
    }

    private static boolean valid(final MapSyncSubscribeC2S request) {
        if (request.dimIndex() < 0 || request.dimIndex() >= Proto.MAX_DIM_ENTRIES
            || request.lod() < 0 || request.lod() > cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD
            || request.minTileX() > request.maxTileX() || request.minTileZ() > request.maxTileZ()) {
            return false;
        }
        final long width = (long) request.maxTileX() - request.minTileX() + 1L;
        final long height = (long) request.maxTileZ() - request.minTileZ() + 1L;
        return width <= Proto.MAX_MAP_SYNC_VIEW_TILES
            && height <= Proto.MAX_MAP_SYNC_VIEW_TILES
            && width * height <= Proto.MAX_MAP_SYNC_VIEW_TILES;
    }

    private static boolean contains(
        final MapSyncSubscribeC2S request, final int tileX, final int tileZ
    ) {
        return tileX >= request.minTileX() && tileX <= request.maxTileX()
            && tileZ >= request.minTileZ() && tileZ <= request.maxTileZ();
    }

    private static void copyVisible(
        final Set<MapInvalidateS2C.Tile> source,
        final Set<MapInvalidateS2C.Tile> target,
        final MapSyncSubscribeC2S request
    ) {
        for (final MapInvalidateS2C.Tile tile : source) {
            if (contains(request, tile.tileX(), tile.tileZ())) {
                target.add(tile);
            }
        }
    }

    private static final class Subscription {
        final MapSyncSubscribeC2S request;
        final Set<MapInvalidateS2C.Tile> pending = new LinkedHashSet<>();
        final Set<MapInvalidateS2C.Tile> notified = new LinkedHashSet<>();

        Subscription(final MapSyncSubscribeC2S request) {
            this.request = request;
        }
    }
}
