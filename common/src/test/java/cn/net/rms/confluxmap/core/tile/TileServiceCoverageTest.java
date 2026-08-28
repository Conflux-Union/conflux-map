package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * A compose pass must only claim ({@link TileUpdate#changed}) the sub-rects it actually
 * composed from in-memory regions. A LOD-N tile covering both a resident and an evicted
 * region would otherwise report the full tile and blank the evicted quadrant on the
 * already-drawn zoomed-out map every time a relight or a chunk store marks it dirty.
 */
class TileServiceCoverageTest {
    private static final int HALF = RegionColumns.SIZE / 2;

    @Test
    void lodComposeClaimsOnlyResidentQuadrants() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session =
                new SessionGuard.Session(1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD);
            mapWorlds.switchSession(session);
            final MapWorld world = mapWorlds.current();
            world.put(MapLayer.SURFACE, snapshot(0, 0), SampleSource.REAL_LIVE);
            world.put(MapLayer.SURFACE, snapshot(16, 0), SampleSource.REAL_LIVE);
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final TileKey lod1 = new TileKey(session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 1, 0, 0);

            tiles.requestTile(lod1);
            final TileUpdate bothResident = drainOne(tiles, lod1);
            assertEquals(
                Set.of(new TileUpdate.Rect(0, 0, HALF, HALF), new TileUpdate.Rect(HALF, 0, HALF, HALF)),
                Set.copyOf(bothResident.changed()),
                "both covered regions resident: both quadrants claimed"
            );

            // Player left: the sweep flushed region (1,0) to disk and dropped it from memory.
            world.store(MapLayer.SURFACE).remove(1, 0);
            tiles.requestTile(lod1);
            final TileUpdate oneEvicted = drainOne(tiles, lod1);
            assertEquals(
                List.of(new TileUpdate.Rect(0, 0, HALF, HALF)),
                oneEvicted.changed(),
                "evicted region's quadrant must not be claimed (its old pixels stay on the texture)"
            );

            final TileKey lod0Missing =
                new TileKey(session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 7, 7);
            tiles.requestTile(lod0Missing);
            assertTrue(drainOne(tiles, lod0Missing).changed().isEmpty(), "LOD0 with no region claims nothing");

            final TileKey lod0Resident =
                new TileKey(session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0);
            tiles.requestTile(lod0Resident);
            assertEquals(
                List.of(new TileUpdate.Rect(0, 0, RegionColumns.SIZE, RegionColumns.SIZE)),
                drainOne(tiles, lod0Resident).changed(),
                "LOD0 with its region resident claims the full tile"
            );
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void lod4ChunkInvalidationRecomposesOnlyItsRegion() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session =
                new SessionGuard.Session(1L, new WorldIdentity("local", "incremental"), DimensionId.OVERWORLD);
            mapWorlds.switchSession(session);
            final MapWorld world = mapWorlds.current();
            world.put(MapLayer.SURFACE, snapshot(5 * 16 + 8, 5 * 16 + 8), SampleSource.REAL_LIVE);
            world.put(MapLayer.SURFACE, snapshot(9 * 16 + 8, 9 * 16 + 8), SampleSource.REAL_LIVE);
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final TileKey lod4 = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 4, 0, 0
            );
            tiles.setViewport(MapLayer.SURFACE, 4, 0, 0, 0, 0);
            tiles.requestTile(lod4);
            drainOne(tiles, lod4);

            tiles.markChunkStored(
                session.token(), session.dimension(), MapLayer.SURFACE,
                5 * 16 + 8, 5 * 16 + 8
            );

            final int subSize = RegionColumns.SIZE >> 4;
            assertEquals(
                List.of(new TileUpdate.Rect(5 * subSize, 5 * subSize, subSize, subSize)),
                drainOne(tiles, lod4).changed()
            );
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void batchedChunkInvalidationComposesSharedParentOnce() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session =
                new SessionGuard.Session(1L, new WorldIdentity("local", "batch"), DimensionId.OVERWORLD);
            mapWorlds.switchSession(session);
            final MapWorld world = mapWorlds.current();
            world.put(MapLayer.SURFACE, snapshot(3 * 16 + 8, 3 * 16 + 8), SampleSource.REAL_LIVE);
            world.put(MapLayer.SURFACE, snapshot(7 * 16 + 8, 7 * 16 + 8), SampleSource.REAL_LIVE);
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final TileKey lod4 = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 4, 0, 0
            );
            tiles.setViewport(MapLayer.SURFACE, 4, 0, 0, 0, 0);
            tiles.requestTile(lod4);
            drainOne(tiles, lod4);

            tiles.markChunksStored(
                session.token(),
                session.dimension(),
                MapLayer.SURFACE,
                List.of(
                    new TileService.RegionUnit(3 * 16 + 8, 3 * 16 + 8),
                    new TileService.RegionUnit(7 * 16 + 8, 7 * 16 + 8)
                )
            );

            final TileUpdate update = drainOne(tiles, lod4);
            final int subSize = RegionColumns.SIZE >> 4;
            assertEquals(
                Set.of(
                    new TileUpdate.Rect(3 * subSize, 3 * subSize, subSize, subSize),
                    new TileUpdate.Rect(7 * subSize, 7 * subSize, subSize, subSize)
                ),
                Set.copyOf(update.changed())
            );
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static TileUpdate drainOne(final TileService tiles, final TileKey key) throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            for (final TileUpdate update : tiles.drainUploads(64)) {
                if (update.key().equals(key)) {
                    return update;
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("no upload arrived for " + key);
    }

    private static ChunkSnapshot snapshot(final int chunkX, final int chunkZ) {
        return new ChunkSnapshot(
            chunkX,
            chunkZ,
            1L,
            new short[ChunkSnapshot.COLUMNS],
            new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
