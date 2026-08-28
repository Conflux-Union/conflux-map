package cn.net.rms.confluxmap.mc.snapshot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkCaptureBatchTest {
    @Test
    void oneCaptureBatchInvalidatesItsSharedParentOnce() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "capture-batch"), DimensionId.OVERWORLD
            );
            final MapWorldService worlds = new MapWorldService();
            worlds.switchSession(session);
            final TileService tiles = new TileService(
                worlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            final ChunkCaptureService captures = new ChunkCaptureService(
                null, null, new ConfluxConfig(), worlds, executors, tiles, null,
                () -> 0, null, null, null, null
            );
            final TileKey parent = new TileKey(
                session.world(), session.dimension(), MapLayer.CAVE_AUTO.cacheId(), 4, 0, 0
            );
            tiles.setViewport(MapLayer.CAVE_AUTO, 4, 0, 0, 0, 0);
            tiles.requestTile(parent);
            drainOne(tiles, parent);

            captures.storeSnapshotsForTest(
                List.of(snapshot(8, 8), snapshot(9, 8)),
                MapLayer.CAVE_AUTO
            );

            drainOne(tiles, parent);
            assertTrue(tiles.isIdleForTest());
            assertEquals(2, captures.storedSnapshotCount());
        } finally {
            executors.shutdown(1_000L);
        }
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

    private static void drainOne(final TileService tiles, final TileKey key)
        throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (tiles.drainUploads(64).stream().anyMatch(update -> update.key().equals(key))) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("no upload arrived for " + key);
    }
}
