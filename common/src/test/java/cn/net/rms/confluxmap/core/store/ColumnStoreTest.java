package cn.net.rms.confluxmap.core.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class ColumnStoreTest {
    @Test
    void evictionRejectsARegionChangedAfterItsFlushSnapshot() {
        final ColumnStore store = new ColumnStore();
        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);
        final RegionColumns region = store.region(0, 0);
        final int flushedVersion = region.version();

        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 42);
        store.put(snapshot(0, 0, surfaceY), SampleSource.REAL_LIVE);

        assertFalse(store.evictIfUnchanged(region, flushedVersion));
        assertSame(region, store.region(0, 0));
    }

    @Test
    void identicalReputIsRejectedAsANoOpStore() {
        final ColumnStore store = new ColumnStore();
        assertTrue(store.put(snapshot(0, 0), SampleSource.REAL_LIVE));

        assertFalse(
            store.put(snapshot(0, 0), SampleSource.REAL_LIVE),
            "a recapture with byte-identical content must not register as a store change"
        );
    }

    @Test
    void identicalReputDoesNotBlockRegionEviction() {
        final ColumnStore store = new ColumnStore();
        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);
        final RegionColumns region = store.region(0, 0);
        final int flushedVersion = region.version();

        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);

        assertTrue(
            store.evictIfUnchanged(region, flushedVersion),
            "an unchanged region stays evictable: identical recaptures are no-ops"
        );
    }

    @Test
    void reputAfterAContentChangeIsAcceptedAgain() {
        final ColumnStore store = new ColumnStore();
        assertTrue(store.put(snapshot(0, 0), SampleSource.REAL_LIVE));
        assertFalse(store.put(snapshot(0, 0), SampleSource.REAL_LIVE));

        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 42);
        assertTrue(store.put(snapshot(0, 0, surfaceY), SampleSource.REAL_LIVE));
    }

    @Test
    void sourceUpgradeWithIdenticalContentStillCounts() {
        final ColumnStore store = new ColumnStore();
        assertTrue(store.put(snapshot(0, 0), SampleSource.IMPORTED));

        assertTrue(
            store.put(snapshot(0, 0), SampleSource.REAL_LIVE),
            "an imported-to-live upgrade is observable through the chunk source even when content matches"
        );
        assertSame(SampleSource.REAL_LIVE, store.region(0, 0).chunkSource(0, 0));
    }

    @Test
    void evictionRemovesTheExactRegionWhenItsFlushedVersionIsCurrent() {
        final ColumnStore store = new ColumnStore();
        store.put(snapshot(0, 0), SampleSource.REAL_LIVE);
        final RegionColumns region = store.region(0, 0);

        assertTrue(store.evictIfUnchanged(region, region.version()));
        assertNull(store.region(0, 0));
    }

    @Test
    void surfaceHeightLookupUsesFloorCoordinatesAcrossNegativeRegions() {
        final ColumnStore store = new ColumnStore();
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        java.util.Arrays.fill(surfaceY, ChunkSnapshot.NO_SURFACE);
        surfaceY[15 * 16 + 15] = 86;
        store.put(snapshot(-1, -2, surfaceY), SampleSource.REAL_LIVE);

        assertEquals(86, store.surfaceYAt(-1, -17).orElseThrow());
        assertTrue(store.surfaceYAt(-2, -17).isEmpty());
        assertTrue(store.surfaceYAt(256, 256).isEmpty());
    }

    @Test
    void knownVoidDoesNotProvideATeleportSurfaceHeight() {
        final ColumnStore store = new ColumnStore();
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.VOID.ordinal());
        store.put(snapshot(0, 0, surfaceY, kind), SampleSource.REAL_LIVE);

        assertTrue(
            store.surfaceYAt(8, 8).isEmpty(),
            "the End's void placeholder Y must not be offered as a teleport estimate"
        );
    }

    @Test
    void surfaceLookupDistinguishesKnownVoidFromUnknownTerrain() {
        final ColumnStore store = new ColumnStore();
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.VOID.ordinal());
        store.put(snapshot(0, 0, surfaceY, kind), SampleSource.REAL_LIVE);

        final ColumnStore.SurfaceLookup knownVoid = store.surfaceAt(8, 8);
        assertTrue(knownVoid.known());
        assertTrue(knownVoid.surfaceY().isEmpty());
        assertFalse(store.surfaceAt(24, 8).known());
    }

    @Test
    void noSurfaceVoidColumnIsKnownWithoutAHeight() {
        final ColumnStore store = new ColumnStore();
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, ChunkSnapshot.NO_SURFACE);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.VOID.ordinal());
        store.put(snapshot(0, 0, surfaceY, kind), SampleSource.REAL_LIVE);

        final ColumnStore.SurfaceLookup voidColumn = store.surfaceAt(8, 8);
        assertTrue(voidColumn.known(), "a captured void column is authoritative, not uncaptured");
        assertTrue(voidColumn.surfaceY().isEmpty());
    }

    @Test
    void legacyCachedVoidHeightProvidesNoReliefNeighbor() {
        final ColumnStore store = new ColumnStore();
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 65);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.VOID.ordinal());
        store.put(snapshot(0, 0, surfaceY, kind), SampleSource.REAL_LIVE);

        assertEquals(ChunkSnapshot.NO_SURFACE, store.region(0, 0).reliefYAt(8, 8, false));
        assertEquals(ChunkSnapshot.NO_SURFACE, store.region(0, 0).reliefYAt(8, 8, true));
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ) {
        return snapshot(chunkX, chunkZ, new short[ChunkSnapshot.COLUMNS]);
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ, final short[] surfaceY) {
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        return snapshot(chunkX, chunkZ, surfaceY, kind);
    }

    private static ChunkSnapshot snapshot(
        final int chunkX,
        final int chunkZ,
        final short[] surfaceY,
        final byte[] kind
    ) {
        return new ChunkSnapshot(
            chunkX,
            chunkZ,
            1L,
            surfaceY,
            new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            kind,
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
