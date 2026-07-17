package cn.net.rms.confluxmap.core.tile;

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
 * {@link TileUpdate}'s javadoc) and only ever renders {@link MapLayer#SURFACE};
 * other layers are a later slice.
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

    public TileService(final MapWorldService mapWorlds, final MapExecutors executors) {
        this.mapWorlds = mapWorlds;
        this.executors = executors;
        this.maxConcurrentCompositions = Math.max(1, executors.workerCount());
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
     * Called by the capture pipeline after a chunk was newly stored. Marks the tile
     * covering that chunk dirty, and - when the chunk sits on the tile's east or
     * north edge - also marks the neighboring tile whose opposite edge depends on
     * this chunk's data for slope shading (see {@link #edgeNeighbor}).
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
            final long dx = key.originBlockX() + 128L - vx;
            final long dz = key.originBlockZ() + 128L - vz;
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
        final ColumnStore store = world.store(MapLayer.SURFACE);
        final RegionColumns region = store.region(key.tileX(), key.tileZ());
        final int[] pixels = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        if (region != null) {
            final RegionColumns west = store.region(key.tileX() - 1, key.tileZ());
            final RegionColumns south = store.region(key.tileX(), key.tileZ() + 1);
            final RegionColumns southWest = store.region(key.tileX() - 1, key.tileZ() + 1);
            composeRegion(region, west, south, southWest, pixels);
        }
        return TileUpdate.fullTile(key, pixels);
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
