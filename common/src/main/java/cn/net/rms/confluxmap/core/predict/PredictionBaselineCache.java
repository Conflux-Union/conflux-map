package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.LinkedHashMap;

/**
 * Bounded, exact native baselines independent of server corrections and their validation times.
 * Only directly sampled planes enter this cache; reduced child coverage must remain in the mip
 * cache, whose correction invalidation and freshness rules are deliberately separate.
 */
final class PredictionBaselineCache {
    static final int MAX_TILES = 64;

    record Key(
        TileKey tile, long token, long generation, long seed, int version,
        WorldPreset preset, FlatBaseline flat, QuadrantLayout quadrants, int netherY
    ) {
    }

    /** Arrays are shared with the immutable planar tile and never modified by a cache reader. */
    record Planes(short[] surfaceKind, byte[] fluidDepth, byte[] biomes) {
    }

    private final LinkedHashMap<Key, Planes> entries = new LinkedHashMap<>(16, 0.75f, true);
    private int limit;

    PredictionBaselineCache(final int limit) {
        setLimit(limit);
    }

    synchronized Planes get(final Key key) {
        return entries.get(key);
    }

    synchronized void put(final Key key, final PredictionMipCache.Tile tile) {
        if (tile != null) {
            entries.put(key, new Planes(tile.packedSurfaceKind(), tile.fluidDepth(), tile.biomes()));
            trim();
        }
    }

    synchronized void setLimit(final int requestedLimit) {
        limit = Math.max(1, Math.min(MAX_TILES, requestedLimit));
        trim();
    }

    synchronized void clear() {
        entries.clear();
    }

    synchronized int size() {
        return entries.size();
    }

    private void trim() {
        while (entries.size() > limit) {
            entries.remove(entries.keySet().iterator().next());
        }
    }
}
