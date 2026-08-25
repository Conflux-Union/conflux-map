package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.store.WorldStorageMigration;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import java.io.BufferedInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.apache.logging.log4j.Logger;

/**
 * Owns the on-disk region cache for one world session (see {@link RegionCacheService}, which
 * creates/discards instances of this class as sessions rotate). Only {@link MapLayer.Type#persistent()}
 * layers are ever touched - {@link #ensureRegionLoaded} no-ops immediately for a non-persistent
 * layer type, and every call site that drives it (the live-capture touch point, {@link
 * cn.net.rms.confluxmap.core.tile.TileService#requestTile}) additionally gates on {@link
 * MapLayer.Type#persistent()} itself before calling in, so dynamic layers (CAVE_AUTO,
 * NETHER_CURRENT, the Y-slices) never reach disk IO at all. The periodic sweep ({@link #tick})
 * and the final flush walk every persistent layer type generically, so a newly-captured
 * persistent layer (SURFACE, NETHER_CEILING, END_SURFACE) just needs to start writing chunks to
 * start getting cached.
 *
 * <p>All file IO runs on {@link MapExecutors#io()}. Writes are atomic (tmp file, fsync, {@code
 * ATOMIC_MOVE}); unreadable or corrupt files are quarantined to {@code *.bad} and treated as empty
 * rather than crashing anything, mirroring {@code core.config.ConfigIo}.
 */
public final class RegionDiskCache {
    private static final long FLUSH_INTERVAL_MS = 30_000L;
    private static final int EVICT_DISTANCE_REGIONS = 6;
    static final int MAX_PENDING_REGION_LOADS = 64;

    private static final MapLayer.Type[] PERSISTENT_LAYER_TYPES = Arrays.stream(MapLayer.Type.values())
        .filter(MapLayer.Type::persistent)
        .toArray(MapLayer.Type[]::new);

    private record RegionSlot(MapLayer.Type layer, int regionX, int regionZ) {
    }

    private record FlushResult(boolean written, int diskVersion) {
    }

    private final Path baseDir;
    private final long token;
    private final DimensionId dimension;
    private final MapWorldService mapWorlds;
    private final Executor io;
    private final TileService tiles;
    private final Logger logger;

    /** Regions {@link #ensureRegionLoaded} has already claimed, per (layer, region), this session. */
    private final Set<RegionSlot> regionLoadTouched = ConcurrentHashMap.newKeySet();
    /** Completion handles for callers, such as export, that must compose after the disk merge. */
    private final Map<RegionSlot, CompletableFuture<Void>> regionLoadCompletions = new ConcurrentHashMap<>();
    /** Bounds queued disk reads when a wide coarse viewport covers hundreds of LOD-0 regions. */
    private final AtomicInteger pendingRegionLoads = new AtomicInteger();
    /** Last {@link RegionColumns#version()} successfully written to disk, per (layer, region). */
    private final Map<RegionSlot, Integer> flushedVersion = new ConcurrentHashMap<>();
    private volatile long lastSweepAtMs = System.currentTimeMillis();

    public RegionDiskCache(
        final Path root,
        final SessionGuard.Session session,
        final MapWorldService mapWorlds,
        final MapExecutors executors,
        final TileService tiles,
        final Logger logger
    ) {
        this(root, session, mapWorlds, executors.io(), tiles, logger);
    }

    RegionDiskCache(
        final Path root,
        final SessionGuard.Session session,
        final MapWorldService mapWorlds,
        final Executor io,
        final TileService tiles,
        final Logger logger
    ) {
        this(root, session, mapWorlds, io, tiles, logger, true);
    }

    RegionDiskCache(
        final Path root,
        final SessionGuard.Session session,
        final MapWorldService mapWorlds,
        final Executor io,
        final TileService tiles,
        final Logger logger,
        final boolean migrateLegacyStorage
    ) {
        final Path worldDir = migrateLegacyStorage
            ? WorldStorageMigration.directory(root, session.world(), logger)
            : root.resolve(session.world().serverId()).resolve(session.world().worldId());
        this.baseDir = worldDir.resolve(session.dimension().fileName());
        this.token = session.token();
        this.dimension = session.dimension();
        this.mapWorlds = mapWorlds;
        this.io = io;
        this.tiles = tiles;
        this.logger = logger;
    }

    /**
     * Main/worker thread: the capture or tile path just touched {@code (regionX, regionZ)} in
     * {@code layerType}. No-ops immediately for a non-persistent layer type (belt-and-suspenders;
     * callers should already gate on {@link MapLayer.Type#persistent()} themselves). Otherwise, if
     * this (layer, region) pair has never been loaded this session, schedules an IO-thread read
     * that merges its cached chunks into the live {@link ColumnStore} as {@link
     * SampleSource#REAL_CACHED}. Cheap and safe to call repeatedly for the same region - only the
     * first caller wins the race and actually schedules work.
     */
    public synchronized boolean ensureRegionLoaded(
        final MapLayer.Type layerType, final int regionX, final int regionZ
    ) {
        return ensureRegionLoadedAsync(layerType, regionX, regionZ) != null;
    }

