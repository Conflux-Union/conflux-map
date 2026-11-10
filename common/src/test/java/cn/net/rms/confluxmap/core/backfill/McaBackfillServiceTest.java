package cn.net.rms.confluxmap.core.backfill;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.LogManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** State-machine coverage for {@link McaBackfillService} against a fake scanner. */
class McaBackfillServiceTest {

    private static final long SESSION_TOKEN = 1L;
    private static final long MTIME = 1_760_000_000_000L;
    /** Mirrors McaBackfillService.MAX_PENDING_SCANS. */
    private static final int MAX_PENDING_SCANS = 4;

    @TempDir
    Path tempDir;

    private MapExecutors executors;
    private MapWorldService mapWorlds;
    private TileService tiles;
    private ConfluxConfig config;
    private FakeScanner scanner;
    private AtomicLong clock;
    private McaBackfillService service;
    private SessionGuard.Session session;

    private static final class FakeBaker implements McaColumnBaker {
        @Override
        public Baked bake(
            final SummaryCodec.Column column,
            final int worldX,
            final int worldZ,
            final int cubiomesBiomeId
        ) {
            return new Baked(0xFF112233, 0xFF112233, 0xFFFFFFFF, 0, 0);
        }
    }

    private static final class FakeScanner implements McaRegionScanner {
        final AtomicLong mtime = new AtomicLong(MTIME);
        final AtomicInteger scanCount = new AtomicInteger();
        final CountDownLatch scanStarted = new CountDownLatch(1);
        /** File-stat probes from the viewport patrol; the throttle test's observation point. */
        final AtomicInteger mtimeProbes = new AtomicInteger();
        final AtomicReference<CountDownLatch> scanGate = new AtomicReference<>();
        /** Thread the latest scan ran on; the scan-pool test's observation point. */
        final AtomicReference<String> lastScanThread = new AtomicReference<>();
        volatile boolean poisonMtimeDuringScan;
        volatile int missingMcaRegionX = Integer.MIN_VALUE;

        @Override
        public long mtimeMs(final DimensionId dimension, final int regionX, final int regionZ) {
            mtimeProbes.incrementAndGet();
            return missingMcaRegionX == regionX ? 0L : mtime.get();
        }

        @Override
        public SummaryCodec.SampledRegion scanRegion(
            final DimensionId dimension, final int regionX, final int regionZ, final long expectedMtimeMs
        ) {
            lastScanThread.set(Thread.currentThread().getName());
            scanCount.incrementAndGet();
            scanStarted.countDown();
            if (poisonMtimeDuringScan) {
                mtime.incrementAndGet();
            }
            final CountDownLatch gate = scanGate.get();
            if (gate != null) {
                try {
                    assertTrue(gate.await(5, TimeUnit.SECONDS));
                } catch (final InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
            }
            return region(mtime.get());
        }
    }

    @BeforeEach
    void setUp() {
        executors = new MapExecutors();
        mapWorlds = new MapWorldService();
        tiles = new TileService(mapWorlds, executors, new ConfluxConfig(), new DaylightModel());
        config = new ConfluxConfig();
        scanner = new FakeScanner();
        clock = new AtomicLong(1_000_000L);
        session = new SessionGuard.Session(
            SESSION_TOKEN, new WorldIdentity("local", "backfill-test"), DimensionId.OVERWORLD
        );
        final RegionCacheService regionCaches = new RegionCacheService(
            tempDir, mapWorlds, executors, tiles, LogManager.getLogger("McaBackfillServiceTest")
        );
        regionCaches.onSessionChanged(session);
        mapWorlds.switchSession(session);
        service = new McaBackfillService(
            mapWorlds,
            regionCaches,
            tiles,
            executors,
            config,
            scanner,
            new FakeBaker(),
            LogManager.getLogger("McaBackfillServiceTest"),
            clock::get
        );
        service.onSessionChanged(session);
    }

    @AfterEach
    void tearDown() {
        executors.shutdown(1_000L);
    }

    @Test
    void viewportOverUnscannedRegionScansAndStoresRealScanData() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        final MapWorld world = mapWorlds.current();

        awaitChunkSource(world, 0, 0, SampleSource.REAL_SCAN);

        final RegionColumns region = world.store(MapLayer.SURFACE).region(0, 0);
        assertEquals(MTIME, region.chunkSourceRevision(0, 0));
        assertEquals(1, scanner.scanCount.get());
        awaitByPushing(() -> Files.exists(cfrFile(0, 0)));
        assertTrue(Files.exists(cfrFile(0, 0)), "accepted scans flush to the disk cache");
    }

