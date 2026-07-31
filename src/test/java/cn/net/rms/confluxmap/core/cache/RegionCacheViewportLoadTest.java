package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegionCacheViewportLoadTest {
    @Test
    void regionReadQueueRejectsWorkBeyondItsBound(@TempDir final Path tempDir) {
        final MapExecutors executors = new MapExecutors();
        try {
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "bounded-cache-test"), DimensionId.OVERWORLD
            );
            final MapWorldService mapWorlds = new MapWorldService();
            mapWorlds.switchSession(session);
            final TileService tiles = new TileService(
                mapWorlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            final java.util.ArrayList<Runnable> queued = new java.util.ArrayList<>();
            final RegionDiskCache cache = new RegionDiskCache(
                tempDir,
                session,
                mapWorlds,
                queued::add,
                tiles,
                LogManager.getLogger("RegionCacheViewportLoadTest")
            );

            for (int i = 0; i < RegionDiskCache.MAX_PENDING_REGION_LOADS; i++) {
                assertTrue(cache.ensureRegionLoaded(MapLayer.Type.SURFACE, i, 0));
            }
            assertFalse(cache.ensureRegionLoaded(MapLayer.Type.SURFACE, 999, 0));
            assertTrue(
                cache.ensureRegionLoaded(MapLayer.Type.SURFACE, 0, 0),
                "an already accepted region is still a successful no-op"
            );
        } finally {
            executors.shutdown(1_000L);
        }
    }

    @Test
    void cancellingSequentialRegionLoadStopsSchedulingMoreReads(@TempDir final Path tempDir) {
        final MapExecutors executors = new MapExecutors();
        try {
            final SessionGuard.Session session = new SessionGuard.Session(
                1L, new WorldIdentity("local", "cancelled-cache-test"), DimensionId.OVERWORLD
            );
            final MapWorldService mapWorlds = new MapWorldService();
            mapWorlds.switchSession(session);
            final TileService tiles = new TileService(
                mapWorlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            final java.util.ArrayList<Runnable> queued = new java.util.ArrayList<>();
            final RegionDiskCache cache = new RegionDiskCache(
                tempDir,
                session,
                mapWorlds,
                queued::add,
                tiles,
                LogManager.getLogger("RegionCacheViewportLoadTest")
            );

            final java.util.concurrent.CompletableFuture<Void> loading = cache.awaitRegionsLoaded(
                MapLayer.Type.SURFACE, 0, 0, 16
            );
            assertEquals(1, queued.size());

            loading.cancel(true);
            queued.remove(0).run();

            assertEquals(0, queued.size());
            assertTrue(loading.isCancelled());
        } finally {
            executors.shutdown(1_000L);
        }
    }

    @Test
    void coarseViewportLoadsEveryCoveredFineRegion(@TempDir final Path tempDir) throws Exception {
        final MapExecutors executors = new MapExecutors();
        try {
            final WorldIdentity identity = new WorldIdentity("local", "viewport-cache-test");
            final SessionGuard.Session session = new SessionGuard.Session(1L, identity, DimensionId.OVERWORLD);
            final MapWorldService mapWorlds = new MapWorldService();
            final TileService tiles = new TileService(
                mapWorlds, executors, new ConfluxConfig(), new DaylightModel()
            );
            final RegionCacheService cache = new RegionCacheService(
                tempDir, mapWorlds, executors, tiles, LogManager.getLogger("RegionCacheViewportLoadTest")
            );
            tiles.bindRegionCache(cache);
            cache.onSessionChanged(session);
            for (int regionZ = 0; regionZ < 2; regionZ++) {
                for (int regionX = 0; regionX < 2; regionX++) {
                    writeRegion(tempDir, session, regionX, regionZ);
                }
            }

            tiles.setViewport(MapLayer.SURFACE, 1, 0, 0, 0, 0);

            final MapWorld world = mapWorlds.current();
            awaitRegions(world, 2);
            for (int regionZ = 0; regionZ < 2; regionZ++) {
                for (int regionX = 0; regionX < 2; regionX++) {
                    assertNotNull(
                        world.store(MapLayer.SURFACE).region(regionX, regionZ),
                        "LOD1 viewport must restore fine region " + regionX + "," + regionZ
                    );
                }
            }
        } finally {
            executors.shutdown(2_000L);
        }
    }

    private static void awaitRegions(final MapWorld world, final int regionsPerSide) throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean complete = true;
            for (int z = 0; z < regionsPerSide; z++) {
                for (int x = 0; x < regionsPerSide; x++) {
                    complete &= world.store(MapLayer.SURFACE).region(x, z) != null;
                }
            }
            if (complete) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("coarse viewport did not restore all fine regions");
    }

    private static void writeRegion(
        final Path root,
        final SessionGuard.Session session,
        final int regionX,
        final int regionZ
    ) throws IOException {
        final byte[] source = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        source[0] = (byte) SampleSource.REAL_CACHED.ordinal();
        final short[] surfaceY = new short[RegionFileCodec.COLUMN_COUNT];
        final byte[] fluidDepth = new byte[RegionFileCodec.COLUMN_COUNT];
        final byte[] kind = new byte[RegionFileCodec.COLUMN_COUNT];
        java.util.Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        final RegionFileCodec.RegionData data = new RegionFileCodec.RegionData(
            regionX,
            regionZ,
            1L,
            source,
            new int[RegionFileCodec.CHUNK_TABLE_ENTRIES],
            surfaceY,
            fluidDepth,
            kind,
            new String[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new int[RegionFileCodec.COLUMN_COUNT],
            new byte[RegionFileCodec.COLUMN_COUNT]
        );
        final Path file = WorldStorageMigration.directory(
            root, session.world(), LogManager.getLogger("RegionCacheViewportLoadTest")
        ).resolve(session.dimension().fileName()).resolve(MapLayer.SURFACE.cacheId())
            .resolve("r." + regionX + "." + regionZ + ".cfr");
        Files.createDirectories(file.getParent());
        try (OutputStream out = Files.newOutputStream(file)) {
            RegionFileCodec.encode(out, MapLayer.Type.SURFACE.ordinal(), data);
        }
    }
}
