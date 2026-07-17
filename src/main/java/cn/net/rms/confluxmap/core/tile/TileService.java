package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionDiskCache;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Owns the dirty-tile composition queue and the bounded render-thread upload
 * queue. Composition itself runs on {@link MapExecutors#workers()}; only
 * {@link #drainUploads(int)} is meant to be called from the render thread.
 *
 * <p>M1 always recomposes a tile from scratch on every dirty event (see
 * {@link TileUpdate}'s javadoc). {@link TileKey#layerId()} carries which
 * {@link MapLayer} a tile belongs to ({@link MapLayer#parse} recovers it);
 * every layer shares this one composition path.
 */
public final class TileService {
    private static final int UPLOAD_QUEUE_CAPACITY = 64;

    private final MapWorldService mapWorlds;
    private final MapExecutors executors;
    private final int maxConcurrentCompositions;

    /** Guarded by {@code this}: tiles waiting to be composed, with the session token that requested them. */
    private final Map<TileKey, Long> dirty = new HashMap<>();
    /** Guarded by {@code this}: tiles currently being composed on a worker. */
    private final Set<TileKey> inFlight = new HashSet<>();

    /** Guarded by {@code this}: bounded, key-deduped upload queue (newest composition wins). */
    private final LinkedHashMap<TileKey, TileUpdate> uploads = new LinkedHashMap<>();

    private volatile int viewpointX;
    private volatile int viewpointZ;

    /**
     * Late-bound instead of a constructor parameter: {@link RegionCacheService} itself needs a
     * {@link TileService} reference (to mark tiles dirty after a disk-cache merge), so the two
     * can't both take each other as constructor arguments. The composition root wires this once
     * after constructing both.
     */
    private volatile RegionCacheService regionCache;

    public TileService(final MapWorldService mapWorlds, final MapExecutors executors) {
        this.mapWorlds = mapWorlds;
        this.executors = executors;
        this.maxConcurrentCompositions = Math.max(1, executors.workerCount());
    }

    public void bindRegionCache(final RegionCacheService regionCache) {
        this.regionCache = regionCache;
    }

    /** Main thread, from the session tracker: forget every queued/in-flight tile and pending upload. */
    public void onSessionChanged(final SessionGuard.Session session) {
        synchronized (this) {
            dirty.clear();
            inFlight.clear();
            uploads.clear();
        }
    }

    /** Where the viewer currently is, for dirty-tile composition priority. */
    public void setViewpoint(final int blockX, final int blockZ) {
        viewpointX = blockX;
        viewpointZ = blockZ;
    }

    /**
     * Called by the capture pipeline after a chunk was newly stored. Marks the LOD-0
     * tile covering that chunk dirty, and - when the chunk sits on the tile's east or
     * north edge - also marks the neighboring tile whose opposite edge depends on
     * this chunk's data for slope shading (see {@link #edgeNeighbor}). Also marks the
     * covering tile at every higher LOD (1-{@link TileMath#MAX_LOD}) dirty, so an
     * already-composed zoomed-out tile refreshes to include this chunk's data next
     * time it's viewed; the {@link #dirty} map dedupes cheaply since each LOD is a
     * distinct {@link TileKey}.
     */
    public void markChunkStored(
        final long token,
        final DimensionId dimensionId,
        final MapLayer layer,
        final int chunkX,
        final int chunkZ
    ) {
        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return;
        }
        final TileKey key = TileKey.ofChunk(world.session().world(), dimensionId, layer, chunkX, chunkZ);
        markDirty(key, token);
        final TileKey edge = edgeNeighbor(key, chunkX, chunkZ);
        if (edge != null) {
            markDirty(edge, token);
        }
        final int blockX = chunkX << 4;
        final int blockZ = chunkZ << 4;
        for (int lod = 1; lod <= TileMath.MAX_LOD; lod++) {
            markDirty(TileKey.ofBlock(world.session().world(), dimensionId, layer, lod, blockX, blockZ), token);
        }
    }

    /**
     * The §4 slope term always compares a column against its (x-1, z+1) neighbor.
     * A column on a tile's local x==15 (east) edge is that neighbor for some column
     * on the local x==0 edge of the tile one to the east; symmetrically for z==0
     * (north) feeding the tile one to the north's z==255 (south) edge. Only those
     * two directions ever need healing - the opposite two edges consume data that
     * is already inside their own tile.
     */
    private static TileKey edgeNeighbor(final TileKey key, final int chunkX, final int chunkZ) {
        final int localX = chunkX & 15;
        final int localZ = chunkZ & 15;
        if (localX == 15) {
            return new TileKey(key.world(), key.dimension(), key.layerId(), key.lod(), key.tileX() + 1, key.tileZ());
        }
        if (localZ == 0) {
            return new TileKey(key.world(), key.dimension(), key.layerId(), key.lod(), key.tileX(), key.tileZ() - 1);
        }
        return null;
    }

    /** For a tile that's visible but has never been composed (or requested again after being evicted). */
    public void requestTile(final TileKey key) {
        final MapWorld world = mapWorlds.current();
        if (world == null) {
            return;
        }
        final SessionGuard.Session session = world.session();
        if (!key.world().equals(session.world()) || !key.dimension().equals(session.dimension())) {
            return;
        }
        markDirty(key, session.token());
        // LOD0 tile coordinates are region coordinates; higher LODs cover many regions at once and
        // the disk cache only ever stores LOD0 data, so there's nothing more specific to load there -
        // those regions get pulled in individually as their own LOD0 tiles are requested/captured.
        // Only MapLayer.Type.persistent() layers ever touch the disk cache at all (dynamic layers
        // like CAVE_AUTO/NETHER_CURRENT are memory-only per cave-nether-layers.md's live-map model).
        if (key.lod() == 0) {
            final MapLayer.Type layerType = MapLayer.parse(key.layerId()).type();
            if (layerType.persistent()) {
                requestRegionLoad(layerType, key.tileX(), key.tileZ());
            }
        }
    }

    private void requestRegionLoad(final MapLayer.Type layerType, final int regionX, final int regionZ) {
        final RegionCacheService cacheService = regionCache;
        if (cacheService == null) {
            return;
        }
        final RegionDiskCache cache = cacheService.current();
        if (cache != null) {
            cache.ensureRegionLoaded(layerType, regionX, regionZ);
        }
    }

    private void markDirty(final TileKey key, final long token) {
        synchronized (this) {
            if (inFlight.contains(key)) {
                // Already composing; the in-flight pass will pick up a fresh copy of the
                // store, so nothing more to do unless it's already done - re-mark dirty is
                // cheap and safe either way since composeTile() below removes from inFlight
                // before this lock is next taken.
                dirty.put(key, token);
                return;
            }
            dirty.put(key, token);
        }
        pump();
    }

    private void pump() {
        while (true) {
            final TileKey next;
            final long token;
            synchronized (this) {
                if (inFlight.size() >= maxConcurrentCompositions || dirty.isEmpty()) {
                    return;
                }
                next = nearestDirty();
                token = dirty.remove(next);
                inFlight.add(next);
            }
            executors.workers().execute(() -> composeAndFinish(next, token));
        }
    }

    /** Caller must hold the monitor. */
    private TileKey nearestDirty() {
        final int vx = viewpointX;
        final int vz = viewpointZ;
        TileKey best = null;
        long bestDist = Long.MAX_VALUE;
        for (final TileKey key : dirty.keySet()) {
            // Half the tile's own edge length in blocks, e.g. 128 at LOD0, 2048 at LOD4 -
            // using a fixed LOD0-sized offset here would skew priority for higher-LOD tiles.
            final long half = 128L << key.lod();
            final long dx = key.originBlockX() + half - vx;
            final long dz = key.originBlockZ() + half - vz;
            final long dist = dx * dx + dz * dz;
            if (dist < bestDist) {
                bestDist = dist;
                best = key;
            }
        }
        return best;
    }

    private void composeAndFinish(final TileKey key, final long token) {
        try {
            final TileUpdate update = composeTile(key, token);
            if (update != null) {
                pushUpload(update);
            }
        } finally {
            synchronized (this) {
                inFlight.remove(key);
            }
            pump();
        }
    }

    private TileUpdate composeTile(final TileKey key, final long token) {
        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return null;
        }
        final ColumnStore store = world.store(MapLayer.parse(key.layerId()));
        final int[] pixels = key.lod() == 0
            ? composeLod0(store, key.tileX(), key.tileZ())
            : composeLodN(store, key);
        return TileUpdate.fullTile(key, pixels);
    }

    /** One LOD-0 region (256x256 blocks, 1 pixel/block), fully transparent where untouched. */
    private static int[] composeLod0(final ColumnStore store, final int regionX, final int regionZ) {
        final int[] pixels = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        final RegionColumns region = store.region(regionX, regionZ);
        if (region != null) {
            final RegionColumns west = store.region(regionX - 1, regionZ);
            final RegionColumns south = store.region(regionX, regionZ + 1);
            final RegionColumns southWest = store.region(regionX - 1, regionZ + 1);
            composeRegion(region, west, south, southWest, pixels);
        }
        return pixels;
    }

    /**
     * A LOD-{@code key.lod()} tile covers {@code 2^lod x 2^lod} LOD-0 regions. Each
     * covered region is composed exactly as at LOD0 (reusing {@link #composeLod0}, so
     * cross-region slope shading at every LOD-0 boundary - including ones that fall
     * inside this LOD's tile, not just at its own edges - stays correct), then
     * box-averaged down by {@code 2^lod} (via repeated 2x2 {@link Argb#average4}
     * passes, i.e. a small mipmap chain) and stitched into its quadrant of the
     * 256x256 output. Regions with no data at all are skipped, leaving their
     * quadrant at the default fully-transparent value.
     */
    private static int[] composeLodN(final ColumnStore store, final TileKey key) {
        final int lod = key.lod();
        final int size = RegionColumns.SIZE;
        final int regionsPerSide = 1 << lod;
        final int subSize = size >> lod;
        final int baseRegionX = key.tileX() << lod;
        final int baseRegionZ = key.tileZ() << lod;
        final int[] outPixels = new int[size * size];
        for (int dz = 0; dz < regionsPerSide; dz++) {
            for (int dx = 0; dx < regionsPerSide; dx++) {
                final int regionX = baseRegionX + dx;
                final int regionZ = baseRegionZ + dz;
                if (store.region(regionX, regionZ) == null) {
                    continue;
                }
                final int[] full = composeLod0(store, regionX, regionZ);
                final int[] downsampled = downsample(full, size, lod);
                stitch(downsampled, subSize, outPixels, dx * subSize, dz * subSize);
            }
        }
        return outPixels;
    }

    /** Repeated 2x2 box-average halving, {@code steps} times: {@code size -> size >> steps}. */
    private static int[] downsample(final int[] src, final int size, final int steps) {
        int[] current = src;
        int currentSize = size;
        for (int step = 0; step < steps; step++) {
            final int nextSize = currentSize / 2;
            final int[] next = new int[nextSize * nextSize];
            for (int z = 0; z < nextSize; z++) {
                final int z0 = z * 2;
                final int z1 = z0 + 1;
                for (int x = 0; x < nextSize; x++) {
                    final int x0 = x * 2;
                    final int x1 = x0 + 1;
                    next[z * nextSize + x] = Argb.average4(
                        current[z0 * currentSize + x0], current[z0 * currentSize + x1],
                        current[z1 * currentSize + x0], current[z1 * currentSize + x1]
                    );
                }
            }
            current = next;
            currentSize = nextSize;
        }
        return current;
    }

    /** Copies a {@code blockSize x blockSize} block into the 256x256 {@code out} at pixel offset (offsetX, offsetZ). */
    private static void stitch(final int[] block, final int blockSize, final int[] out, final int offsetX, final int offsetZ) {
        final int outSize = RegionColumns.SIZE;
        for (int z = 0; z < blockSize; z++) {
            System.arraycopy(block, z * blockSize, out, (offsetZ + z) * outSize + offsetX, blockSize);
        }
    }

    private static void composeRegion(
        final RegionColumns region,
        final RegionColumns west,
        final RegionColumns south,
        final RegionColumns southWest,
        final int[] outPixels
    ) {
        final int size = RegionColumns.SIZE;
        final short[] surfaceY = new short[size * size];
        final byte[] fluidDepth = new byte[size * size];
        final int[] baseArgb = new int[size * size];
        final int[] tintArgb = new int[size * size];
        final int[] overlayArgb = new int[size * size];
        final byte[] kind = new byte[size * size];
        region.copyChunkRows(0, size, surfaceY, fluidDepth, baseArgb, tintArgb, overlayArgb, kind);

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int idx = z * size + x;
                final byte k = kind[idx];
                if (k == SurfaceKind.UNKNOWN.ordinal() || k == SurfaceKind.VOID.ordinal()) {
                    outPixels[idx] = Argb.TRANSPARENT;
                    continue;
                }
                final Integer neighborHeight = neighborHeight(x, z, surfaceY, region, west, south, southWest);
                final double shade = ShadingPipeline.combinedShade(
                    true, true, surfaceY[idx], ShadingPipeline.REFERENCE_HEIGHT, neighborHeight
                );
                final int shadedBase = ShadingPipeline.applyShade(Argb.multiply(baseArgb[idx], tintArgb[idx]), shade);
                final int shadedOverlay = overlayArgb[idx] == Argb.TRANSPARENT
                    ? Argb.TRANSPARENT
                    : ShadingPipeline.applyShade(overlayArgb[idx], shade);
                outPixels[idx] = k == SurfaceKind.WATER.ordinal() || k == SurfaceKind.ICE.ordinal()
                    ? ShadingPipeline.compositeOver(shadedBase, shadedOverlay)
                    : ShadingPipeline.compositeOver(shadedOverlay, shadedBase);
            }
        }
    }

    /**
     * §4's fixed diagonal neighbor is (x-1, z+1). Reads it from the already-copied
     * local rows when inside this tile, else from the relevant adjacent region's edge
     * (west for x underflow, south for z overflow, south-west for both at once). A
     * missing region, or a neighbor column that was never captured (still holding the
     * {@link ChunkSnapshot#NO_SURFACE} sentinel), leaves the term flat per the spec's
     * "missing neighbor -> unshaded" rule.
     */
    private static Integer neighborHeight(
        final int x,
        final int z,
        final short[] localSurfaceY,
        final RegionColumns region,
        final RegionColumns west,
        final RegionColumns south,
        final RegionColumns southWest
    ) {
        final int nx = x - 1;
        final int nz = z + 1;
        final short value;
        if (nx < 0 && nz > 255) {
            value = southWest == null ? ChunkSnapshot.NO_SURFACE : southWest.surfaceYAt(255, 0);
        } else if (nx < 0) {
            value = west == null ? ChunkSnapshot.NO_SURFACE : west.surfaceYAt(255, nz);
        } else if (nz > 255) {
            value = south == null ? ChunkSnapshot.NO_SURFACE : south.surfaceYAt(nx, 0);
        } else {
            value = localSurfaceY[nz * RegionColumns.SIZE + nx];
        }
        return value == ChunkSnapshot.NO_SURFACE ? null : (int) value;
    }

    private void pushUpload(final TileUpdate update) {
        synchronized (this) {
            uploads.remove(update.key());
            uploads.put(update.key(), update);
            while (uploads.size() > UPLOAD_QUEUE_CAPACITY) {
                final TileKey oldest = uploads.keySet().iterator().next();
                uploads.remove(oldest);
            }
        }
    }

    /** Render thread: pop up to {@code max} freshly-composed tiles to upload to the GPU. */
    public List<TileUpdate> drainUploads(final int max) {
        final List<TileUpdate> result = new ArrayList<>(Math.min(max, UPLOAD_QUEUE_CAPACITY));
        synchronized (this) {
            final var it = uploads.entrySet().iterator();
            while (it.hasNext() && result.size() < max) {
                result.add(it.next().getValue());
                it.remove();
            }
        }
        return result;
    }
}
