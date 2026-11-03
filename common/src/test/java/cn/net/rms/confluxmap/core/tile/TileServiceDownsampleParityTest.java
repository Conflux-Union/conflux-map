package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void endToEndLod1ThroughLod3MatchesWeightedAverageFormulaAndExercisesPingPongScratch() throws InterruptedException {
        final MapExecutors executors = new MapExecutors(1);
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD
            );
            mapWorlds.switchSession(session);

            // Construct 4 chunks in region (0,0) with alternating columns (cEven, cOdd)
            // to ensure every 2x2 and 4x4 downsampling block mixes distinct colors geometrically.
            final int cEven = 0xFF224466;
            final int cOdd = 0xFF88AACC;

            mapWorlds.current().put(MapLayer.SURFACE, stripedChunk(0, 0, cEven, cOdd, 1, 5), SampleSource.REAL_LIVE);
            mapWorlds.current().put(MapLayer.SURFACE, stripedChunk(1, 0, cEven, cOdd, 1, 5), SampleSource.REAL_LIVE);
            mapWorlds.current().put(MapLayer.SURFACE, stripedChunk(0, 1, cEven, cOdd, 1, 5), SampleSource.REAL_LIVE);
            mapWorlds.current().put(MapLayer.SURFACE, stripedChunk(1, 1, cEven, cOdd, 1, 5), SampleSource.REAL_LIVE);

            final ConfluxConfig config = new ConfluxConfig();
            config.dynamicLighting = false;
            final TileService service = new TileService(
                mapWorlds, executors, config, new DaylightModel()
            );

            // 1. Request LOD0 to inspect the base rendered pixel values for even and odd columns
            final TileKey lod0Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );
            service.requestTile(lod0Key);
            final TileUpdate lod0Update = awaitUpdate(service, lod0Key);
            assertNotNull(lod0Update);
            final int baseEven = lod0Update.pixelAt(0, 0);
            final int baseOdd = lod0Update.pixelAt(1, 0);

            // Hand-computed mathematical expectation for any 2x2 block mixing 2 even and 2 odd columns
            final int expectedDownsampled = Argb.average4Weighted(baseEven, baseOdd, baseEven, baseOdd);

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

            assertEquals(expectedDownsampled, lod1First.pixelAt(0, 0),
                "LOD1 downsampled pixel must mathematically match Argb.average4Weighted of the 2x2 source block");

            assertArrayEquals(lod1First.argbPixels(), lod1Second.argbPixels(),
                "Consecutive LOD1 compositions must be bit-identical");
            assertArrayEquals(lod1First.relight().lightLevels(), lod1Second.relight().lightLevels());
            assertEquals(3, lod1First.lightLevelAt(0, 0));
            final int[] retainedPixels = lod1First.argbPixels().clone();
            final byte[] retainedLight = lod1First.relight().lightLevels().clone();

            // 3. Request LOD2 (steps = 2: step 0 writes A, step 1 writes downsampleB).
            // Directly exercises the downsampleB ping-pong branch.
            final TileKey lod2Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 2, 0, 0
            );
            service.requestTile(lod2Key);
            final TileUpdate lod2Update = awaitUpdate(service, lod2Key);
            assertNotNull(lod2Update);
            assertEquals(expectedDownsampled, lod2Update.pixelAt(0, 0),
                "LOD2 (2 ping-pong steps via downsampleB) must preserve expected downsampled pixel");
            assertEquals(3, lod2Update.lightLevelAt(0, 0));

            // 4. Request LOD3 (steps = 3: A -> B -> A).
            // Exercises 3-step ping-pong alternation back into downsampleA.
            final TileKey lod3Key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 3, 0, 0
            );
            service.requestTile(lod3Key);
            final TileUpdate lod3Update = awaitUpdate(service, lod3Key);
            assertNotNull(lod3Update);
            assertEquals(expectedDownsampled, lod3Update.pixelAt(0, 0),
                "LOD3 (3 ping-pong steps A -> B -> A) must preserve expected downsampled pixel");
            assertEquals(3, lod3Update.lightLevelAt(0, 0));

            assertTrue(mapWorlds.current().put(
                MapLayer.SURFACE, stripedChunk(0, 0, 0xFFCC4400, 0xFF00AA88, 9, 13), SampleSource.REAL_LIVE
            ));
            service.requestTile(lod0Key);
            final TileUpdate changedLod0 = awaitUpdate(service, lod0Key);
            final int changedExpected = Argb.average4Weighted(
                changedLod0.pixelAt(0, 0), changedLod0.pixelAt(1, 0),
                changedLod0.pixelAt(0, 1), changedLod0.pixelAt(1, 1)
            );
            service.requestTile(lod1Key);
            final TileUpdate changedLod1 = awaitUpdate(service, lod1Key);
            assertNotEquals(expectedDownsampled, changedExpected);
            assertEquals(changedExpected, changedLod1.pixelAt(0, 0));
            assertEquals(11, changedLod1.lightLevelAt(0, 0));
            assertArrayEquals(retainedPixels, lod1First.argbPixels(), "Later compositions must not overwrite uploaded pixels");
            assertArrayEquals(retainedLight, lod1First.relight().lightLevels(), "Later compositions must not overwrite uploaded light");

        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot stripedChunk(
        final int cx, final int cz, final int cEven, final int cOdd, final int lightEven, final int lightOdd
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        final byte[] light = new byte[ChunkSnapshot.COLUMNS];
        for (int i = 0; i < ChunkSnapshot.COLUMNS; i++) {
            surfaceY[i] = 70;
            // Alternating columns so every 2x2 box has 2 even and 2 odd pixels
            baseArgb[i] = ((i % 16) % 2 == 0) ? cEven : cOdd;
            tintArgb[i] = 0xFFFFFFFF;
            kind[i] = (byte) SurfaceKind.LAND.ordinal();
            light[i] = (byte) ((i % 2 == 0) ? lightEven : lightOdd);
        }
        return new ChunkSnapshot(
            cx, cz, 1L, surfaceY, new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS], baseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, light
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
