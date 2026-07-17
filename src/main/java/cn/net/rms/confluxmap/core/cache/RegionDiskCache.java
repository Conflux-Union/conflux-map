package cn.net.rms.confluxmap.core.cache;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
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
import org.apache.logging.log4j.Logger;

/**
 * Owns the on-disk region cache for one world session (see {@link RegionCacheService}, which
 * creates/discards instances of this class as sessions rotate). Only {@link MapLayer.Type#persistent()}
 * layers are ever touched; M1's capture pipeline only ever populates {@link MapLayer#SURFACE}, so
 * that's the only layer {@link #ensureRegionLoaded} and the live-capture touch point deal with, but
 * the periodic sweep ({@link #tick}) and the final flush walk every persistent layer type generically
 * so a future layer just needs to start writing chunks to start getting cached too.
 *
 * <p>All file IO runs on {@link MapExecutors#io()}. Writes are atomic (tmp file, fsync, {@code
 * ATOMIC_MOVE}); unreadable or corrupt files are quarantined to {@code *.bad} and treated as empty
 * rather than crashing anything, mirroring {@code core.config.ConfigIo}.
 */
public final class RegionDiskCache {
    private static final long FLUSH_INTERVAL_MS = 30_000L;
    private static final int EVICT_DISTANCE_REGIONS = 6;

    private static final MapLayer.Type[] PERSISTENT_LAYER_TYPES = Arrays.stream(MapLayer.Type.values())
        .filter(MapLayer.Type::persistent)
        .toArray(MapLayer.Type[]::new);

    private record RegionRef(int regionX, int regionZ) {
    }

    private record RegionSlot(MapLayer.Type layer, int regionX, int regionZ) {
    }

    private final Path baseDir;
    private final long token;
    private final DimensionId dimension;
    private final MapWorldService mapWorlds;
    private final MapExecutors executors;
    private final TileService tiles;
    private final Logger logger;

