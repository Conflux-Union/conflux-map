package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SampleSource;
import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Column samples for one dimension+layer, grouped into 256x256-column regions
 * (one region backs one LOD-0 tile). Writes obey the source priority rule:
 * higher-priority data always wins, lower priority only fills UNKNOWN chunks.
 * Thread-safe: region cells are guarded by the region monitor.
 */
public final class ColumnStore {
    private final ConcurrentHashMap<Long, RegionColumns> regions = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lifecycle = new ReentrantReadWriteLock();

    private static long key(final int regionX, final int regionZ) {
        return ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
    }

    /**
     * Merge a chunk snapshot. Returns true if the region changed (i.e. the
     * write was not discarded by the priority rule).
     */
    boolean put(final ChunkSnapshot snapshot, final SampleSource source) {
        lifecycle.readLock().lock();
        try {
            final int regionX = snapshot.chunkX >> 4;
            final int regionZ = snapshot.chunkZ >> 4;
            final RegionColumns region = regions.computeIfAbsent(
                key(regionX, regionZ),
                ignored -> new RegionColumns(regionX, regionZ)
            );
            return region.putChunk(snapshot, source);
        } finally {
            lifecycle.readLock().unlock();
        }
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
        lifecycle.writeLock().lock();
        try {
            return regions.remove(key(regionX, regionZ)) != null;
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    /**
     * Evicts {@code region} only when it is still the mapped instance and has not changed since
     * {@code flushedVersion} was copied to disk. The write lock closes the check/remove race with
     * {@link #put}: a concurrent writer either completes before the version check or creates a new
     * region after removal.
     */
    public boolean evictIfUnchanged(final RegionColumns region, final int flushedVersion) {
        lifecycle.writeLock().lock();
        try {
            final long key = key(region.regionX, region.regionZ);
            if (regions.get(key) != region || region.version() != flushedVersion) {
                return false;
            }
            return regions.remove(key, region);
        } finally {
            lifecycle.writeLock().unlock();
        }
    }

    public void clear() {
        lifecycle.writeLock().lock();
        try {
            regions.clear();
        } finally {
            lifecycle.writeLock().unlock();
        }
    }
}
