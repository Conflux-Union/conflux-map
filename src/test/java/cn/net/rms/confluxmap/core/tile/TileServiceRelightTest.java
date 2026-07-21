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

            tiles.markSurfaceRelit(1L);

            final Set<TileKey> expected = new HashSet<>();
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                expected.add(surfaceKey(session, lod, 0, 0));
                expected.add(surfaceKey(session, lod, -1 >> lod, -1 >> lod));
            }
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
                "expected relit tiles at every LOD, missing: " + missing(expected, uploaded)
            );
        } finally {
            executors.shutdown(1000L);
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
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS]
        );
    }
}
