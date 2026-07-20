package cn.net.rms.confluxmap.core.store;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SampleSource;
import org.junit.jupiter.api.Test;

class ColumnStoreTest {
    @Test
    void evictionRejectsARegionChangedAfterItsFlushSnapshot() {
        final ColumnStore store = new ColumnStore();
        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);
        final RegionColumns region = store.region(0, 0);
        final int flushedVersion = region.version();

        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);

        assertFalse(store.evictIfUnchanged(region, flushedVersion));
        assertSame(region, store.region(0, 0));
    }

    @Test
    void evictionRemovesTheExactRegionWhenItsFlushedVersionIsCurrent() {
        final ColumnStore store = new ColumnStore();
        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);
        final RegionColumns region = store.region(0, 0);

        assertTrue(store.evictIfUnchanged(region, region.version()));
        assertNull(store.region(0, 0));
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ) {
        return new ChunkSnapshot(
            chunkX,
            chunkZ,
            1L,
            new short[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
