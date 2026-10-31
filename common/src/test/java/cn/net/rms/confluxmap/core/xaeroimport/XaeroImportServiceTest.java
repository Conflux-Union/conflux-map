package cn.net.rms.confluxmap.core.xaeroimport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.cache.RegionFileWriter;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** End-to-end coverage of {@link XaeroImportService}: scan, disk merge, priority. */
class XaeroImportServiceTest {
    private static final Logger LOGGER = LogManager.getLogger("XaeroImportServiceTest");

    private static final XaeroColumnBaker BAKER = (column, worldX, worldZ, waterDepth, biomeId, ceiling) -> {
        if (!column.isGrassSurface() && column.surface() != null
            && column.surface().id().equals("minecraft:air")) {
            return XaeroColumnBaker.Baked.voidColumn();
        }
        return new XaeroColumnBaker.Baked(
            0xFF112233, 0xFF445566, 0xFFFFFFFF, 0, 0, SurfaceKind.LAND, (short) column.height()
        );
    };

    private static XaeroColumn[] uniform(final int height) {
        final XaeroColumn[] columns = new XaeroColumn[256];
        for (int i = 0; i < columns.length; i++) {
            columns[i] = new XaeroColumn(
                XaeroBlockState.of("minecraft:stone"), height, XaeroColumn.NO_TOP_HEIGHT, 0,
                "minecraft:plains", List.of());
        }
        return columns;
    }

    /** Writes a Xaero region whose tile slot (tileX, tileZ) carries columns of the given height. */
    private static void writeXaeroRegion(
        final Path layerFolder, final int regionX, final int regionZ,
        final int tileX, final int tileZ, final int height
    ) throws IOException {
        final XaeroRegionWriter writer = new XaeroRegionWriter(new XaeroSaveFormat(5, 8));
        final XaeroRegionWriter.Tile tile = new XaeroRegionWriter.Tile(
            0, 0, tileX, tileZ, uniform(height));
        final String name = regionX + "_" + regionZ + ".zip";
        try (OutputStream out = Files.newOutputStream(layerFolder.resolve(name))) {
            writer.write(out, List.of(tile));
        }
    }

    private static void awaitTerminal(final XaeroImportService service) throws Exception {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            final XaeroImportService.Progress progress = service.progress();
            if (progress.state() != XaeroImportService.State.IDLE
                && progress.state() != XaeroImportService.State.RUNNING) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("import did not finish");
    }

    @TempDir
    Path tempDir;