    /** Regions {@link #ensureRegionLoaded} has already claimed for {@link MapLayer#SURFACE} this session. */
    private final Set<RegionRef> surfaceTouched = ConcurrentHashMap.newKeySet();
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
        this.baseDir = root.resolve(session.world().serverId()).resolve(session.world().worldId()).resolve(session.dimension().fileName());
        this.token = session.token();
        this.dimension = session.dimension();
        this.mapWorlds = mapWorlds;
        this.executors = executors;
        this.tiles = tiles;
        this.logger = logger;
    }

    /**
     * Main/worker thread: the capture or tile path just touched {@code (regionX, regionZ)} in
     * {@link MapLayer#SURFACE}. If this region has never been loaded this session, schedules an
     * IO-thread read that merges its cached chunks into the live {@link ColumnStore} as
     * {@link SampleSource#REAL_CACHED}. Cheap and safe to call repeatedly for the same region -
     * only the first caller wins the race and actually schedules work.
     */
    public void ensureRegionLoaded(final int regionX, final int regionZ) {
        if (!surfaceTouched.add(new RegionRef(regionX, regionZ))) {
            return;
        }
        executors.io().execute(() -> loadRegion(regionX, regionZ));
    }

    private void loadRegion(final int regionX, final int regionZ) {
        final Path file = regionFile(MapLayer.Type.SURFACE, regionX, regionZ);
        if (!Files.exists(file)) {
            return;
        }
        final RegionFileCodec.RegionData data;
        try (InputStream in = new BufferedInputStream(Files.newInputStream(file))) {
            data = RegionFileCodec.decode(in, regionX, regionZ, MapLayer.Type.SURFACE.ordinal());
        } catch (final IOException | RegionFileCodec.RegionFileException e) {
            logger.warn("cache: region file {} unreadable ({}), quarantining and treating as empty", file, e.toString());
            quarantine(file);
            return;
        }

        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return;
        }
        final ColumnStore store = world.store(MapLayer.SURFACE);
        int merged = 0;
        for (int chunkLocalZ = 0; chunkLocalZ < RegionColumns.CHUNKS; chunkLocalZ++) {
            for (int chunkLocalX = 0; chunkLocalX < RegionColumns.CHUNKS; chunkLocalX++) {
                final int chunkIndex = chunkLocalZ * RegionColumns.CHUNKS + chunkLocalX;
                if (SampleSource.byOrdinal(data.chunkSourceOrdinal()[chunkIndex]) == SampleSource.UNKNOWN) {
                    continue;
                }
                final ChunkSnapshot snapshot = extractChunkSnapshot(data, chunkLocalX, chunkLocalZ, token);
                if (store.put(snapshot, SampleSource.REAL_CACHED)) {
                    merged++;
                    tiles.markChunkStored(token, dimension, MapLayer.SURFACE, snapshot.chunkX, snapshot.chunkZ);
                }
            }
        }
        if (merged > 0) {
            logger.info("cache: loaded region r.{}.{} ({} chunks) as REAL_CACHED", regionX, regionZ, merged);
        }
    }

    private static ChunkSnapshot extractChunkSnapshot(
        final RegionFileCodec.RegionData data,
        final int chunkLocalX,
        final int chunkLocalZ,
        final long token
    ) {
        final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
        final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
        final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
        final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
        final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
        final int baseX = chunkLocalX * 16;
        final int baseZ = chunkLocalZ * 16;
        for (int z = 0; z < 16; z++) {
            final int srcRow = (baseZ + z) * RegionColumns.SIZE + baseX;
            final int dstRow = z * 16;
            System.arraycopy(data.surfaceY(), srcRow, surfaceY, dstRow, 16);
            System.arraycopy(data.fluidDepth(), srcRow, fluidDepth, dstRow, 16);
            System.arraycopy(data.kind(), srcRow, kind, dstRow, 16);
            System.arraycopy(data.baseArgb(), srcRow, baseArgb, dstRow, 16);
            System.arraycopy(data.biomeTint(), srcRow, tintArgb, dstRow, 16);
            System.arraycopy(data.overlayArgb(), srcRow, overlayArgb, dstRow, 16);
        }
        final int chunkX = (data.rx() << 4) + chunkLocalX;
        final int chunkZ = (data.rz() << 4) + chunkLocalZ;
        return new ChunkSnapshot(chunkX, chunkZ, token, surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind);
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
        executors.io().execute(() -> sweep(playerRegionX, playerRegionZ));
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
                flushed += flushIfDirty(store, region, type);
                final boolean farAway = chebyshev(region.regionX, region.regionZ, playerRegionX, playerRegionZ) > EVICT_DISTANCE_REGIONS;
                if (farAway) {
                    store.remove(region.regionX, region.regionZ);
                    flushedVersion.remove(new RegionSlot(type, region.regionX, region.regionZ));
                    if (type == MapLayer.Type.SURFACE) {
                        surfaceTouched.remove(new RegionRef(region.regionX, region.regionZ));
                    }
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

    /** Returns 1 if the region was dirty and got written, 0 otherwise. */
    private int flushIfDirty(final ColumnStore store, final RegionColumns region, final MapLayer.Type type) {
        final RegionSlot slot = new RegionSlot(type, region.regionX, region.regionZ);
        // Read the version BEFORE copying data out: the copy is guaranteed to see at least
        // everything written up to this point, so recording this (possibly stale-low) version
        // as "flushed" never overstates what's actually on disk - worst case we redundantly
        // reflush unchanged data next sweep, never lose a write.
        final int versionAtDecision = region.version();
        final Integer last = flushedVersion.get(slot);
        if (last != null && versionAtDecision <= last) {
            return 0;
        }
        writeRegion(region, type);
        flushedVersion.put(slot, versionAtDecision);
        return 1;
    }

    /**
     * Session-end final flush, called by {@link RegionCacheService} with a {@link MapWorld}
     * reference captured directly before the session's {@code current} pointer was swapped -
     * by the time this IO-thread task actually runs, {@code mapWorlds.ifCurrent(token)} would
     * already return null (the new session has rotated in), so this deliberately does not use
     * that check and instead trusts the world it was handed.
     */
    void flushAllOnSessionEnd(final MapWorld world) {
        executors.io().execute(() -> {
            int flushed = 0;
            for (final MapLayer.Type type : PERSISTENT_LAYER_TYPES) {
                final ColumnStore store = world.store(new MapLayer(type, 0));
                for (final RegionColumns region : store.allRegions()) {
                    writeRegion(region, type);
                    flushed++;
                }
            }
            if (flushed > 0) {
                logger.info("cache: flushed {} regions", flushed);
            }
        });
    }

    private void writeRegion(final RegionColumns region, final MapLayer.Type type) {
        final int size = RegionColumns.SIZE;
        final short[] surfaceY = new short[size * size];
        final byte[] fluidDepth = new byte[size * size];
        final int[] baseArgb = new int[size * size];
        final int[] tintArgb = new int[size * size];
        final int[] overlayArgb = new int[size * size];
        final byte[] kind = new byte[size * size];
        final byte[] chunkSource = new byte[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        final int[] chunkUpdateSeconds = new int[RegionFileCodec.CHUNK_TABLE_ENTRIES];
        region.copyForFlush(surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind, chunkSource, chunkUpdateSeconds);

        final RegionFileCodec.RegionData data = new RegionFileCodec.RegionData(
            region.regionX, region.regionZ, System.currentTimeMillis(),
            chunkSource, chunkUpdateSeconds, surfaceY, fluidDepth, kind, baseArgb, tintArgb, overlayArgb
        );
        writeAtomic(regionFile(type, region.regionX, region.regionZ), data, type.ordinal());
    }

    private void writeAtomic(final Path file, final RegionFileCodec.RegionData data, final int layerOrdinal) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            try (FileOutputStream fos = new FileOutputStream(tmp.toFile())) {
                RegionFileCodec.encode(fos, layerOrdinal, data);
                fos.flush();
                fos.getChannel().force(true);
            }
            move(tmp, file);
        } catch (final IOException e) {
            logger.warn("cache: failed to write region file {} ({})", file, e.toString());
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
