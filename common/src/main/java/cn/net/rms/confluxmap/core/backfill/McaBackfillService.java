package cn.net.rms.confluxmap.core.backfill;

import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionDiskCache;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.LongSupplier;
import org.apache.logging.log4j.Logger;

/**
 * Backfills the live map from the singleplayer world's own Anvil region files.
 * The fullscreen map viewport is reported every frame; regions that hold no
 * fresh map data but exist as {@code .mca} on disk get scanned on the worker
 * pool and merged as {@link SampleSource#REAL_SCAN}, so captured/synchronized
 * data always wins and the prediction underlay is masked by real terrain.
 *
 * <p>Freshness rides in the chunk {@code sourceRevision}: the backing MCA
 * file's mtime. A cached REAL_SCAN chunk whose revision no longer matches the
 * file mtime is rescanned (subject to a per-region cooldown, since one Anvil
 * save touches every region inside one file at once). The same revision ranks
 * the chunk in sync arbitration, where the mtime's magnitude outranks
 * tick-based revisions by design: real scanned terrain beats corrected
 * predictions of the same world.
 */
public final class McaBackfillService {
    private static final int MAX_PENDING_SCANS = 4;
    private static final int MAX_OFFERS_PER_PASS = 8;
    private static final long RESCAN_COOLDOWN_MS = 5_000L;

    /** One cache-region unit of backfill work, per layer. */
    private record Slot(MapLayer layer, int regionX, int regionZ) {
    }

    private final MapWorldService mapWorlds;
    private final RegionCacheService regionCaches;
    private final TileService tiles;
    private final MapExecutors executors;
    private final ConfluxConfig config;
    private final McaRegionScanner scanner;
    private final McaColumnBaker baker;
    private final Logger logger;
    private final LongSupplier clock;

    private final Set<Slot> inFlight = ConcurrentHashMap.newKeySet();
    /** Slot -> backing MCA mtime at scan time; a changed mtime re-opens the region. */
    private final Map<Slot, Long> doneRegions = new ConcurrentHashMap<>();
    private final Map<Slot, Long> cooldownUntil = new ConcurrentHashMap<>();
    private final AtomicInteger pendingScans = new AtomicInteger();

    private volatile SessionGuard.Session session = SessionGuard.Session.NONE;
    private ViewportRect viewport;
    /** Row-major position inside the current viewport's region expansion; reset on rect change. */
    private long cursor;

    private record ViewportRect(int lod, int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
    }

    public McaBackfillService(
        final MapWorldService mapWorlds,
        final RegionCacheService regionCaches,
        final TileService tiles,
        final MapExecutors executors,
        final ConfluxConfig config,
        final McaRegionScanner scanner,
        final McaColumnBaker baker,
        final Logger logger
    ) {
        this(mapWorlds, regionCaches, tiles, executors, config, scanner, baker, logger,
            System::currentTimeMillis);
    }

    /** Clock seam for tests. */
    public McaBackfillService(
        final MapWorldService mapWorlds,
        final RegionCacheService regionCaches,
        final TileService tiles,
        final MapExecutors executors,
        final ConfluxConfig config,
        final McaRegionScanner scanner,
        final McaColumnBaker baker,
        final Logger logger,
        final LongSupplier clock
    ) {
        this.mapWorlds = mapWorlds;
        this.regionCaches = regionCaches;
        this.tiles = tiles;
        this.executors = executors;
        this.config = config;
        this.scanner = scanner;
        this.baker = baker;
        this.logger = logger;
        this.clock = clock;
    }

