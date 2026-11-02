package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.List;
import org.junit.jupiter.api.Test;

class TileServiceDownsampleParityTest {
    @Test
    void endToEndLod1AndLod2ComposeMatchesDownsampleFormula() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD
            );
            mapWorlds.switchSession(session);

            // Construct 4 chunks in region (0,0) with distinct flat colors
            final int c00 = 0xFF112233;
            final int c10 = 0xFF445566;
            final int c01 = 0xFF778899;
            final int c11 = 0xFFAABBCC;

            mapWorlds.current().put(MapLayer.SURFACE, solidChunk(0, 0, c00), SampleSource.REAL_LIVE);
            mapWorlds.current().put(MapLayer.SURFACE, solidChunk(1, 0, c10), SampleSource.REAL_LIVE);
            mapWorlds.current().put(MapLayer.SURFACE, solidChunk(0, 1, c01), SampleSource.REAL_LIVE);
            mapWorlds.current().put(MapLayer.SURFACE, solidChunk(1, 1, c11), SampleSource.REAL_LIVE);

            final ConfluxConfig config = new ConfluxConfig();
            config.dynamicLighting = false;
            final TileService service = new TileService(
                mapWorlds, executors, config, new DaylightModel()
            );

            // 1. Request LOD0 to inspect the base rendered pixel at (0,0)
            final TileKey lod0Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );
            service.requestTile(lod0Key);
            final TileUpdate lod0Update = awaitUpdate(service, lod0Key);
            assertNotNull(lod0Update);
            final int basePixel00 = lod0Update.pixelAt(0, 0);

            // 2. Request LOD1 (steps = 1: writes to downsampleA).
            // Request twice to verify idempotence and absence of thread-local state drift.
            final TileKey lod1Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 1, 0, 0
            );
            service.requestTile(lod1Key);
            final TileUpdate lod1First = awaitUpdate(service, lod1Key);
            assertNotNull(lod1First);

            service.requestTile(lod1Key);
            final TileUpdate lod1Second = awaitUpdate(service, lod1Key);
            assertNotNull(lod1Second);

            assertEquals(basePixel00, lod1First.pixelAt(0, 0),
                "Flat uniform region (0,0) must preserve uniform downsampled color at origin");

            final int[] p1 = lod1First.argbPixels();
            final int[] p2 = lod1Second.argbPixels();
            assertEquals(p1.length, p2.length);
            for (int i = 0; i < p1.length; i++) {
                assertEquals(p1[i], p2[i], "Mismatch at index " + i);
            }

            // 3. Request LOD2 (steps = 2: step 0 writes A, step 1 writes downsampleB).
            // Directly exercises the downsampleB ping-pong branch.
            final TileKey lod2Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 2, 0, 0
            );
            service.requestTile(lod2Key);
            final TileUpdate lod2Update = awaitUpdate(service, lod2Key);
            assertNotNull(lod2Update);
            assertEquals(basePixel00, lod2Update.pixelAt(0, 0),
                "LOD2 (2 ping-pong steps via downsampleB) must preserve uniform origin pixel");

            // 4. Request LOD3 (steps = 3: A -> B -> A).
            // Exercises 3-step ping-pong alternation back into downsampleA.
            final TileKey lod3Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 3, 0, 0
            );
            service.requestTile(lod3Key);
            final TileUpdate lod3Update = awaitUpdate(service, lod3Key);
            assertNotNull(lod3Update);
            assertEquals(basePixel00, lod3Update.pixelAt(0, 0),
                "LOD3 (3 ping-pong steps A -> B -> A) must preserve uniform origin pixel");
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot solidChunk(final int cx, final int cz, final int argb) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            surfaceY[i] = 70;
            baseArgb[i] = argb;
            tintArgb[i] = 0xFFFFFFFF;
            kind[i] = (byte) SurfaceKind.LAND.ordinal();
        }
        return new ChunkSnapshot(
            cx, cz, 1L, surfaceY, new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS], baseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static TileUpdate awaitUpdate(final TileService service, final TileKey key)
        throws InterruptedException {
        final long deadline = System.currentTimeMillis() + 5000L;
        while (System.currentTimeMillis() < deadline) {
            final List<TileUpdate> updates = service.drainUploads(64);
            for (final TileUpdate update : updates) {
                if (update.key().equals(key)) {
                    return update;
                }
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("Timed out waiting for tile update " + key);
    }
}
