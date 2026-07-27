package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.color.BiomeColorPalette;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TileServiceBiomeModeTest {
    @Test
    void biomeTileUsesOneUnshadedColorForEveryColumnOfTheSameBiome() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "world"), DimensionId.OVERWORLD
            );
            mapWorlds.switchSession(session);
            final MapWorld world = mapWorlds.current();
            world.put(MapLayer.SURFACE, snapshot(), SampleSource.REAL_LIVE);
            final ConfluxConfig config = new ConfluxConfig();
            config.dynamicLighting = false;
            final TileService tiles = new TileService(mapWorlds, executors, config, new DaylightModel());
            final TileKey terrain = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );
            final TileKey biomes = BiomeTileKeys.toBiome(terrain);

            tiles.requestTile(terrain);
            tiles.requestTile(biomes);
            final Map<TileKey, TileUpdate> updates = drain(tiles, terrain, biomes);
            final TileUpdate terrainUpdate = updates.get(terrain);
            final TileUpdate biomeUpdate = updates.get(biomes);

            assertNotEquals(terrainUpdate.argbPixels()[0], terrainUpdate.argbPixels()[1]);
            final int plains = BiomeColorPalette.color("minecraft:plains");
            assertEquals(plains, biomeUpdate.argbPixels()[0]);
            assertEquals(plains, biomeUpdate.argbPixels()[1]);
            assertEquals(0, biomeUpdate.argbPixels()[2], "unknown columns remain transparent");
            assertEquals(null, biomeUpdate.relight(), "flat biome colors do not carry daylight state");
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot snapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, ChunkSnapshot.NO_SURFACE);
        surfaceY[0] = 70;
        surfaceY[1] = 110;
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        biomeId[0] = "minecraft:plains";
        biomeId[1] = "minecraft:plains";
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        baseArgb[0] = 0xFFFF0000;
        baseArgb[1] = 0xFF0000FF;
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        tintArgb[0] = 0xFFFFFFFF;
        tintArgb[1] = 0xFFFFFFFF;
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        kind[0] = (byte) SurfaceKind.LAND.ordinal();
        kind[1] = (byte) SurfaceKind.LAND.ordinal();
        return new ChunkSnapshot(
            0, 0, 1L, surfaceY, biomeId,
            new byte[ChunkSnapshot.COLUMNS], baseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static Map<TileKey, TileUpdate> drain(
        final TileService tiles,
        final TileKey first,
        final TileKey second
    ) throws InterruptedException {
        final Map<TileKey, TileUpdate> updates = new HashMap<>();
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (updates.size() < 2 && System.nanoTime() < deadline) {
            for (final TileUpdate update : tiles.drainUploads(64)) {
                if (update.key().equals(first) || update.key().equals(second)) {
                    updates.put(update.key(), update);
                }
            }
            Thread.sleep(10L);
        }
        assertEquals(2, updates.size(), "both terrain and biome uploads arrive");
        return updates;
    }
}