    /** Render-thread viewport push, same shape as the tile/prediction services. */
    public synchronized void reportViewport(
        final DimensionId dimension,
        final int lod,
        final int minTileX,
        final int maxTileX,
        final int minTileZ,
        final int maxTileZ
    ) {
        final SessionGuard.Session current = session;
        if (!config.mcaBackfillEnabled || !current.active()
            || !current.world().isSingleplayer() || !current.dimension().equals(dimension)) {
            return;
        }
        final MapLayer layer = PredictionDimensions.layer(dimension);
        if (layer == null || lod < 0 || lod > TileMath.MAX_LOD) {
            return;
        }
        final ViewportRect rect = new ViewportRect(lod, minTileX, maxTileX, minTileZ, maxTileZ);
        if (!rect.equals(viewport)) {
            viewport = rect;
            cursor = 0;
        }
        offerRegions(current, layer, rect);
    }

    /** View is gone (screen closed): keep state but stop offering until the next push. */
    public synchronized void clearViewport() {
        viewport = null;
    }

    /** Session boundary: drop every queue and ledger; in-flight writes are token-checked anyway. */
    public void onSessionChanged(final SessionGuard.Session newSession) {
        synchronized (this) {
            session = newSession;
            viewport = null;
        }
        inFlight.clear();
        doneRegions.clear();
        cooldownUntil.clear();
        pendingScans.set(0);
    }

    private void offerRegions(final SessionGuard.Session session, final MapLayer layer, final ViewportRect rect) {
        final int regionsPerTile = 1 << rect.lod();
        final long tileWidth = (long) rect.maxTileX() - rect.minTileX() + 1L;
        final long tileHeight = (long) rect.maxTileZ() - rect.minTileZ() + 1L;
        if (tileWidth <= 0L || tileHeight <= 0L) {
            return;
        }
        final long total = tileWidth * tileHeight * (long) regionsPerTile * regionsPerTile;
        for (int pass = 0; pass < MAX_OFFERS_PER_PASS; pass++) {
            // The cursor wraps: once every region is visited, later passes keep patrolling so
            // a region whose backing MCA file changed since its scan gets re-opened. Fresh
            // done regions cost one mtime stat; only expired ones do real work.
            final long index = Math.floorMod(cursor, total);
            cursor++;
            final long tileIndex = index / ((long) regionsPerTile * regionsPerTile);
            final int regionIndex = (int) (index % ((long) regionsPerTile * regionsPerTile));
            final int tileX = rect.minTileX() + (int) (tileIndex % tileWidth);
            final int tileZ = rect.minTileZ() + (int) (tileIndex / tileWidth);
            final int regionX = tileX * regionsPerTile + regionIndex % regionsPerTile;
            final int regionZ = tileZ * regionsPerTile + regionIndex / regionsPerTile;
            final Slot slot = new Slot(layer, regionX, regionZ);
            final Long doneMtime = doneRegions.get(slot);
            final long mtime = scanner.mtimeMs(session.dimension(), regionX, regionZ);
            if (mtime == 0L) {
                continue; // no Anvil file for this region
            }
            if (doneMtime != null && doneMtime == mtime) {
                continue;
            }
            doneRegions.remove(slot);
            if (inFlight.contains(slot)) {
                continue;
            }
            final long now = clock.getAsLong();
            final Long cooling = cooldownUntil.get(slot);
            if (cooling != null && now < cooling) {
                continue;
            }
            if (inMemorySatisfied(session, layer, regionX, regionZ, mtime)) {
                doneRegions.put(slot, mtime);
                continue;
            }
            if (pendingScans.get() >= MAX_PENDING_SCANS) {
                return; // keep the cursor; the next push retries this region
            }
            submit(session, layer, slot, mtime);
        }
        this.cursor = cursor;
    }

