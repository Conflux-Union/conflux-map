package cn.net.rms.confluxmap.core.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * All region-file writes must go through the single IO thread: worker-side
 * callers (backfill scans, terrain import) inline flushes race the IO-thread
 * sweep and session-end flush on the shared tmp path.
 */
class RegionDiskCacheFlushTest {
    private static final long TOKEN = 7L;

    @TempDir
    Path tempDir;

    private MapExecutors executors;
    private MapWorldService mapWorlds;
    private SessionGuard.Session session;
    private final List<Runnable> ioTasks = new ArrayList<>();

    @BeforeEach
    void setUp() {
        executors = new MapExecutors();
        mapWorlds = new MapWorldService();
        session = new SessionGuard.Session(
            TOKEN, new WorldIdentity("local", "flush-test"), DimensionId.OVERWORLD
        );
        mapWorlds.switchSession(session);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(1_000L);
    }

    @Test
    void flushRegionIfDirtyRunsOnTheIoExecutorNotTheCallerThread() throws Exception {
        final TileService tiles = new TileService(
            mapWorlds, executors, new ConfluxConfig(), new DaylightModel()
        );
        final RegionDiskCache disk = new RegionDiskCache(
            tempDir, session, mapWorlds, ioTasks::add, tiles, LogManager.getLogger("flush-test")
        );
        putChunk(0, 0);

        final Path cfr = cfrFile();

        disk.flushRegionIfDirty(MapLayer.SURFACE.type(), 0, 0);
        assertEquals(1, ioTasks.size(), "the flush is handed to the IO executor");
        assertFalse(Files.exists(cfr), "the caller thread must not have written the file itself");

        ioTasks.get(0).run();
        assertTrue(Files.exists(cfr), "running the IO task writes the region file");
    }

    private void putChunk(final int chunkX, final int chunkZ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        Arrays.fill(biomeId, "minecraft:plains");
        final int[] color = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(color, 0xFF223344);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        mapWorlds.current().put(
            MapLayer.SURFACE,
            new ChunkSnapshot(chunkX, chunkZ, TOKEN, 5L,
                surfaceY, biomeId, new byte[ChunkSnapshot.COLUMNS],
                color, color, new int[ChunkSnapshot.COLUMNS], kind,
                new byte[ChunkSnapshot.COLUMNS]),
            SampleSource.REAL_CACHED
        );
    }

    private Path cfrFile() {
        return WorldStorageMigration.directory(tempDir, session.world(),
                LogManager.getLogger("flush-test"))
            .resolve(DimensionId.OVERWORLD.fileName())
            .resolve(MapLayer.SURFACE.cacheId())
            .resolve("r.0.0.cfr");
    }
}