    /**
     * Schedules the same bounded region load as {@link #ensureRegionLoaded}, returning a handle
     * that completes after the disk data has merged into the active {@link MapWorld}. Returns
     * {@code null} only while the bounded queue is full, allowing viewport callers to retry.
     */
    public synchronized CompletableFuture<Void> ensureRegionLoadedAsync(
        final MapLayer.Type layerType, final int regionX, final int regionZ
    ) {
        if (!layerType.persistent()) {
            return CompletableFuture.completedFuture(null);
        }
        final RegionSlot slot = new RegionSlot(layerType, regionX, regionZ);
        final CompletableFuture<Void> existing = regionLoadCompletions.get(slot);
        if (existing != null) {
            return existing;
        }
        if (pendingRegionLoads.get() >= MAX_PENDING_REGION_LOADS) {
            return null;
        }
        final CompletableFuture<Void> completion = new CompletableFuture<>();
        regionLoadTouched.add(slot);
        regionLoadCompletions.put(slot, completion);
        pendingRegionLoads.incrementAndGet();
        try {
            io.execute(() -> {
                try {
                    loadRegion(layerType, regionX, regionZ);
                    completion.complete(null);
                } catch (final RuntimeException e) {
                    completion.completeExceptionally(e);
                } finally {
                    pendingRegionLoads.decrementAndGet();
                }
            });
            return completion;
        } catch (final RejectedExecutionException e) {
            pendingRegionLoads.decrementAndGet();
            regionLoadTouched.remove(slot);
            regionLoadCompletions.remove(slot);
            completion.completeExceptionally(e);
            return null;
        }
    }

    /** Waits for queue capacity when necessary, then completes after this region has loaded. */
    public CompletableFuture<Void> awaitRegionLoaded(
        final MapLayer.Type layerType, final int regionX, final int regionZ
    ) {
        final CompletableFuture<Void> scheduled;
        final CompletableFuture<Void> capacity;
        synchronized (this) {
            scheduled = ensureRegionLoadedAsync(layerType, regionX, regionZ);
            if (scheduled != null) {
                return scheduled;
            }
            capacity = regionLoadCompletions.values().stream()
                .filter(future -> !future.isDone())
                .findFirst()
                .orElse(CompletableFuture.completedFuture(null));
        }
        return capacity.handle((ignored, error) -> null).thenCompose(
            ignored -> awaitRegionLoaded(layerType, regionX, regionZ)
        );
    }

    /**
     * Loads a square of regions one at a time. Cancelling the returned future stops scheduling
     * after the currently active region read, which keeps large non-viewport consumers bounded.
     */
    public CompletableFuture<Void> awaitRegionsLoaded(
        final MapLayer.Type layerType,
        final int baseRegionX,
        final int baseRegionZ,
        final int regionsPerSide
    ) {
        if (regionsPerSide <= 0) {
            throw new IllegalArgumentException("Region square must have a positive edge");
        }
        final CompletableFuture<Void> result = new CompletableFuture<>();
        awaitNextRegion(layerType, baseRegionX, baseRegionZ, regionsPerSide, 0, result);
        return result;
    }

    private void awaitNextRegion(
        final MapLayer.Type layerType,
        final int baseRegionX,
        final int baseRegionZ,
        final int regionsPerSide,
        final int index,
        final CompletableFuture<Void> result
    ) {
        if (result.isDone()) {
            return;
        }
        final int total = Math.multiplyExact(regionsPerSide, regionsPerSide);
        if (index >= total) {
            result.complete(null);
            return;
        }
        final int regionX = baseRegionX + index % regionsPerSide;
        final int regionZ = baseRegionZ + index / regionsPerSide;
        awaitRegionLoaded(layerType, regionX, regionZ).whenComplete((ignored, error) -> {
            if (result.isDone()) {
                return;
            }
            if (error != null) {
                result.completeExceptionally(error);
                return;
            }
            awaitNextRegion(
                layerType, baseRegionX, baseRegionZ, regionsPerSide, index + 1, result
            );
        });
    }

