package cn.net.rms.confluxmap.core.xaeroimport;

import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionDiskCache;
import cn.net.rms.confluxmap.core.cache.RegionFileCodec;
import cn.net.rms.confluxmap.core.cache.RegionFileWriter;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.logging.log4j.Logger;

/**
 * Materializes external world-map region data into the local cache. One import
 * run converts a single (container, dimension) dataset region by region:
 *
 * <ul>
 *   <li>the dimension of the live session goes through {@link MapWorld#put} with
 *       {@link SampleSource#IMPORTED} (after the disk copy of that region has
 *       merged into memory, so real cached chunks can never be displaced), and
 *       the region flushes immediately to keep memory bounded;</li>
 *   <li>any other dimension of the same world is merged straight into the disk
 *       cache files, where imported chunks fill only slots that hold no explored
 *       data.</li>
 * </ul>
 *
 * <p>All runs execute on one dedicated worker thread; the map screen polls
 * {@link #progress()} and can {@link #cancel()}.
 */
public final class XaeroImportService {
    public enum State { IDLE, RUNNING, DONE, CANCELLED, FAILED }

    /** One importable dataset: a dimension's surface layer folder and its region count. */
    public record DimensionPlan(DimensionId dimension, MapLayer layer, Path layerFolder, int regionCount) {}

    public record ScanResult(String container, List<DimensionPlan> dimensions) {}

    public record Progress(
        State state,
        DimensionId dimension,
        int totalRegions,
        int processedRegions,
        int importedChunks,
        int keptChunks,
        String message
    ) {
        public static Progress idle() {
            return new Progress(State.IDLE, null, 0, 0, 0, 0, null);
        }
    }

    private static final int MAX_REGIONS_PER_IMPORT = 100_000;

