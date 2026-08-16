package cn.net.rms.confluxmap.core.tile;

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
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class TileServiceRelightTest {
    @Test
    void surfaceRelightRecomposesEveryLodCoveringAResidentRegion() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session =
                new SessionGuard.Session(1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD);
            mapWorlds.switchSession(session);
            final MapWorld world = mapWorlds.current();
            world.put(MapLayer.SURFACE, snapshot(0, 0), SampleSource.REAL_LIVE);
            world.put(MapLayer.SURFACE, snapshot(-16, -16), SampleSource.REAL_LIVE);
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());

            // A relight only has to reach tiles the renderer is holding and showing; one LOD is on
            // screen at a time, so check each in its own viewport.
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final Set<TileKey> expected = Set.of(
                    surfaceKey(session, lod, 0, 0), surfaceKey(session, lod, -1 >> lod, -1 >> lod)
                );
                tiles.setViewport(MapLayer.SURFACE, lod, -1, 0, -1, 0);
                expected.forEach(tiles::requestTile);
                settle(tiles);

                tiles.markSurfaceRelit(1L);

                final Set<TileKey> uploaded = new HashSet<>();
                final long deadline = System.nanoTime() + 5_000_000_000L;
                while (!uploaded.containsAll(expected) && System.nanoTime() < deadline) {
                    for (final TileUpdate update : tiles.drainUploads(64)) {
                        uploaded.add(update.key());
                    }
                    Thread.sleep(10L);
                }
                assertTrue(
                    uploaded.containsAll(expected),
                    "expected relit tiles at LOD" + lod + ", missing: " + missing(expected, uploaded)
                );
            }
        } finally {
            executors.shutdown(1000L);
        }
    }

    /** Drains until the composer has been quiet for a few passes, so the next drain is the new work. */
    private static void settle(final TileService tiles) throws InterruptedException {
        int idle = 0;
        while (idle < 3) {
            Thread.sleep(20L);
            idle = tiles.drainUploads(64).isEmpty() ? idle + 1 : 0;
        }
    }

    private static TileKey surfaceKey(final SessionGuard.Session session, final int lod, final int tileX, final int tileZ) {
        return new TileKey(session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod, tileX, tileZ);
    }

    private static Set<TileKey> missing(final Set<TileKey> expected, final Set<TileKey> uploaded) {
        final Set<TileKey> result = new HashSet<>(expected);
        result.removeAll(uploaded);
        return result;
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