    @Test
    void scanMatchesAddressContainerAndCountsRegions() throws Exception {
        final Path layer = tempDir.resolve("xaero/world-map/Multiplayer_127.0.0.1/null/mw$default");
        Files.createDirectories(layer);
        writeXaeroRegion(layer, 0, 0, 1, 2, 70);
        writeXaeroRegion(layer, -1, 0, 0, 0, 71);

        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final RegionCacheService cache = new RegionCacheService(tempDir, mapWorlds, executors, tiles, LOGGER);
            final XaeroImportService service = new XaeroImportService(
                mapWorlds, cache, tiles, tempDir, BAKER, LOGGER);

            final XaeroImportService.ScanResult result = service.scan(tempDir, "127.0.0.1:25565", false);
            assertNotNull(result);
            assertEquals("Multiplayer_127.0.0.1", result.container());
            assertEquals(1, result.dimensions().size());
            assertEquals(2, result.dimensions().get(0).regionCount());

            assertNull(service.scan(tempDir, "other.example.org:25565", false));
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void importsOffSessionDimensionStraightToDiskCache() throws Exception {
        final Path layer = tempDir.resolve("xaero/world-map/Multiplayer_127.0.0.1/DIM-1/mw$default");
        Files.createDirectories(layer);
        // Tile slot (1, 2) of chunk (0,0) = cache chunk (1, 2) in cache region (0, 0).
        writeXaeroRegion(layer, 0, 0, 1, 2, 70);
        // A tile in a neighboring cache region bucket: chunk (17, 3) -> region (1, 0), local (1, 3).
        final XaeroRegionWriter writer = new XaeroRegionWriter(new XaeroSaveFormat(5, 8));
        final XaeroRegionWriter.Tile neighbor = new XaeroRegionWriter.Tile(1, 0, 1, 3, uniform(90));
        try (OutputStream out = Files.newOutputStream(layer.resolve("0_1.zip"))) {
            writer.write(out, List.of(neighbor));
        }

        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final RegionCacheService cache = new RegionCacheService(tempDir, mapWorlds, executors, tiles, LOGGER);
            final XaeroImportService service = new XaeroImportService(
                mapWorlds, cache, tiles, tempDir, BAKER, LOGGER);
            final WorldIdentity world = WorldIdentity.multiplayer("127.0.0.1:25565");
            // Live session is in the overworld; the nether plan must take the disk path.
            mapWorlds.switchSession(new SessionGuard.Session(1L, world, cn.net.rms.confluxmap.core.model.DimensionId.OVERWORLD));

            final XaeroImportService.ScanResult result = service.scan(tempDir, "127.0.0.1:25565", false);
            assertNotNull(result);
            final XaeroImportService.DimensionPlan nether = result.dimensions().stream()
                .filter(plan -> plan.dimension().path().equals("the_nether"))
                .findFirst().orElseThrow();
            service.start(nether);
            awaitTerminal(service);

            final XaeroImportService.Progress progress = service.progress();
            assertEquals(XaeroImportService.State.DONE, progress.state());
            assertEquals(2, progress.processedRegions());
            assertEquals(2, progress.importedChunks());

            final Path worldDir = WorldStorageMigration.directory(tempDir, world, LOGGER);
            final Path regionFile = worldDir.resolve("minecraft_the_nether").resolve("nether_roof")
                .resolve("r.0.0.cfr");
            assertTrue(Files.isRegularFile(regionFile));
            final RegionFileCodec.RegionData data;
            try (InputStream in = Files.newInputStream(regionFile)) {
                data = RegionFileCodec.decode(in, 0, 0, MapLayer.Type.NETHER_CEILING.ordinal());
            }
            final int importedOrdinal = SampleSource.IMPORTED.ordinal();
            assertEquals(importedOrdinal, data.chunkSourceOrdinal()[2 * 16 + 1]);
            // Region-level z-major column index for chunk (1,2), column (x=5, z=2).
            assertEquals(70, data.surfaceY()[(2 * 16 + 2) * 256 + 1 * 16 + 5]);
            // Other chunk slots stay unknown.
            assertEquals(0, data.chunkSourceOrdinal()[0]);
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void importNeverDisplacesExploredChunksOnDisk() throws Exception {
        final Path layer = tempDir.resolve("xaero/world-map/Multiplayer_127.0.0.1/DIM1/mw$default");
        Files.createDirectories(layer);
        writeXaeroRegion(layer, 0, 0, 0, 0, 64);
        // A tile in the neighboring cache-region bucket: chunk (1, 16) -> region (0, 1), local (1, 0).
        final XaeroRegionWriter writer = new XaeroRegionWriter(new XaeroSaveFormat(5, 8));
        final XaeroRegionWriter.Tile neighbor = new XaeroRegionWriter.Tile(0, 1, 1, 0, uniform(65));
        try (OutputStream out = Files.newOutputStream(layer.resolve("0_1.zip"))) {
            writer.write(out, List.of(neighbor));
        }

        final MapExecutors executors = new MapExecutors();
        try {
            final MapWorldService mapWorlds = new MapWorldService();
            final TileService tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
            final RegionCacheService cache = new RegionCacheService(tempDir, mapWorlds, executors, tiles, LOGGER);
            final XaeroImportService service = new XaeroImportService(
                mapWorlds, cache, tiles, tempDir, BAKER, LOGGER);
            final WorldIdentity world = WorldIdentity.multiplayer("127.0.0.1:25565");
            mapWorlds.switchSession(new SessionGuard.Session(1L, world, cn.net.rms.confluxmap.core.model.DimensionId.OVERWORLD));

            final Path worldDir = WorldStorageMigration.directory(tempDir, world, LOGGER);
            final Path endFile = worldDir.resolve("minecraft_the_end").resolve("end").resolve("r.0.0.cfr");
            // Pre-seed cache region (0,0) with two REAL_CACHED chunks; they must survive.
            final byte[] chunkSource = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
            chunkSource[0] = (byte) SampleSource.REAL_CACHED.ordinal();
            chunkSource[1] = (byte) SampleSource.REAL_CACHED.ordinal();
            final int[] seconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
            final long[] revisions = new long[RegionFileCodec.CHUNK_TABLE_ENTRIES];
            final int columns = RegionFileCodec.COLUMN_COUNT;
            final RegionFileCodec.RegionData existing = new RegionFileCodec.RegionData(
                0, 0, 1L, chunkSource, seconds, revisions,
                filled((short) 300), new byte[columns], kinds(), strings(columns, "minecraft:plains"),
                filledInt(0xFF999999), filledInt(0xFF999999), filledInt(0xFFAAAAAA),
                new int[columns], new int[columns], new byte[columns]
            );
            Files.createDirectories(endFile.getParent());
            assertTrue(RegionFileWriter.writeAtomic(endFile, existing, MapLayer.Type.END_SURFACE.ordinal(), LOGGER));

            final XaeroImportService.ScanResult result = service.scan(tempDir, "127.0.0.1:25565", false);
            final XaeroImportService.DimensionPlan end = result.dimensions().stream()
                .filter(plan -> plan.dimension().path().equals("the_end"))
                .findFirst().orElseThrow();
            service.start(end);
            awaitTerminal(service);
            assertEquals(XaeroImportService.State.DONE, service.progress().state());

            final RegionFileCodec.RegionData merged;
            try (InputStream in = Files.newInputStream(endFile)) {
                merged = RegionFileCodec.decode(in, 0, 0, MapLayer.Type.END_SURFACE.ordinal());
            }
            // Real cached chunks keep their source, color and height.
            assertEquals((byte) SampleSource.REAL_CACHED.ordinal(), merged.chunkSourceOrdinal()[0]);
            assertEquals(300, merged.surfaceY()[5]);
            assertEquals(0xFF999999, merged.baseArgb()[5]);
            // No other chunk of this region file was in the import, so it stays unknown.
            assertEquals(0, merged.chunkSourceOrdinal()[8]);

            // The neighbor tile became chunk (1, 36): cache region (0, 2), chunk table slot 65.
            final Path neighborFile = worldDir.resolve("minecraft_the_end").resolve("end").resolve("r.0.2.cfr");
            assertTrue(Files.isRegularFile(neighborFile));
            final RegionFileCodec.RegionData neighborRegion;
            try (InputStream in = Files.newInputStream(neighborFile)) {
                neighborRegion = RegionFileCodec.decode(in, 0, 2, MapLayer.Type.END_SURFACE.ordinal());
            }
            assertEquals((byte) SampleSource.IMPORTED.ordinal(), neighborRegion.chunkSourceOrdinal()[65]);
            assertEquals(65, neighborRegion.surfaceY()[(4 * 16 + 5) * 256 + 1 * 16 + 5]);
        } finally {
            executors.shutdown(1000L);
        }
    }

    @Test
    void importedDataLosesToEveryCapturedSource() {
        final MapWorldService mapWorlds = new MapWorldService();
        final WorldIdentity world = WorldIdentity.multiplayer("127.0.0.1:25565");
        mapWorlds.switchSession(new SessionGuard.Session(1L, world, cn.net.rms.confluxmap.core.model.DimensionId.OVERWORLD));
        final cn.net.rms.confluxmap.core.store.MapWorld worldStore = mapWorlds.current();
        final MapLayer surface = new MapLayer(MapLayer.Type.SURFACE, 0);
        final XaeroColumn[] columns = uniform(70);
        final XaeroTileData tile = new XaeroTileData(1, XaeroTileData.SURFACE_CAVE_START, 30, columns);
        final ChunkSnapshot imported = XaeroSnapshotConverter.toSnapshot(tile, 4, 4, 1L, surface, BAKER);

        // Imported data fills unknown chunks.
        assertTrue(worldStore.put(surface, imported, SampleSource.IMPORTED));
        assertEquals(SampleSource.IMPORTED, worldStore.store(surface).region(0, 0).chunkSource(4, 4));
        // A live capture displaces it.
        assertTrue(worldStore.put(surface, imported, SampleSource.REAL_LIVE));
        assertEquals(SampleSource.REAL_LIVE, worldStore.store(surface).region(0, 0).chunkSource(4, 4));
        // Imported data cannot come back over it.
        assertEquals(false, worldStore.put(surface, imported, SampleSource.IMPORTED));
        assertEquals(SampleSource.REAL_LIVE, worldStore.store(surface).region(0, 0).chunkSource(4, 4));
    }

    private static short[] filled(final short value) {        final short[] array = new short[RegionFileCodec.COLUMN_COUNT];
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static int[] filledInt(final int value) {
        final int[] array = new int[RegionFileCodec.COLUMN_COUNT];
        java.util.Arrays.fill(array, value);
        return array;
    }

    private static byte[] kinds() {
        final byte[] array = new byte[RegionFileCodec.COLUMN_COUNT];
        java.util.Arrays.fill(array, (byte) SurfaceKind.LAND.ordinal());
        return array;
    }

    private static String[] strings(final int length, final String value) {
        final String[] array = new String[length];
        java.util.Arrays.fill(array, value);
        return array;
    }
}