    @Test
    void freshRealScanChunksAreNotRescanned() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);
        final int scansAfterFirst = scanner.scanCount.get();

        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        Thread.sleep(100L);

        assertEquals(scansAfterFirst, scanner.scanCount.get(), "fresh REAL_SCAN stays done");
    }

    @Test
    void staleMtimeTriggersRescanAfterCooldown() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);
        final int scansAfterFirst = scanner.scanCount.get();

        // The world save touches the MCA file; the clock has not reached the cooldown yet.
        scanner.mtime.incrementAndGet();
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        Thread.sleep(100L);
        assertEquals(scansAfterFirst, scanner.scanCount.get(), "cooldown holds during the window");

        clock.addAndGet(10_000L);
        final long expectedRevision = scanner.mtime.get();
        awaitByPushing(() -> {
            final RegionColumns region = mapWorlds.current().store(MapLayer.SURFACE).region(0, 0);
            return region != null && region.chunkSourceRevision(0, 0) == expectedRevision;
        });
        assertTrue(scanner.scanCount.get() > scansAfterFirst, "expired REAL_SCAN is rescanned");
    }

    @Test
    void unchangedMtimeStaysDonePastTheCooldown() throws Exception {
        // The fixture region is partial (chunk 0 only), so in-memory satisfaction alone can
        // never close it: a landed scan must record the region as done for its mtime.
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);
        final int scansAfterFirst = scanner.scanCount.get();

        clock.addAndGet(10_000L);
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        Thread.sleep(200L);

        assertEquals(scansAfterFirst, scanner.scanCount.get(),
            "a region whose MCA file never changed must not be rescanned once its scan landed");
    }

    @Test
    void redoneScanWithIdenticalRevisionsLeavesTheRegionUnchanged() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);
        final RegionColumns region = mapWorlds.current().store(MapLayer.SURFACE).region(0, 0);
        final int versionAfterFirstScan = region.version();

        // A done-ledger reset re-opens the region; the rescan of the unchanged file
        // carries the same (source, revision) for every chunk the region already holds.
        service.onSessionChanged(session);
        awaitByPushing(() -> scanner.scanCount.get() >= 2);
        Thread.sleep(200L);

        assertEquals(versionAfterFirstScan, region.version(),
            "identical (source, revision) writes must not bump the region version");
    }

    @Test
    void fullyCapturedRegionHoldsWithoutScans() throws Exception {
        final MapWorld world = mapWorlds.current();
        for (int localZ = 0; localZ < 16; localZ++) {
            for (int localX = 0; localX < 16; localX++) {
                putChunk(world, 0, 0, localX, localZ, SampleSource.REAL_CACHED, 5L);
            }
        }

        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        Thread.sleep(100L);

        assertEquals(0, scanner.scanCount.get(), "explored chunks keep the region done");
        assertEquals(SampleSource.REAL_CACHED, chunkSource(world, 0, 0, 0, 0));
    }

    @Test
    void fileChangedMidScanIsDiscarded() throws Exception {
        scanner.poisonMtimeDuringScan = true;
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        assertTrue(scanner.scanStarted.await(5, TimeUnit.SECONDS), "scan must start");
        executors.scans().shutdown();
        assertTrue(executors.scans().awaitTermination(5, TimeUnit.SECONDS), "scan must finish");

        assertEquals(1, scanner.scanCount.get());
        assertNull(mapWorlds.current().store(MapLayer.SURFACE).region(0, 0),
            "mtime mismatch must discard the scan");
    }

    @Test
    void missingMcaFileSkipsTheRegionSilently() {
        scanner.missingMcaRegionX = 0;
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        assertEquals(0, scanner.scanCount.get());
    }

    @Test
    void disabledConfigAndNonSingleplayerWorldsNeverScan() {
        config.mcaBackfillEnabled = false;
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        assertEquals(0, scanner.scanCount.get());

        config.mcaBackfillEnabled = true;
        final SessionGuard.Session remote = new SessionGuard.Session(
            2L, new WorldIdentity("mc.example.com:25565", "remote-world"), DimensionId.OVERWORLD
        );
        mapWorlds.switchSession(remote);
        service.onSessionChanged(remote);
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        assertEquals(0, scanner.scanCount.get());
    }

    @Test
    void sessionChangeClearsTheDoneLedger() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);
        final int scansAfterFirst = scanner.scanCount.get();

        final SessionGuard.Session next = new SessionGuard.Session(
            3L, new WorldIdentity("local", "backfill-test"), DimensionId.OVERWORLD
        );
        mapWorlds.switchSession(next);
        service.onSessionChanged(next);
        awaitByPushing(() -> chunkSource(mapWorlds.current(), 0, 0, 0, 0) == SampleSource.REAL_SCAN);

        assertTrue(scanner.scanCount.get() > scansAfterFirst, "new world rescans the region");
    }

    @Test
    void pendingBoundCapsConcurrentScansAndTheNextPushResumes() throws Exception {
        final CountDownLatch gate = new CountDownLatch(1);
        scanner.scanGate.set(gate);
        service.reportViewport(DimensionId.OVERWORLD, 1, 0, 1, 0, 1); // 16 candidate regions
        Thread.sleep(200L);
        final int whileGated = scanner.scanCount.get();
        assertTrue(whileGated >= 2, "the two-thread scan pool saturates, saw " + whileGated);
        assertTrue(whileGated <= MAX_PENDING_SCANS,
            "pending bound caps queued scans, saw " + whileGated);
        service.reportViewport(DimensionId.OVERWORLD, 1, 0, 1, 0, 1);
        Thread.sleep(100L);
        assertEquals(whileGated, scanner.scanCount.get(),
            "no extra offers while the bound holds");

        gate.countDown();
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (scanner.scanCount.get() <= MAX_PENDING_SCANS && System.nanoTime() < deadline) {
            clock.addAndGet(250L); // let the patrol throttle window elapse like real frames would
            service.reportViewport(DimensionId.OVERWORLD, 1, 0, 1, 0, 1);
            Thread.sleep(20L);
        }
        assertTrue(scanner.scanCount.get() > MAX_PENDING_SCANS,
            "released capacity lets the next push resume the cursor");
    }

    @Test
    void patrolOffersAreThrottledToTheInterval() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        final int probesAfterFirst = scanner.mtimeProbes.get();
        assertTrue(probesAfterFirst > 0, "the first push patrols");

        for (int i = 0; i < 5; i++) {
            service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        }
        assertEquals(probesAfterFirst, scanner.mtimeProbes.get(),
            "pushes inside the throttle window must not stat files on the render thread");

        clock.addAndGet(250L);
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        assertTrue(scanner.mtimeProbes.get() > probesAfterFirst,
            "a push past the interval patrols again");
    }

    @Test
    void scansRunOnTheDedicatedScanPool() throws Exception {
        service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
        awaitChunkSource(mapWorlds.current(), 0, 0, SampleSource.REAL_SCAN);

        assertTrue(scanner.lastScanThread.get().startsWith("ConfluxMap-Scan"),
            "multi-second scans must not starve tile composition on the worker pool, ran on "
                + scanner.lastScanThread.get());
    }

    // --- helpers ---

    private Path cfrFile(final int regionX, final int regionZ) {
        return WorldStorageMigration.directory(
                tempDir, session.world(), LogManager.getLogger("McaBackfillServiceTest")
            ).resolve(DimensionId.OVERWORLD.fileName())
            .resolve(MapLayer.SURFACE.cacheId())
            .resolve("r." + regionX + "." + regionZ + ".cfr");
    }

    /** Pushes the viewport each round, mirroring the production per-frame reportViewport. */
    private void awaitByPushing(final java.util.function.BooleanSupplier condition)
        throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            // Real pushes are spaced by real frames; the fake clock must advance with them
            // or the patrol throttle swallows every retry after the first.
            clock.addAndGet(250L);
            service.reportViewport(DimensionId.OVERWORLD, 0, 0, 0, 0, 0);
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(50L);
        }
        throw new AssertionError("condition never held while pushing the viewport");
    }

    private static void awaitChunkSource(
        final MapWorld world, final int regionX, final int regionZ,
        final SampleSource expected
    ) throws InterruptedException {
        final long deadline = System.nanoTime() + 5_000_000_000L;
        while (System.nanoTime() < deadline) {
            if (chunkSource(world, regionX, regionZ, 0, 0) == expected) {
                return;
            }
            Thread.sleep(10L);
        }
        throw new AssertionError("region " + regionX + "," + regionZ
            + " never reached " + expected);
    }

    private static SampleSource chunkSource(
        final MapWorld world, final int regionX, final int regionZ,
        final int chunkLocalX, final int chunkLocalZ
    ) {
        final RegionColumns region = world.store(MapLayer.SURFACE).region(regionX, regionZ);
        return region == null ? SampleSource.UNKNOWN : region.chunkSource(chunkLocalX, chunkLocalZ);
    }

    private static void putChunk(
        final MapWorld world, final int regionX, final int regionZ,
        final int chunkLocalX, final int chunkLocalZ,
        final SampleSource source, final long revision
    ) {
        final int chunkX = regionX * 16 + chunkLocalX;
        final int chunkZ = regionZ * 16 + chunkLocalZ;
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        Arrays.fill(surfaceY, (short) 64);
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        Arrays.fill(biomeId, "minecraft:plains");
        final int[] color = new int[ChunkSnapshot.COLUMNS];
        Arrays.fill(color, 0xFF112233);
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        Arrays.fill(kind, (byte) SurfaceKind.LAND.ordinal());
        world.put(
            MapLayer.SURFACE,
            new ChunkSnapshot(chunkX, chunkZ, SESSION_TOKEN, revision,
                surfaceY, biomeId, new byte[ChunkSnapshot.COLUMNS],
                color, color, new int[ChunkSnapshot.COLUMNS], kind,
                new byte[ChunkSnapshot.COLUMNS]),
            source
        );
    }

    private static SummaryCodec.SampledRegion region(final long mtime) {
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[256];
        Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(1));
        final SummaryCodec.Column[] columns = new SummaryCodec.Column[256];
        Arrays.fill(columns, new SummaryCodec.Column(
            1, 64, SurfaceKind.LAND.ordinal(), 11, 0, 255, 0,
            "minecraft:grass_block", "", ""
        ));
        chunks[0] = new SummaryCodec.SampledChunk(true, 0L, 1, columns);
        return new SummaryCodec.SampledRegion(0, 0, mtime, 1, chunks);
    }
}