    /**
     * Whether every chunk of the region already holds covered data: captured or
     * imported sources, or a REAL_SCAN whose revision still matches the MCA
     * mtime. Partially covered regions return false; the rescan's writes are
     * rejected per chunk by the priority gate.
     */
    private boolean inMemorySatisfied(
        final SessionGuard.Session session,
        final MapLayer layer,
        final int regionX,
        final int regionZ,
        final long mtime
    ) {
        final MapWorld world = mapWorlds.ifCurrent(session.token());
        if (world == null) {
            return true;
        }
        final RegionColumns region = world.store(layer).region(regionX, regionZ);
        if (region == null) {
            return false;
        }
        for (int localZ = 0; localZ < RegionColumns.CHUNKS; localZ++) {
            for (int localX = 0; localX < RegionColumns.CHUNKS; localX++) {
                final SampleSource source = region.chunkSource(localX, localZ);
                if (source == SampleSource.UNKNOWN || source == SampleSource.PREDICTED) {
                    return false;
                }
                if (source == SampleSource.REAL_SCAN
                    && region.chunkSourceRevision(localX, localZ) != mtime) {
                    return false;
                }
            }
        }
        return true;
    }

    private void submit(
        final SessionGuard.Session session,
        final MapLayer layer,
        final Slot slot,
        final long mtime
    ) {
        inFlight.add(slot);
        pendingScans.incrementAndGet();
        final RegionDiskCache disk = regionCaches.current();
        final CompletableFuture<Void> load = disk != null
            ? disk.awaitRegionLoaded(layer.type(), slot.regionX(), slot.regionZ())
            : CompletableFuture.completedFuture(null);
        load.thenRunAsync(() -> scanAndStore(session, layer, slot, mtime), executors.workers())
            .exceptionally(error -> {
                logger.warn("mca-backfill: region {},{} failed", slot.regionX(), slot.regionZ(), error);
                release(slot);
                return null;
            });
    }

    /** Worker thread: the region's disk copy has merged; decide freshness and scan. */
    private void scanAndStore(
        final SessionGuard.Session session,
        final MapLayer layer,
        final Slot slot,
        final long mtime
    ) {
        try {
            final long scanStartedAt = clock.getAsLong();
            if (mapWorlds.ifCurrent(session.token()) == null) {
                return;
            }
            final long now = clock.getAsLong();
            final Long cooling = cooldownUntil.get(slot);
            if (cooling != null && now < cooling) {
                return;
            }
            final DimensionId dimension = session.dimension();
            final int regionX = slot.regionX();
            final int regionZ = slot.regionZ();
            if (inMemorySatisfied(session, layer, regionX, regionZ, mtime)) {
                return; // the awaited disk load already brought fresh data
            }
            final SummaryCodec.SampledRegion scanned = scanner.scanRegion(dimension, regionX, regionZ, mtime);
            if (scanned == null || scanner.mtimeMs(dimension, regionX, regionZ) != mtime) {
                return; // unreadable, or the file changed mid-scan
            }
            final MapWorld world = mapWorlds.ifCurrent(session.token());
            if (world == null) {
                return;
            }
            final List<ChunkSnapshot> snapshots =
                McaSnapshotConverter.toSnapshots(scanned, session.token(), layer, baker);
            int accepted = 0;
            final List<TileService.RegionUnit> units = new ArrayList<>(snapshots.size());
            for (final ChunkSnapshot snapshot : snapshots) {
                if (world.put(layer, snapshot, SampleSource.REAL_SCAN)) {
                    accepted++;
                    units.add(new TileService.RegionUnit(snapshot.chunkX, snapshot.chunkZ));
                }
            }
            if (accepted > 0) {
                tiles.markChunksStored(session.token(), dimension, layer, units);
                final RegionDiskCache disk = regionCaches.current();
                if (disk != null) {
                    disk.flushRegionIfDirty(layer.type(), regionX, regionZ);
                }
            }
            // The cooldown runs from the scan start, not the write, so a slow tail
            // cannot stretch the throttle window; it also only guards this session's
            // cadence — a write landing after the session swap must not throttle
            // the new session's offers.
            if (this.session.token() == session.token()) {
                cooldownUntil.put(slot, scanStartedAt + RESCAN_COOLDOWN_MS);
            }
        } finally {
            release(slot);
        }
    }

    private void release(final Slot slot) {
        inFlight.remove(slot);
        pendingScans.decrementAndGet();
    }
}
