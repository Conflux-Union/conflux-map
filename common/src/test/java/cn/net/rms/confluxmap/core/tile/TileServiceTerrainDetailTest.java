package cn.net.rms.confluxmap.core.tile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.Argb;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

class TileServiceTerrainDetailTest {
    @Test
    void xaeroStyleUsesItsRawColorPlaneInsteadOfConfluxTextureDetail() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "xaero-plane"), DimensionId.OVERWORLD
            );
            worlds.switchSession(session);
            worlds.current().put(MapLayer.SURFACE, dualColorLandSnapshot(), SampleSource.REAL_LIVE);
            final ConfluxConfig config = new ConfluxConfig();
            config.dynamicLighting = false;
            config.mapColorStyle = MapColorStyle.XAERO;
            final TileService tiles = new TileService(worlds, executors, config, new DaylightModel());
            final TileKey key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );

            tiles.requestTile(key);
            final int actual = drainOne(tiles, key).argbPixels()[5 * RegionColumns.SIZE + 5];
            final int expected = XaeroMapStyle.applyTerrain(
                0xFF204060, 63, 63, 63, 1, true, XaeroMapStyle.Shadow.OVERWORLD
            );

            assertEquals(expected, actual);
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void xaeroStyleUsesXaerosFlatTerrainRendererForCapturedTiles() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "xaero"), DimensionId.OVERWORLD
            );
            worlds.switchSession(session);
            worlds.current().put(MapLayer.SURFACE, landSnapshot(), SampleSource.REAL_LIVE);
            final ConfluxConfig config = new ConfluxConfig();
            config.dynamicLighting = false;
            config.mapColorStyle = MapColorStyle.XAERO;
            final TileService tiles = new TileService(worlds, executors, config, new DaylightModel());
            final TileKey key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );

            tiles.requestTile(key);
            final TileUpdate update = drainOne(tiles, key);

            assertEquals(0xFF896A4A, update.argbPixels()[5 * RegionColumns.SIZE + 5]);
            assertEquals(MapColorStyle.XAERO, update.relight().style());
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void capturedWaterDarkensItsFloorByRecordedDepth() throws InterruptedException {
        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService worlds = new MapWorldService();
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "detail"), DimensionId.OVERWORLD
            );
            worlds.switchSession(session);
            worlds.current().put(MapLayer.SURFACE, waterSnapshot(), SampleSource.REAL_LIVE);
            final ConfluxConfig config = new ConfluxConfig();
            config.dynamicLighting = false;
            final TileService tiles = new TileService(worlds, executors, config, new DaylightModel());
            final TileKey key = new TileKey(
                session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), 0, 0, 0
            );

            tiles.requestTile(key);
            final int[] pixels = drainOne(tiles, key).argbPixels();
            final int shallow = pixels[5 * RegionColumns.SIZE + 5];
            final int deep = pixels[5 * RegionColumns.SIZE + 6];

            assertEquals(Argb.alpha(shallow), Argb.alpha(deep), "water depth must not change surface opacity");
            assertTrue(Argb.red(deep) < Argb.red(shallow), "the deep seafloor should read darker through the same water");
            assertTrue(Argb.green(deep) < Argb.green(shallow), "bathymetry should preserve a visible depth gradient");
        } finally {
            executors.shutdown(1000L);
        }
    }

    private static ChunkSnapshot waterSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 80);
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        fluidDepth[5 * 16 + 6] = 48;
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(baseArgb, 0x803060D0);
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(tintArgb, 0xFFFFFFFF);
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(overlayArgb, 0xFFFFC080);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.WATER.ordinal());
        return new ChunkSnapshot(
            0, 0, 1L, surfaceY, new String[ChunkSnapshot.COLUMNS], fluidDepth,
            baseArgb, tintArgb, overlayArgb, kind, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static ChunkSnapshot landSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 63);
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(baseArgb, 0xFF806040);
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(tintArgb, 0xFFFFFFFF);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        return new ChunkSnapshot(
            0, 0, 1L, surfaceY, new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS], baseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
    }

    private static ChunkSnapshot dualColorLandSnapshot() {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 63);
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(baseArgb, 0xFFA0B0C0);
        final int[] xaeroBaseArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(xaeroBaseArgb, 0xFF204060);
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(tintArgb, 0xFFFFFFFF);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        return new ChunkSnapshot(
            0, 0, 1L, Long.MIN_VALUE, surfaceY, new String[ChunkSnapshot.COLUMNS],
            new byte[ChunkSnapshot.COLUMNS], baseArgb, xaeroBaseArgb, tintArgb,
            new int[ChunkSnapshot.COLUMNS], kind, new byte[ChunkSnapshot.COLUMNS]
        );
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
}
