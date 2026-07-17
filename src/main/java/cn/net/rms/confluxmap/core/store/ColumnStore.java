package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SampleSource;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Column samples for one dimension+layer, grouped into 256x256-column regions
 * (one region backs one LOD-0 tile). Writes obey the source priority rule:
 * higher-priority data always wins, lower priority only fills UNKNOWN chunks.
 * Thread-safe: region cells are guarded by the region monitor.
 */
public final class ColumnStore {
    private final ConcurrentHashMap<Long, RegionColumns> regions = new ConcurrentHashMap<>();

    private static long key(final int regionX, final int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    /**
     * Merge a chunk snapshot. Returns true if the region changed (i.e. the
     * write was not discarded by the priority rule).
     */
    public boolean put(final ChunkSnapshot snapshot, final SampleSource source) {
        final int regionX = snapshot.chunkX >> 4;
        final int regionZ = snapshot.chunkZ >> 4;
        final RegionColumns region = regions.computeIfAbsent(key(regionX, regionZ), k -> new RegionColumns(regionX, regionZ));
        return region.putChunk(snapshot, source);
    }

    /** The region backing the LOD-0 tile at (regionX, regionZ), or null if untouched. */
    public RegionColumns region(final int regionX, final int regionZ) {
        return regions.get(key(regionX, regionZ));
    }

    public int regionCount() {
        return regions.size();
    }

    /**
     * Live, weakly-consistent view of every region currently in memory. Safe to
     * iterate while concurrently calling {@link #remove} (including removing the
     * element the iterator is currently on) - {@link ConcurrentHashMap}'s views
     * never throw {@code ConcurrentModificationException}.
     */
    public Collection<RegionColumns> allRegions() {
        return regions.values();
    }

    /**
     * Drops a region from memory, e.g. for distance-based eviction. Callers must
     * have already flushed any unwritten data first - this never touches disk.
     */
    public boolean remove(final int regionX, final int regionZ) {
        return regions.remove(key(regionX, regionZ)) != null;
    }

    public void clear() {
        regions.clear();
    }
}
