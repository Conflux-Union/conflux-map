package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * SURFACE tile compositions must carry their {@link TileUpdate.Relight} inputs (the
 * compose-time daylight factor and per-pixel block light), so the render-side texture
 * cache can re-light a tile whose backing region was evicted from memory - the case
 * where {@code markSurfaceRelit} can no longer reach it and its texture would otherwise
 * stay frozen at the daylight baked in at last compose.
 */
class TileServiceRelightInfoTest {
    private static final float COMPOSE_FACTOR = 0.4f;
    private static final byte TORCH_LIGHT = 5;

    @Test
    void surfaceTilesCarryComposeFactorAndLightPlane() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session =
                new SessionGuard.Session(1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD);
            mapWorlds.switchSession(session);
            final MapWorld world = mapWorlds.current();
            world.put(MapLayer.SURFACE, litSnapshot(), SampleSource.REAL_LIVE);
            world.put(MapLayer.END_SURFACE, litSnapshot(), SampleSource.REAL_LIVE);
            final DaylightModel daylight = new DaylightModel();
            daylight.update(COMPOSE_FACTOR);
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), daylight);

            final TileKey surfaceLod0 = key(session, MapLayer.SURFACE, 0);
            final TileKey surfaceLod1 = key(session, MapLayer.SURFACE, 1);
            final TileKey endLod0 = key(session, MapLayer.END_SURFACE, 0);
            tiles.requestTile(surfaceLod0);
            tiles.requestTile(surfaceLod1);
            tiles.requestTile(endLod0);

            final Map<TileKey, TileUpdate> updates = drain(tiles, 3);

            final TileUpdate.Relight lod0Relight = updates.get(surfaceLod0).relight();
            assertNotNull(lod0Relight, "SURFACE LOD0 must carry relight inputs");
            assertEquals(COMPOSE_FACTOR, lod0Relight.composedDaylight());
            assertEquals(TORCH_LIGHT, lod0Relight.lightLevels()[0], "lit column's light level");
            assertEquals(0, lod0Relight.lightLevels()[2], "unlit column stays 0");

            final TileUpdate.Relight lod1Relight = updates.get(surfaceLod1).relight();
            assertNotNull(lod1Relight, "SURFACE LOD1 must carry relight inputs");
            assertEquals(TORCH_LIGHT, lod1Relight.lightLevels()[0], "2x2-averaged light of the lit block");

            assertNull(updates.get(endLod0).relight(), "non-SURFACE layers never re-light");
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static Map<TileKey, TileUpdate> drain(final TileService tiles, final int expected) throws InterruptedException {
        final Map<TileKey, TileUpdate> updates = new HashMap<>();
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (updates.size() < expected && System.nanoTime() < deadline) {
            for (final TileUpdate update : tiles.drainUploads(64)) {
                updates.put(update.key(), update);
            }
            Thread.sleep(10L);
        }
        assertEquals(expected, updates.size(), "composed tiles");
        return updates;
    }

    private static TileKey key(final SessionGuard.Session session, final MapLayer layer, final int lod) {
        return new TileKey(session.world(), session.dimension(), layer.cacheId(), lod, 0, 0);
    }

    /** Chunk (0, 0) with block light {@value #TORCH_LIGHT} in its 2x2 north-west columns. */
    private static ChunkSnapshot litSnapshot() {
        final byte[] light = new byte[ChunkSnapshot.COLUMNS];
        light[0] = TORCH_LIGHT;
        light[1] = TORCH_LIGHT;
        light[16] = TORCH_LIGHT;
        light[17] = TORCH_LIGHT;
        return new ChunkSnapshot(
            0,
            0,
            1L,
            new short[ChunkSnapshot.COLUMNS],
            new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new int[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS],
            light
        );
    }
}