    private void loadRegion(final MapLayer.Type layerType, final int regionX, final int regionZ) {
        final Path file = regionFile(layerType, regionX, regionZ);
        if (!Files.exists(file)) {
            return;
        }
        final RegionFileCodec.RegionData data;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            data = RegionFileCodec.decode(in, regionX, regionZ, layerType.ordinal());
        } catch (final IOException | RegionFileCodec.RegionFileException e) {
            logger.warn("cache: region file {} unreadable ({}), quarantining and treating as empty", file, e.toString());
            quarantine(file);
            return;
        }

        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return;
        }
        final MapLayer layer = new MapLayer(layerType, 0);
        int merged = 0;
        for (int chunkLocalZ = 0; chunkLocalZ < RegionColumns.CHUNKS; chunkLocalZ++) {
            for (int chunkLocalX = 0; chunkLocalX < RegionColumns.CHUNKS; chunkLocalX++) {
                final int chunkIndex = chunkLocalZ * RegionColumns.CHUNKS + chunkLocalX;
                if (SampleSource.byOrdinal(data.chunkSourceOrdinal()[chunkIndex]) == SampleSource.UNKNOWN) {
                    continue;
                }
                final ChunkSnapshot snapshot = extractChunkSnapshot(data, chunkLocalX, chunkLocalZ, token);
                if (world.put(layer, snapshot, SampleSource.REAL_CACHED)) {
                    merged++;
                }
            }
        }
        if (merged > 0) {
            tiles.markRegionStored(token, dimension, layer, regionX, regionZ);
            logger.info("cache: loaded region r.{}.{} layer={} ({} chunks) as REAL_CACHED", regionX, regionZ, layerType.id(), merged);
        }
    }

    private static ChunkSnapshot extractChunkSnapshot(
        final RegionFileCodec.RegionData data,
        final int chunkLocalX,
        final int chunkLocalZ,
        final long token
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] xaeroBaseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] xaeroOverlayArgb = new int[ChunkSnapshot.COLUMNS];
        final byte[] light = new byte[ChunkSnapshot.COLUMNS];
        final int baseX = chunkLocalX * 16;
        final int baseZ = chunkLocalZ * 16;
        for (int z = 0; z < 16; z++) {
            final int srcRow = (baseZ + z) * RegionColumns.SIZE + baseX;
            final int dstRow = z * 16;
            System.arraycopy(data.surfaceY(), srcRow, surfaceY, dstRow, 16);
            System.arraycopy(data.biomeId(), srcRow, biomeId, dstRow, 16);
            System.arraycopy(data.fluidDepth(), srcRow, fluidDepth, dstRow, 16);
            System.arraycopy(data.kind(), srcRow, kind, dstRow, 16);
            System.arraycopy(data.baseArgb(), srcRow, baseArgb, dstRow, 16);
            System.arraycopy(data.xaeroBaseArgb(), srcRow, xaeroBaseArgb, dstRow, 16);
            System.arraycopy(data.biomeTint(), srcRow, tintArgb, dstRow, 16);
            System.arraycopy(data.overlayArgb(), srcRow, overlayArgb, dstRow, 16);
            System.arraycopy(data.xaeroOverlayArgb(), srcRow, xaeroOverlayArgb, dstRow, 16);
            System.arraycopy(data.light(), srcRow, light, dstRow, 16);
        }
        final int chunkX = (data.rx() << 4) + chunkLocalX;
        final int chunkZ = (data.rz() << 4) + chunkLocalZ;
        return new ChunkSnapshot(
            chunkX, chunkZ, token,
            data.chunkSourceRevision()[chunkLocalZ * RegionColumns.CHUNKS + chunkLocalX],
            surfaceY, biomeId, fluidDepth,
            baseArgb, xaeroBaseArgb, tintArgb, overlayArgb, xaeroOverlayArgb, kind, light
        );
    }

    /**
     * Main thread, once per client tick: drives the 30s-debounced flush and the ~6-region
     * eviction sweep. Self-throttles internally, so it's cheap to call unconditionally every
     * tick - the actual IO-thread sweep only runs when {@link #FLUSH_INTERVAL_MS} has elapsed.
     */
    public void tick(final int playerRegionX, final int playerRegionZ) {
        final long now = System.currentTimeMillis();
        if (now - lastSweepAtMs < FLUSH_INTERVAL_MS) {
            return;
        }
        lastSweepAtMs = now;
        io.execute(() -> sweep(playerRegionX, playerRegionZ));
    }

    private void sweep(final int playerRegionX, final int playerRegionZ) {
        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return;
        }
        int flushed = 0;
        int evicted = 0;
        for (final MapLayer.Type type : PERSISTENT_LAYER_TYPES) {
            final ColumnStore store = world.store(new MapLayer(type, 0));
            for (final RegionColumns region : store.allRegions()) {
                final FlushResult result = flushIfDirty(region, type);
                flushed += result.written() ? 1 : 0;
                final boolean farAway = chebyshev(region.regionX, region.regionZ, playerRegionX, playerRegionZ) > EVICT_DISTANCE_REGIONS;
                if (farAway && result.diskVersion() >= 0 && store.evictIfUnchanged(region, result.diskVersion())) {
                    final RegionSlot slot = new RegionSlot(type, region.regionX, region.regionZ);
                    flushedVersion.remove(slot);
                    regionLoadTouched.remove(slot);
                    regionLoadCompletions.remove(slot);
                    evicted++;
                }
            }
        }
        if (flushed > 0) {
            logger.info("cache: flushed {} regions", flushed);
        }
        if (evicted > 0) {
            logger.info("cache: evicted {} regions from memory (kept on disk)", evicted);
        }
    }

    private FlushResult flushIfDirty(final RegionColumns region, final MapLayer.Type type) {
        final RegionSlot slot = new RegionSlot(type, region.regionX, region.regionZ);
        final int versionAtDecision = region.version();
        final Integer last = flushedVersion.get(slot);
        if (last != null && versionAtDecision <= last) {
            return new FlushResult(false, last);
        }
        final int writtenVersion = writeRegion(region, type);
        if (writtenVersion < 0) {
            return new FlushResult(false, -1);
        }
        flushedVersion.put(slot, writtenVersion);
        return new FlushResult(true, writtenVersion);
    }

    /**
     * Session-end final flush, called by {@link RegionCacheService} with a {@link MapWorld}
     * reference captured directly before the session's {@code current} pointer was swapped -
     * by the time this IO-thread task actually runs, {@code mapWorlds.ifCurrent(token)} would
     * already return null (the new session has rotated in), so this deliberately does not use
     * that check and instead trusts the world it was handed.
     */
    void flushAllOnSessionEnd(final MapWorld world) {
        io.execute(() -> {
            int flushed = 0;
            for (final MapLayer.Type type : PERSISTENT_LAYER_TYPES) {
                final ColumnStore store = world.store(new MapLayer(type, 0));
                for (final RegionColumns region : store.allRegions()) {
                    if (writeRegion(region, type) >= 0) {
                        flushed++;
                    }
                }
            }
            if (flushed > 0) {
                logger.info("cache: flushed {} regions", flushed);
            }
        });
    }

    private int writeRegion(final RegionColumns region, final MapLayer.Type type) {
        final int size = RegionColumns.SIZE;
        final short[] surfaceY = new short[size * size];
        final String[] biomeId = new String[size * size];
        final byte[] fluidDepth = new byte[size * size];
        final int[] baseArgb = new int[size * size];
        final int[] xaeroBaseArgb = new int[size * size];
        final int[] tintArgb = new int[size * size];
        final int[] overlayArgb = new int[size * size];
        final int[] xaeroOverlayArgb = new int[size * size];
        final byte[] kind = new byte[size * size];
        final byte[] light = new byte[size * size];
        final byte[] chunkSource = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final int[] chunkUpdateSeconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final long[] chunkSourceRevision = new long[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final int copiedVersion = region.copyForFlush(
            surfaceY,
            biomeId,
            fluidDepth,
            baseArgb,
            xaeroBaseArgb,
            tintArgb,
            overlayArgb,
            xaeroOverlayArgb,
            kind,
            light,
            chunkSource,
            chunkUpdateSeconds,
            chunkSourceRevision
        );

        final RegionFileCodec.RegionData data = new RegionFileCodec.RegionData(
            region.regionX, region.regionZ, System.currentTimeMillis(),
            chunkSource, chunkUpdateSeconds, chunkSourceRevision,
            surfaceY, fluidDepth, kind, biomeId,
            baseArgb, xaeroBaseArgb, tintArgb, overlayArgb, xaeroOverlayArgb, light
        );
        return writeAtomic(regionFile(type, region.regionX, region.regionZ), data, type.ordinal()) ? copiedVersion : -1;
    }

    private boolean writeAtomic(final Path file, final RegionFileCodec.RegionData data, final int layerOrdinal) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                RegionFileCodec.encode(fos, layerOrdinal, data);
                fos.flush();
                fos.getChannel().force(true);
            }
            move(tmp, file);
            return true;
        } catch (final IOException e) {
            logger.warn("cache: failed to write region file {} ({})", file, e.toString());
            return false;
        }
    }

    private static void move(final Path tmp, final Path file) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine(final Path file) {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("cache: could not quarantine {} ({})", file, e.toString());
        }
    }

    private Path regionFile(final MapLayer.Type type, final int regionX, final int regionZ) {
        return baseDir.resolve(new MapLayer(type, 0).cacheId()).resolve(String.format("r.%d.%d.cfr", regionX, regionZ));
    }

    private static int chebyshev(final int x1, final int z1, final int x2, final int z2) {
        return Math.max(Math.abs(x1 - x2), Math.abs(z1 - z2));
    }
}