    private final MapWorldService mapWorlds;
    private final RegionCacheService regionCaches;
    private final TileService tiles;
    private final Path cacheRoot;
    private final XaeroColumnBaker baker;
    private final Logger logger;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ConfluxMap-XaeroImport");
        thread.setDaemon(true);
        return thread;
    });
    private final AtomicReference<Progress> progress = new AtomicReference<>(Progress.idle());
    private volatile Future<?> running;
    private volatile boolean cancelRequested;

    public XaeroImportService(
        final MapWorldService mapWorlds,
        final RegionCacheService regionCaches,
        final TileService tiles,
        final Path cacheRoot,
        final XaeroColumnBaker baker,
        final Logger logger
    ) {
        this.mapWorlds = mapWorlds;
        this.regionCaches = regionCaches;
        this.tiles = tiles;
        this.cacheRoot = cacheRoot;
        this.baker = baker;
        this.logger = logger;
    }

    /**
     * Finds the external dataset matching {@code worldMatchKey} (a server
     * address with port, or a singleplayer save folder name) and counts the
     * surface-layer regions per dimension. Returns null when no container
     * matches.
     */
    public ScanResult scan(final Path gameDir, final String worldMatchKey, final boolean singleplayer) {
        final String key = singleplayer ? worldMatchKey.toLowerCase(java.util.Locale.ROOT)
            : XaeroWorldMapLayout.addressKey(worldMatchKey);
        final List<XaeroWorldMapLayout.Container> containers = XaeroWorldMapLayout.containers(gameDir);
        logger.info("xaero-import: scanning {} (key '{}', singleplayer {}) -> {} container(s)",
            gameDir, key, singleplayer, containers.size());
        for (final XaeroWorldMapLayout.Container container : containers) {
            if (!matches(container.folderName(), key, singleplayer)) {
                continue;
            }
            final List<DimensionPlan> plans = new ArrayList<>();
            for (final XaeroWorldMapLayout.DimensionFolder dimension : XaeroWorldMapLayout.dimensions(container)) {
                final MapLayer layer = surfaceLayer(dimension.dimension());
                if (layer == null) {
                    continue;
                }
                final Path layerFolder = XaeroWorldMapLayout.surfaceLayerFolder(dimension.dir());
                if (layerFolder == null) {
                    continue;
                }
                try {
                    plans.add(new DimensionPlan(
                        dimension.dimension(), layer, layerFolder,
                        XaeroWorldMapLayout.surfaceRegions(layerFolder).size()
                    ));
                } catch (final IOException e) {
                    logger.warn("xaero-import: could not list {} ({})", dimension.dir(), e.toString());
                }
            }
            return new ScanResult(container.folderName(), List.copyOf(plans));
        }
        return null;
    }

    private static boolean matches(final String folderName, final String key, final boolean singleplayer) {
        if (singleplayer) {
            // Save-name containers escape underscores differently across Xaero releases.
            return XaeroWorldMapLayout.unescapeContainerName(folderName).toLowerCase(java.util.Locale.ROOT).equals(key);
        }
        return key.equals(XaeroWorldMapLayout.containerAddressKey(folderName));
    }

    private static MapLayer surfaceLayer(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return new MapLayer(MapLayer.Type.SURFACE, 0);
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return new MapLayer(MapLayer.Type.NETHER_CEILING, 0);
        }
        if (dimension.equals(DimensionId.END)) {
            return new MapLayer(MapLayer.Type.END_SURFACE, 0);
        }
        return null;
    }

    public Progress progress() {
        return progress.get();
    }

    public boolean isIdle() {
        return progress.get().state() == State.IDLE || progress.get().state() == State.DONE
            || progress.get().state() == State.CANCELLED || progress.get().state() == State.FAILED;
    }

    public void cancel() {
        cancelRequested = true;
    }

    /** Starts importing one dimension plan on the worker thread. */
    public synchronized void start(final DimensionPlan plan) {
        if (!isIdle()) {
            return;
        }
        cancelRequested = false;
        progress.set(new Progress(
            State.RUNNING, plan.dimension(), Math.min(plan.regionCount(), MAX_REGIONS_PER_IMPORT), 0, 0, 0, null
        ));
        running = worker.submit(() -> run(plan));
    }

    private void run(final DimensionPlan plan) {
        int imported = 0;
        int kept = 0;
        int processed = 0;
        String failure = null;
        try {
            final List<XaeroRegionFile> regions = XaeroWorldMapLayout.surfaceRegions(plan.layerFolder());
            for (final XaeroRegionFile region : regions) {
                if (cancelRequested || processed >= MAX_REGIONS_PER_IMPORT) {
                    break;
                }
                final RegionOutcome outcome = importRegion(region, plan);
                processed++;
                imported += outcome.imported();
                kept += outcome.kept();
                progress.set(new Progress(
                    State.RUNNING, plan.dimension(), Math.min(regions.size(), MAX_REGIONS_PER_IMPORT),
                    processed, imported, kept, null
                ));
            }
        } catch (final Exception e) {
            failure = e.toString();
            logger.warn("xaero-import: import of {} failed ({})", plan.dimension(), failure);
        }
        final State end = failure != null ? State.FAILED
            : cancelRequested ? State.CANCELLED : State.DONE;
        progress.set(new Progress(end, plan.dimension(), progress.get().totalRegions(), processed, imported, kept, failure));
    }

    private record RegionOutcome(int imported, int kept) {}

    private RegionOutcome importRegion(final XaeroRegionFile region, final DimensionPlan plan) throws IOException {
        final SessionGuard.Session session = currentSession();
        if (session == null) {
            return new RegionOutcome(0, 0);
        }
        // One Xaero region covers four cache regions (512x512 vs 256x256 columns); route
        // tiles into per-cache-region buckets and then take the live or disk path.
        final RegionBuilder[] buckets = new RegionBuilder[4];
        final int[] importedByBucket = new int[4];
        final boolean liveDimension = session.dimension().equals(plan.dimension());
        if (liveDimension) {
            prefetchLiveRegions(session, plan, region);
        }
        final int importedTotal;
        try (InputStream stream = region.open()) {
            final XaeroRegionReader.Result result = XaeroRegionReader.read(
                stream, region.regionX(), region.regionZ(), (chunkX, chunkZ, tile) -> {
                    final int bucket = bucketIndex(chunkX, chunkZ);
                    if (buckets[bucket] == null) {
                        buckets[bucket] = new RegionBuilder(region.regionX() * 2 + (chunkX >> 4 & 1),
                            region.regionZ() * 2 + (chunkZ >> 4 & 1));
                    }
                    importedByBucket[bucket] += buckets[bucket].addTile(
                        tile, chunkX, chunkZ, session.token(), plan.layer(), baker
                    );
                }
            );
            if (result instanceof XaeroRegionReader.Result.Skipped skip) {
                logger.info("xaero-import: skipped {} ({})", region.path().getFileName(), skip.reason());
                return new RegionOutcome(0, 0);
            }
        }
        importedTotal = sum(importedByBucket);
        int keptTotal = 0;
        for (int i = 0; i < buckets.length; i++) {
            if (buckets[i] == null) {
                continue;
            }
            final int keptChunks;
            if (liveDimension) {
                keptChunks = storeLive(buckets[i], plan, session);
            } else {
                keptChunks = storeToDisk(buckets[i], plan, session.world());
            }
            keptTotal += keptChunks;
        }
        return new RegionOutcome(importedTotal, keptTotal);
    }

    private static int sum(final int[] values) {
        int total = 0;
        for (final int value : values) {
            total += value;
        }
        return total;
    }

    /** Which quarter of the Xaero region a chunk falls in: the cache-region offset (0..3). */
    private static int bucketIndex(final int chunkX, final int chunkZ) {
        return (chunkX >> 4 & 1) << 1 | (chunkZ >> 4 & 1);
    }

    /** Loads the disk copies of the cache regions this Xaero region feeds, before any import write. */
    private void prefetchLiveRegions(final SessionGuard.Session session, final DimensionPlan plan, final XaeroRegionFile region) {
        final RegionDiskCache disk = regionCaches.current();
        if (disk == null) {
            return;
        }
        final int baseX = region.regionX() * 2;
        final int baseZ = region.regionZ() * 2;
        for (int dx = 0; dx < 2; dx++) {
            for (int dz = 0; dz < 2; dz++) {
                disk.awaitRegionLoaded(plan.layer().type(), baseX + dx, baseZ + dz);
            }
        }
    }

    /** Live-session path: priority-gated puts, then immediate flush + tile invalidation. */
    private int storeLive(final RegionBuilder bucket, final DimensionPlan plan, final SessionGuard.Session session) {
        final MapWorld world = mapWorlds.ifCurrent(session.token());
        if (world == null) {
            return 0;
        }
        int accepted = 0;
        for (final ChunkSnapshot snapshot : bucket.snapshots()) {
            if (world.put(plan.layer(), snapshot, SampleSource.IMPORTED)) {
                accepted++;
            }
        }
        if (accepted > 0) {
            tiles.markChunksStored(
                session.token(), session.dimension(), plan.layer(), bucket.units()
            );
        }
        final RegionDiskCache disk = regionCaches.current();
        if (disk != null) {
            disk.flushRegionIfDirty(plan.layer().type(), bucket.regionX(), bucket.regionZ());
        }
        return accepted;
    }

    /** Off-session path: merge into the existing region file, imported chunks fill only unexplored slots. */
    private int storeToDisk(final RegionBuilder bucket, final DimensionPlan plan, final WorldIdentity world) {
        final RegionFileCodec.RegionData imported = bucket.toRegionData();
        final Path worldDir = WorldStorageMigration.directory(cacheRoot, world, logger);
        final Path file = worldDir.resolve(plan.dimension().fileName())
            .resolve(plan.layer().cacheId())
            .resolve(String.format("r.%d.%d.cfr", bucket.regionX(), bucket.regionZ()));
        final RegionFileCodec.RegionData existing = readExisting(file, plan, bucket);
        if (existing == null) {
            return RegionFileWriter.writeAtomic(file, imported, plan.layer().type().ordinal(), logger)
                ? countImportedChunks(imported) : 0;
        }
        int importedChunks = 0;
        final byte[] chunkSource = existing.chunkSourceOrdinal().clone();
        final int[] chunkUpdateSeconds = existing.chunkUpdateEpochSeconds().clone();
        final long[] chunkSourceRevision = existing.chunkSourceRevision().clone();
        final short[] surfaceY = existing.surfaceY().clone();
        final byte[] fluidDepth = existing.fluidDepth().clone();
        final byte[] kind = existing.kind().clone();
        final String[] biomeId = existing.biomeId().clone();
        final int[] baseArgb = existing.baseArgb().clone();
        final int[] xaeroBaseArgb = existing.xaeroBaseArgb().clone();
        final int[] biomeTint = existing.biomeTint().clone();
        final int[] overlayArgb = existing.overlayArgb().clone();
        final int[] xaeroOverlayArgb = existing.xaeroOverlayArgb().clone();
        final byte[] light = existing.light().clone();
        for (int chunkIndex = 0; chunkIndex < chunkSource.length; chunkIndex++) {
            if (SampleSource.byOrdinal(chunkSource[chunkIndex]).isExplored()
                || SampleSource.byOrdinal(imported.chunkSourceOrdinal()[chunkIndex]) != SampleSource.IMPORTED) {
                continue;
            }
            copyChunk(chunkIndex, imported, surfaceY, fluidDepth, kind, biomeId, baseArgb,
                xaeroBaseArgb, biomeTint, overlayArgb, xaeroOverlayArgb, light);
            chunkSource[chunkIndex] = imported.chunkSourceOrdinal()[chunkIndex];
            chunkUpdateSeconds[chunkIndex] = imported.chunkUpdateEpochSeconds()[chunkIndex];
            chunkSourceRevision[chunkIndex] = imported.chunkSourceRevision()[chunkIndex];
            importedChunks++;
        }
        if (importedChunks == 0) {
            return 0;
        }
        final RegionFileCodec.RegionData merged = new RegionFileCodec.RegionData(
            imported.rx(), imported.rz(), System.currentTimeMillis(),
            chunkSource, chunkUpdateSeconds, chunkSourceRevision,
            surfaceY, fluidDepth, kind, biomeId, baseArgb, xaeroBaseArgb,
            biomeTint, overlayArgb, xaeroOverlayArgb, light
        );
        return RegionFileWriter.writeAtomic(file, merged, plan.layer().type().ordinal(), logger) ? importedChunks : 0;
    }

    private static int countImportedChunks(final RegionFileCodec.RegionData data) {
        int count = 0;
        for (final byte source : data.chunkSourceOrdinal()) {
            if (SampleSource.byOrdinal(source) == SampleSource.IMPORTED) {
                count++;
            }
        }
        return count;
    }

    private static void copyChunk(
        final int chunkIndex,
        final RegionFileCodec.RegionData source,
        final short[] surfaceY,
        final byte[] fluidDepth,
        final byte[] kind,
        final String[] biomeId,
        final int[] baseArgb,
        final int[] xaeroBaseArgb,
        final int[] biomeTint,
        final int[] overlayArgb,
        final int[] xaeroOverlayArgb,
        final byte[] light
    ) {
        final int baseX = (chunkIndex % RegionColumns.CHUNKS) * 16;
        final int baseZ = (chunkIndex / RegionColumns.CHUNKS) * 16;
        for (int z = 0; z < 16; z++) {
            final int from = (baseZ + z) * RegionColumns.SIZE + baseX;
            System.arraycopy(source.surfaceY(), from, surfaceY, from, 16);
            System.arraycopy(source.fluidDepth(), from, fluidDepth, from, 16);
            System.arraycopy(source.kind(), from, kind, from, 16);
            System.arraycopy(source.biomeId(), from, biomeId, from, 16);
            System.arraycopy(source.baseArgb(), from, baseArgb, from, 16);
            System.arraycopy(source.xaeroBaseArgb(), from, xaeroBaseArgb, from, 16);
            System.arraycopy(source.biomeTint(), from, biomeTint, from, 16);
            System.arraycopy(source.overlayArgb(), from, overlayArgb, from, 16);
            System.arraycopy(source.xaeroOverlayArgb(), from, xaeroOverlayArgb, from, 16);
            System.arraycopy(source.light(), from, light, from, 16);
        }
    }

    private RegionFileCodec.RegionData readExisting(final Path file, final DimensionPlan plan, final RegionBuilder bucket) {
        if (!Files.exists(file)) {
            return null;
        }
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            return RegionFileCodec.decode(in, bucket.regionX(), bucket.regionZ(), plan.layer().type().ordinal());
        } catch (final IOException | RegionFileCodec.RegionFileException e) {
            logger.warn("xaero-import: existing region {} unreadable ({}), overwriting", file, e.toString());
            return null;
        }
    }

    private SessionGuard.Session currentSession() {
        final MapWorld world = mapWorlds.current();
        return world == null ? null : world.session();
    }

    /**
     * Column accumulator for one 256x256 cache region, fed by decoded tiles.
     * Chunks that end up with no data contribute nothing to the built region.
     */
    private static final class RegionBuilder {
        private final int regionX;
        private final int regionZ;
        private final short[] surfaceY = filledShort(ChunkSnapshot.NO_SURFACE);
        private final String[] biomeId = new String[RegionColumns.SIZE * RegionColumns.SIZE];
        private final byte[] fluidDepth = new byte[RegionColumns.SIZE * RegionColumns.SIZE];
        private final int[] baseArgb = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        private final int[] xaeroBaseArgb = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        private final int[] tintArgb = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        private final int[] overlayArgb = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        private final int[] xaeroOverlayArgb = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        private final byte[] kind = new byte[RegionColumns.SIZE * RegionColumns.SIZE];
        private final byte[] light = new byte[RegionColumns.SIZE * RegionColumns.SIZE];
        private final boolean[] chunkPresent = new boolean[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        private final List<ChunkSnapshot> liveSnapshots = new ArrayList<>();

        RegionBuilder(final int regionX, final int regionZ) {
            this.regionX = regionX;
            this.regionZ = regionZ;
        }

        int regionX() {
            return regionX;
        }

        int regionZ() {
            return regionZ;
        }

        int addTile(final XaeroTileData tile, final int chunkX, final int chunkZ, final long token, final MapLayer layer, final XaeroColumnBaker baker) {
            final ChunkSnapshot snapshot = XaeroSnapshotConverter.toSnapshot(tile, chunkX, chunkZ, token, layer, baker);
            liveSnapshots.add(snapshot);
            chunkPresent[(chunkZ & 15) * RegionColumns.CHUNKS + (chunkX & 15)] = true;
            final int baseX = (chunkX & 15) * 16;
            final int baseZ = (chunkZ & 15) * 16;
            for (int z = 0; z < 16; z++) {
                final int from = z * 16;
                final int to = (baseZ + z) * RegionColumns.SIZE + baseX;
                System.arraycopy(snapshot.surfaceY, from, surfaceY, to, 16);
                System.arraycopy(snapshot.biomeId, from, biomeId, to, 16);
                System.arraycopy(snapshot.fluidDepth, from, fluidDepth, to, 16);
                System.arraycopy(snapshot.baseArgb, from, baseArgb, to, 16);
                System.arraycopy(snapshot.xaeroBaseArgb, from, xaeroBaseArgb, to, 16);
                System.arraycopy(snapshot.tintArgb, from, tintArgb, to, 16);
                System.arraycopy(snapshot.overlayArgb, from, overlayArgb, to, 16);
                System.arraycopy(snapshot.xaeroOverlayArgb, from, xaeroOverlayArgb, to, 16);
                System.arraycopy(snapshot.kind, from, kind, to, 16);
                System.arraycopy(snapshot.light, from, light, to, 16);
            }
            return 1;
        }

        List<ChunkSnapshot> snapshots() {
            return liveSnapshots;
        }

        List<TileService.RegionUnit> units() {
            final List<TileService.RegionUnit> units = new ArrayList<>();
            for (final ChunkSnapshot snapshot : liveSnapshots) {
                units.add(new TileService.RegionUnit(snapshot.chunkX, snapshot.chunkZ));
            }
            return units;
        }
        RegionFileCodec.RegionData toRegionData() {
            final byte[] chunkSource = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
            final int[] chunkUpdateSeconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
            final long[] chunkSourceRevision = new long[RegionFileCodec.CHUNK_TABLE_ENTRIES];
            final int now = (int) (System.currentTimeMillis() / 1000L);
            for (int i = 0; i < chunkSource.length; i++) {
                if (chunkPresent[i]) {
                    chunkSource[i] = (byte) SampleSource.IMPORTED.ordinal();
                    chunkUpdateSeconds[i] = now;
                    chunkSourceRevision[i] = Long.MIN_VALUE;
                }
            }
            return new RegionFileCodec.RegionData(
                regionX, regionZ, System.currentTimeMillis(),
                chunkSource, chunkUpdateSeconds, chunkSourceRevision,
                surfaceY, fluidDepth, kind, biomeId, baseArgb, xaeroBaseArgb,
                tintArgb, overlayArgb, xaeroOverlayArgb, light
            );
        }

        private static short[] filledShort(final short value) {
            final short[] array = new short[RegionColumns.SIZE * RegionColumns.SIZE];
            java.util.Arrays.fill(array, value);
            return array;
        }
    }
}
