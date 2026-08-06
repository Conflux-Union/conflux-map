package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionDiskCache;
import cn.net.rms.confluxmap.core.color.BiomeColorPalette;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.predict.CorrectionTile;
import cn.net.rms.confluxmap.core.predict.MapSourceSelector;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.RegionColumns;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Owns the dirty-tile composition queue and the bounded render-thread upload
 * queue. Composition itself runs on {@link MapExecutors#workers()}; only
 * {@link #drainUploads(int)} is meant to be called from the render thread.
 *
 * <p>M1 always recomposes a tile from scratch on every dirty event (see
 * {@link TileUpdate}'s javadoc). {@link TileKey#layerId()} carries which
 * {@link MapLayer} a tile belongs to ({@link MapLayer#parse} recovers it);
 * every layer shares this one composition path. During a visible fullscreen/minimap viewport the
 * queue is serialized and uses top-left row-major order; background work retains distance priority.
 */
public final class TileService {
    private static final int UPLOAD_QUEUE_CAPACITY = 64;
    private static final int VIEWPORT_REGION_LOAD_ATTEMPTS = 128;

    private final MapWorldService mapWorlds;
    private final MapExecutors executors;
    private final ConfluxConfig config;
    private final DaylightModel daylightModel;
    private final int maxConcurrentCompositions;

    /** Guarded by {@code this}: tiles waiting to be composed, with the session token that requested them. */
    private final Map<TileKey, Long> dirty = new HashMap<>();
    /** Guarded by {@code this}: tiles currently being composed on a worker. */
    private final Set<TileKey> inFlight = new HashSet<>();
    /** Biome variants requested at least once this session; avoids composing unused twins eagerly. */
    private final Set<TileKey> requestedBiomeTiles = new HashSet<>();

    /** Guarded by {@code this}: bounded, key-deduped upload queue (newest composition wins). */
    private final LinkedHashMap<TileKey, TileUpdate> uploads = new LinkedHashMap<>();

    /** Latest fullscreen viewport. Visible requests use deterministic top-left row-major order. */
    private ViewportRect viewport;
    /** Next LOD-0 region in the current viewport to offer to the bounded disk-load queue. */
    private long viewportRegionLoadCursor;

    private volatile int viewpointX;
    private volatile int viewpointZ;

    /**
     * Late-bound instead of a constructor parameter: {@link RegionCacheService} itself needs a
     * {@link TileService} reference (to mark tiles dirty after a disk-cache merge), so the two
     * can't both take each other as constructor arguments. The composition root wires this once
     * after constructing both.
     */
    private volatile RegionCacheService regionCache;
    private volatile Consumer<TileKey> realCoverageListener = ignored -> { };
    private volatile ChunkViewport localAuthorityViewport;

    public TileService(
        final MapWorldService mapWorlds,
        final MapExecutors executors,
        final ConfluxConfig config,
        final DaylightModel daylightModel
    ) {
        this.mapWorlds = mapWorlds;
        this.executors = executors;
        this.config = config;
        this.daylightModel = daylightModel;
        this.maxConcurrentCompositions = Math.max(1, executors.workerCount());
    }

    public void bindRegionCache(final RegionCacheService regionCache) {
        this.regionCache = regionCache;
    }

    /** Registers the prediction-plane invalidator for newly available real-map coverage. */
    public void bindRealCoverageListener(final Consumer<TileKey> listener) {
        realCoverageListener = listener == null ? ignored -> { } : listener;
    }

    /** Publishes the server send-distance snapshot used for local source authority. */
    public void setLocalAuthorityViewport(final ChunkViewport viewport) {
        localAuthorityViewport = viewport;
    }

    /** Main thread, from the session tracker: forget every queued/in-flight tile and pending upload. */
    public void onSessionChanged(final SessionGuard.Session session) {
        synchronized (this) {
            dirty.clear();
            inFlight.clear();
            requestedBiomeTiles.clear();
            uploads.clear();
            viewport = null;
            viewportRegionLoadCursor = 0L;
        }
        localAuthorityViewport = null;
    }

    /** Where the viewer currently is, for dirty-tile composition priority. */
    public void setViewpoint(final int blockX, final int blockZ) {
        viewpointX = blockX;
        viewpointZ = blockZ;
    }

    /**
     * Main thread: tells the composer which tiles the fullscreen map is about to request. Requests
     * inside this rectangle are started from the top-left, left-to-right, then top-to-bottom.
     */
    public void setViewport(
        final MapLayer layer,
        final int lod,
        final int minTileX,
        final int maxTileX,
        final int minTileZ,
        final int maxTileZ
    ) {
        final ViewportRect next = new ViewportRect(layer.type(), lod, minTileX, maxTileX, minTileZ, maxTileZ);
        synchronized (this) {
            if (!next.equals(viewport)) {
                viewportRegionLoadCursor = 0L;
            }
            viewport = next;
        }
        scheduleViewportRegionLoads(next);
        pump();
    }

    /** Clears fullscreen ordering after the screen closes so minimap requests return to distance priority. */
    public void clearViewport() {
        synchronized (this) {
            viewport = null;
            viewportRegionLoadCursor = 0L;
        }
        pump();
    }

    /**
     * Returns a fully recomposed CPU tile for non-render consumers such as PNG export. Persistent
     * backing regions are loaded first, one at a time, so an arbitrarily large export neither
     * replaces the visible viewport nor floods the bounded disk queue.
     */
    public CompletableFuture<int[]> snapshotTile(
        final TileKey key,
        final boolean dynamicLighting,
        final float daylightFactor
    ) {
        final MapWorld world = mapWorlds.current();
        if (world == null
            || !key.world().equals(world.session().world())
            || !key.dimension().equals(world.session().dimension())) {
            return CompletableFuture.failedFuture(new CancellationException("Map session changed"));
        }
        final long token = world.session().token();
        final MapLayer layer = MapLayer.parse(BiomeTileKeys.realLayerId(key.layerId()));
        final RegionDiskCache cache = regionCache == null ? null : regionCache.current();
        final CompletableFuture<Void> loaded;
        if (cache != null && layer.type().persistent()) {
            final int regionsPerSide = 1 << key.lod();
            final int baseRegionX = key.tileX() << key.lod();
            final int baseRegionZ = key.tileZ() << key.lod();
            loaded = cache.awaitRegionsLoaded(
                layer.type(), baseRegionX, baseRegionZ, regionsPerSide
            );
        } else {
            loaded = CompletableFuture.completedFuture(null);
        }
        final CompletableFuture<int[]> composed = loaded.thenApplyAsync(ignored -> {
            final TileUpdate update = composeTile(
                key, token, dynamicLighting, daylightFactor
            );
            if (update == null) {
                throw new CancellationException("Map session changed");
            }
            return update.argbPixels();
        }, executors.workers());
        final CompletableFuture<int[]> result = new CompletableFuture<>();
        composed.whenComplete((pixels, error) -> {
            if (error == null) {
                result.complete(pixels);
            } else {
                result.completeExceptionally(error);
            }
        });
        result.whenComplete((ignored, error) -> {
            if (result.isCancelled()) {
                loaded.cancel(true);
                composed.cancel(true);
            }
        });
        return result;
    }

    /**
     * Called by the capture pipeline after a chunk was newly stored. Marks the tile covering
     * that chunk dirty at every LOD. When the chunk touches a tile edge, every
     * adjacent tile whose symmetric one-pixel relief stencil consumes that edge is included too.
     * Interior chunks still invalidate exactly one tile per LOD.
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
        final SessionGuard.Session session = world.session();
        for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
            markReliefConsumers(
                session, dimensionId, layer, lod, chunkX, chunkZ, 16 << lod, token
            );
            realCoverageListener.accept(new TileKey(
                session.world(), dimensionId, layer.cacheId(), lod,
                Math.floorDiv(chunkX, 16 << lod), Math.floorDiv(chunkZ, 16 << lod)
            ));
        }
    }

    /**
     * Disk-cache counterpart of {@link #markChunkStored}: one region read may merge up to 256
     * chunks, so invalidate its affected tiles once instead of issuing the same parent keys 256
     * times. A LOD-0 region fills a complete tile and therefore touches all eight neighbors;
     * coarser LODs only include the parent edges that this region actually reaches.
     */
    public void markRegionStored(
        final long token,
        final DimensionId dimensionId,
        final MapLayer layer,
        final int regionX,
        final int regionZ
    ) {
        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return;
        }
        final SessionGuard.Session session = world.session();
        for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
            markReliefConsumers(
                session, dimensionId, layer, lod, regionX, regionZ, 1 << lod, token
            );
            realCoverageListener.accept(new TileKey(
                session.world(), dimensionId, layer.cacheId(), lod,
                Math.floorDiv(regionX, 1 << lod), Math.floorDiv(regionZ, 1 << lod)
            ));
        }
    }

    /**
     * Clears predicted pixels whose exact block footprint belongs to a real cached/live chunk.
     * Every supported LOD remains chunk-aligned: LOD0 has 16 pixels per chunk and LOD4 has one.
     */
    public void maskKnownRealPixels(final TileKey realKey, final int[] predictedPixels) {
        maskPredictedPixels(realKey, predictedPixels, null, false);
    }

    /** Keeps prediction below both real sources while allowing newer synchronized chunks through. */
    public void maskPredictedPixels(
        final TileKey realKey,
        final int[] predictedPixels,
        final CorrectionTile corrections,
        final boolean enhancedProfile
    ) {
        maskPredictedPixels(
            realKey,
            predictedPixels,
            corrections == null ? null : corrections.copyEvaluated(),
            corrections == null ? null : corrections.copyPixelSourceRevisions(),
            enhancedProfile
        );
    }

    public void maskPredictedPixels(
        final TileKey realKey,
        final int[] predictedPixels,
        final byte[] syncEvaluated,
        final long[] syncSourceRevisions,
        final boolean enhancedProfile
    ) {
        if (predictedPixels.length != RegionColumns.SIZE * RegionColumns.SIZE) {
            throw new IllegalArgumentException("predictedPixels must contain one 256x256 tile");
        }
        if (syncEvaluated != null && syncEvaluated.length != PatchCodec.MASK_BYTES) {
            throw new IllegalArgumentException("sync evaluated mask has the wrong length");
        }
        if (syncSourceRevisions != null
            && syncSourceRevisions.length != RegionColumns.SIZE * RegionColumns.SIZE) {
            throw new IllegalArgumentException("sync source revisions have the wrong length");
        }
        final MapWorld world = mapWorlds.current();
        if (world == null
            || !realKey.world().equals(world.session().world())
            || !realKey.dimension().equals(world.session().dimension())) {
            return;
        }
        final MapLayer layer = MapLayer.parse(BiomeTileKeys.realLayerId(realKey.layerId()));
        final ColumnStore store = world.store(layer);
        final int chunksPerTile = 16 << realKey.lod();
        final int pixelsPerChunk = 16 >> realKey.lod();
        final int baseChunkX = realKey.tileX() * chunksPerTile;
        final int baseChunkZ = realKey.tileZ() * chunksPerTile;
        for (int chunkDz = 0; chunkDz < chunksPerTile; chunkDz++) {
            for (int chunkDx = 0; chunkDx < chunksPerTile; chunkDx++) {
                final int chunkX = baseChunkX + chunkDx;
                final int chunkZ = baseChunkZ + chunkDz;
                final ChunkViewport localViewport = localAuthorityViewport;
                final boolean locallyAuthoritative = localViewport != null
                    && localViewport.contains(chunkX, chunkZ);
                final boolean localPresent = store.hasRealChunk(chunkX, chunkZ);
                if (!locallyAuthoritative && !localPresent) {
                    continue;
                }
                final long localRevision = localPresent
                    ? store.realChunkSourceRevision(chunkX, chunkZ)
                    : MapSourceSelector.UNKNOWN_REVISION;
                final int pixelX = chunkDx * pixelsPerChunk;
                final int pixelZ = chunkDz * pixelsPerChunk;
                for (int dz = 0; dz < pixelsPerChunk; dz++) {
                    final int row = (pixelZ + dz) * RegionColumns.SIZE;
                    for (int dx = 0; dx < pixelsPerChunk; dx++) {
                        final int pixel = row + pixelX + dx;
                        final boolean syncPresent = syncEvaluated != null
                            && (syncEvaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0;
                        final long syncRevision = syncPresent && syncSourceRevisions != null
                            ? syncSourceRevisions[pixel]
                            : MapSourceSelector.UNKNOWN_REVISION;
                        if (locallyAuthoritative || !MapSourceSelector.syncWins(
                            localPresent, localRevision, syncPresent, syncRevision, enhancedProfile
                        )) {
                            predictedPixels[pixel] = Argb.TRANSPARENT;
                        }
                    }
                }
            }
        }
    }

    private void markReliefConsumers(
        final SessionGuard.Session session,
        final DimensionId dimensionId,
        final MapLayer layer,
        final int lod,
        final int unitX,
        final int unitZ,
        final int unitsPerTile,
        final long token
    ) {
        final int tileX = Math.floorDiv(unitX, unitsPerTile);
        final int tileZ = Math.floorDiv(unitZ, unitsPerTile);
        final int localX = Math.floorMod(unitX, unitsPerTile);
        final int localZ = Math.floorMod(unitZ, unitsPerTile);
        final int minDx = localX == 0 ? -1 : 0;
        final int maxDx = localX == unitsPerTile - 1 ? 1 : 0;
        final int minDz = localZ == 0 ? -1 : 0;
        final int maxDz = localZ == unitsPerTile - 1 ? 1 : 0;
        for (int dz = minDz; dz <= maxDz; dz++) {
            for (int dx = minDx; dx <= maxDx; dx++) {
                final TileKey key = new TileKey(
                    session.world(), dimensionId, layer.cacheId(), lod, tileX + dx, tileZ + dz
                );
                markDirty(key, token);
                if (dx == 0 && dz == 0) {
                    // Biome mode is a flat colour plane and has no cross-tile relief stencil.
                    markBiomeDirtyIfRequested(BiomeTileKeys.toBiome(key), token);
                }
            }
        }
    }

    /**
     * Called (from {@code mc.world.McDaylightTracker}) whenever {@link DaylightModel}'s
     * quantized bucket changes: marks every currently-resident SURFACE-layer tile dirty -
     * the LOD-0 tile of each region already in the {@link ColumnStore}, plus the covering
     * tile at every higher LOD (deduped by the {@link #dirty} map, since many regions share
     * one coarse tile) - so all of them recompose with the new day/night + block-light blend.
     * Without the higher LODs the zoomed-out fullscreen map would keep showing whatever
     * daylight its tiles were last composed with while the LOD-0 minimap moved on. Uses the
     * existing dirty-queue prioritization/budget (see {@link #pump}), so this never stalls
     * the queue even for a large resident set - it only fires on a slow drift (a few dozen
     * times per full day/night cycle), not per-frame.
     */
    public void markSurfaceRelit(final long token) {
        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return;
        }
        final SessionGuard.Session session = world.session();
        final ColumnStore store = world.store(MapLayer.SURFACE);
        for (final RegionColumns region : store.allRegions()) {
            for (int lod = 0; lod <= TileMath.MAX_LOD; lod++) {
                final TileKey key = new TileKey(
                    session.world(), session.dimension(), MapLayer.SURFACE.cacheId(), lod,
                    region.regionX >> lod, region.regionZ >> lod
                );
                markDirty(key, token);
            }
        }
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
        synchronized (this) {
            if (BiomeTileKeys.isBiome(key)) {
                requestedBiomeTiles.add(key);
            }
            // A missing texture is checked once per rendered frame. Do not turn that repeated
            // check into an invalidation loop while the same tile is already queued or composing.
            if (dirty.containsKey(key) || inFlight.contains(key)) {
                return;
            }
            dirty.put(key, session.token());
        }
        pump();
        // A viewport proactively schedules every covered region at higher LODs. Keep this direct
        // LOD0 touch for callers that request a tile without first publishing a viewport.
        if (key.lod() == 0) {
            final MapLayer.Type layerType = MapLayer.parse(
                BiomeTileKeys.realLayerId(key.layerId())
            ).type();
            if (layerType.persistent()) {
                requestRegionLoad(layerType, key.tileX(), key.tileZ());
            }
        }
    }

    private void markBiomeDirtyIfRequested(final TileKey key, final long token) {
        synchronized (this) {
            if (!requestedBiomeTiles.contains(key)) {
                return;
            }
        }
        markDirty(key, token);
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

    /**
     * Offers a bounded row-major slice of the coarse viewport's LOD-0 regions to the disk cache.
     * The cache has its own pending-read cap; when full, the cursor stays put and the next rendered
     * frame retries. Already accepted regions are session-deduped by {@link RegionDiskCache}.
     */
    private void scheduleViewportRegionLoads(final ViewportRect active) {
        if (!active.layerType().persistent()) {
            return;
        }
        final RegionCacheService cacheService = regionCache;
        final RegionDiskCache cache = cacheService == null ? null : cacheService.current();
        if (cache == null) {
            return;
        }
        final long tileWidth = (long) active.maxTileX() - active.minTileX() + 1L;
        final long tileHeight = (long) active.maxTileZ() - active.minTileZ() + 1L;
        if (tileWidth <= 0L || tileHeight <= 0L) {
            return;
        }
        final int regionsPerTile = 1 << active.lod();
        final long regionsPerTileSquared = (long) regionsPerTile * regionsPerTile;
        final long total = tileWidth * tileHeight * regionsPerTileSquared;
        int attempted = 0;
        while (attempted < VIEWPORT_REGION_LOAD_ATTEMPTS) {
            final long cursor;
            synchronized (this) {
                if (!active.equals(viewport) || viewportRegionLoadCursor >= total) {
                    return;
                }
                cursor = viewportRegionLoadCursor;
            }
            final long tileIndex = cursor / regionsPerTileSquared;
            final int regionIndex = (int) (cursor % regionsPerTileSquared);
            final int tileX = active.minTileX() + (int) (tileIndex % tileWidth);
            final int tileZ = active.minTileZ() + (int) (tileIndex / tileWidth);
            final int regionX = tileX * regionsPerTile + regionIndex % regionsPerTile;
            final int regionZ = tileZ * regionsPerTile + regionIndex / regionsPerTile;
            if (!cache.ensureRegionLoaded(active.layerType(), regionX, regionZ)) {
                return;
            }
            synchronized (this) {
                if (active.equals(viewport) && viewportRegionLoadCursor == cursor) {
                    viewportRegionLoadCursor++;
                }
            }
            attempted++;
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
                final int compositionLimit = viewport == null ? maxConcurrentCompositions : 1;
                if (inFlight.size() >= compositionLimit || dirty.isEmpty()) {
                    return;
                }
                next = nearestDirty();
                if (next == null) {
                    return;
                }
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
        final ViewportRect activeViewport = viewport;
        TileKey best = null;
        long bestDist = Long.MAX_VALUE;
        boolean bestInViewport = false;
        for (final TileKey key : dirty.keySet()) {
            if (inFlight.contains(key)) {
                continue;
            }
            final boolean inViewport = activeViewport != null && activeViewport.contains(key);
            if (inViewport) {
                if (!bestInViewport || best == null || rowMajorBefore(key, best)) {
                    best = key;
                    bestInViewport = true;
                }
                continue;
            }
            if (bestInViewport) {
                continue;
            }
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

    private static boolean rowMajorBefore(final TileKey candidate, final TileKey current) {
        return candidate.tileZ() < current.tileZ()
            || (candidate.tileZ() == current.tileZ() && candidate.tileX() < current.tileX());
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
        return composeTile(key, token, config.dynamicLighting, daylightModel.factor());
    }

    private TileUpdate composeTile(
        final TileKey key,
        final long token,
        final boolean dynamicLighting,
        final float requestedDaylightFactor
    ) {
        final MapWorld world = mapWorlds.ifCurrent(token);
        if (world == null) {
            return null;
        }
        final boolean biomeMode = BiomeTileKeys.isBiome(key);
        final MapLayer layer = MapLayer.parse(BiomeTileKeys.realLayerId(key.layerId()));
        final ColumnStore store = world.store(layer);
        // The roof view is block-accurate and lives around one almost-flat Y. Keep the shared,
        // symmetric local relief there, but omit the fixed Y=80 absolute-height wash that used to
        // turn the whole bedrock roof pale.
        final boolean applyAbsoluteHeight = layer.type() != MapLayer.Type.NETHER_CEILING;
        final boolean applyNetherCeilingLight = layer.type() == MapLayer.Type.NETHER_CEILING;
        // Dynamic daylight only touches SURFACE. NETHER_CEILING has no sky cycle, but its static
        // per-column block light is applied separately below from ChunkSnapshot#light.
        final boolean applyDaylight = !biomeMode
            && layer.type() == MapLayer.Type.SURFACE
            && dynamicLighting;
        final float daylightFactor = applyDaylight ? requestedDaylightFactor : 1f;
        // SURFACE tiles always carry their re-light inputs, even with dynamic lighting off
        // (compose then leaves pixels undarkened, which is exactly "composed at factor 1.0"),
        // so toggling the setting on relights already-uploaded tiles instead of stranding them.
        final byte[] lightPlane = !biomeMode && layer.type() == MapLayer.Type.SURFACE
            ? new byte[RegionColumns.SIZE * RegionColumns.SIZE]
            : null;
        // Only the sub-rects actually composed from in-memory regions are claimed; the
        // texture cache preserves its previous pixels everywhere else, so a recompose can
        // never erase a quadrant whose backing region was evicted to disk in the meantime.
        final List<TileUpdate.Rect> changed = new ArrayList<>();
        final int[] pixels;
        if (key.lod() == 0) {
            pixels = composeLod0(
                store, key.tileX(), key.tileZ(), biomeMode, applyAbsoluteHeight,
                applyNetherCeilingLight, applyDaylight, daylightFactor, lightPlane
            );
            if (store.region(key.tileX(), key.tileZ()) != null) {
                changed.add(new TileUpdate.Rect(0, 0, RegionColumns.SIZE, RegionColumns.SIZE));
            }
        } else {
            pixels = composeLodN(
                store, key, biomeMode, applyAbsoluteHeight,
                applyNetherCeilingLight, applyDaylight, daylightFactor, lightPlane, changed
            );
        }
        final TileUpdate.Relight relight = lightPlane == null
            ? null
            : new TileUpdate.Relight(daylightFactor, lightPlane);
        return new TileUpdate(key, pixels, List.copyOf(changed), relight);
    }

    /**
     * One LOD-0 region (256x256 blocks, 1 pixel/block), fully transparent where untouched.
     * {@code outLight}, when non-null, receives the region's per-pixel block-light plane
     * (zeros where untouched) for the tile's {@link TileUpdate.Relight}.
     */
    private static int[] composeLod0(
        final ColumnStore store,
        final int regionX,
        final int regionZ,
        final boolean biomeMode,
        final boolean applyAbsoluteHeight,
        final boolean applyNetherCeilingLight,
        final boolean applyDaylight,
        final float daylightFactor,
        final byte[] outLight
    ) {
        final int[] pixels = new int[RegionColumns.SIZE * RegionColumns.SIZE];
        final RegionColumns region = store.region(regionX, regionZ);
        if (region != null) {
            final RegionNeighborhood neighborhood = new RegionNeighborhood(
                region,
                store.region(regionX, regionZ - 1),
                store.region(regionX + 1, regionZ - 1),
                store.region(regionX + 1, regionZ),
                store.region(regionX + 1, regionZ + 1),
                store.region(regionX, regionZ + 1),
                store.region(regionX - 1, regionZ + 1),
                store.region(regionX - 1, regionZ),
                store.region(regionX - 1, regionZ - 1)
            );
            composeRegion(
                neighborhood, pixels,
                biomeMode, applyAbsoluteHeight, applyNetherCeilingLight,
                applyDaylight, daylightFactor, outLight
            );
        }
        return pixels;
    }

    /**
     * A LOD-{@code key.lod()} tile covers {@code 2^lod x 2^lod} LOD-0 regions. Each
     * covered region is composed exactly as at LOD0 (reusing {@link #composeLod0}, so
     * cross-region slope shading at every LOD-0 boundary - including ones that fall
     * inside this LOD's tile, not just at its own edges - stays correct), then
     * box-averaged down by {@code 2^lod} (via repeated 2x2 alpha-weighted {@link Argb#average4Weighted}
     * passes, i.e. a small mipmap chain, so a region that is only partly explored downsamples to a
     * clean translucent value instead of darkening toward black) and stitched into its quadrant of
     * the 256x256 output. Regions not in memory are skipped AND left out of {@code outChanged},
     * so the texture cache keeps showing whatever that quadrant held before - regions evicted to
     * disk between two composes must not be erased from an already-drawn zoomed-out tile.
     */
    private static int[] composeLodN(
        final ColumnStore store,
        final TileKey key,
        final boolean biomeMode,
        final boolean applyAbsoluteHeight,
        final boolean applyNetherCeilingLight,
        final boolean applyDaylight,
        final float daylightFactor,
        final byte[] outLight,
        final List<TileUpdate.Rect> outChanged
    ) {
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
                final byte[] fullLight = outLight == null ? null : new byte[size * size];
                final int[] full = composeLod0(
                    store, regionX, regionZ, biomeMode, applyAbsoluteHeight,
                    applyNetherCeilingLight, applyDaylight, daylightFactor, fullLight
                );
                final int[] downsampled = downsample(full, size, lod);
                stitch(downsampled, subSize, outPixels, dx * subSize, dz * subSize);
                if (outLight != null) {
                    stitchLight(downsampleLight(fullLight, size, lod), subSize, outLight, dx * subSize, dz * subSize);
                }
                outChanged.add(new TileUpdate.Rect(dx * subSize, dz * subSize, subSize, subSize));
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
                    next[z * nextSize + x] = Argb.average4Weighted(
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

    /**
     * Block-light counterpart of {@link #downsample}: repeated rounded 2x2 averaging of the
     * 0-15 levels. A plain average (no alpha weighting) is enough here - the levels only
     * steer how strongly a re-light darkens each pixel, and at coarse LODs a torch is a
     * sub-pixel detail either way.
     */
    private static byte[] downsampleLight(final byte[] src, final int size, final int steps) {
        byte[] current = src;
        int currentSize = size;
        for (int step = 0; step < steps; step++) {
            final int nextSize = currentSize / 2;
            final byte[] next = new byte[nextSize * nextSize];
            for (int z = 0; z < nextSize; z++) {
                final int z0 = z * 2;
                final int z1 = z0 + 1;
                for (int x = 0; x < nextSize; x++) {
                    final int x0 = x * 2;
                    final int x1 = x0 + 1;
                    final int sum = current[z0 * currentSize + x0] + current[z0 * currentSize + x1]
                        + current[z1 * currentSize + x0] + current[z1 * currentSize + x1];
                    next[z * nextSize + x] = (byte) ((sum + 2) >> 2);
                }
            }
            current = next;
            currentSize = nextSize;
        }
        return current;
    }

    private static void stitchLight(final byte[] block, final int blockSize, final byte[] out, final int offsetX, final int offsetZ) {
        final int outSize = RegionColumns.SIZE;
        for (int z = 0; z < blockSize; z++) {
            System.arraycopy(block, z * blockSize, out, (offsetZ + z) * outSize + offsetX, blockSize);
        }
    }

    private static void composeRegion(
        final RegionNeighborhood neighborhood,
        final int[] outPixels,
        final boolean biomeMode,
        final boolean applyAbsoluteHeight,
        final boolean applyNetherCeilingLight,
        final boolean applyDaylight,
        final float daylightFactor,
        final byte[] outLight
    ) {
        final int size = RegionColumns.SIZE;
        final short[] surfaceY = new short[size * size];
        final String[] biomeId = new String[size * size];
        final byte[] fluidDepth = new byte[size * size];
        final int[] baseArgb = new int[size * size];
        final int[] tintArgb = new int[size * size];
        final int[] overlayArgb = new int[size * size];
        final byte[] kind = new byte[size * size];
        final byte[] light = new byte[size * size];
        neighborhood.center().copyChunkRows(
            0, size, surfaceY, biomeId, fluidDepth,
            baseArgb, tintArgb, overlayArgb, kind, light
        );
        if (outLight != null) {
            System.arraycopy(light, 0, outLight, 0, size * size);
        }

        for (int z = 0; z < size; z++) {
            for (int x = 0; x < size; x++) {
                final int idx = z * size + x;
                final byte k = kind[idx];
                if (k == SurfaceKind.UNKNOWN.ordinal() || k == SurfaceKind.VOID.ordinal()) {
                    outPixels[idx] = Argb.TRANSPARENT;
                    continue;
                }
                if (biomeMode) {
                    outPixels[idx] = BiomeColorPalette.color(biomeId[idx]);
                    continue;
                }
                final double surfaceHeightShade = applyAbsoluteHeight
                    ? ShadingPipeline.detailedHeightShade(surfaceY[idx], ShadingPipeline.REFERENCE_HEIGHT)
                    : 0.0;
                final double surfaceRelief = reliefMultiplier(
                    x, z, false, surfaceY, fluidDepth, kind, neighborhood
                );
                final int shadedBase = ShadingPipeline.applyBrightnessMultiplier(
                    ShadingPipeline.applyShade(Argb.multiply(baseArgb[idx], tintArgb[idx]), surfaceHeightShade),
                    surfaceRelief
                );
                final boolean waterOrIce = k == SurfaceKind.WATER.ordinal() || k == SurfaceKind.ICE.ordinal();
                final int floorY = surfaceY[idx] - (fluidDepth[idx] & 0xFF);
                final double overlayHeightShade = waterOrIce && applyAbsoluteHeight
                    ? ShadingPipeline.detailedHeightShade(floorY, ShadingPipeline.REFERENCE_HEIGHT)
                    : surfaceHeightShade;
                final double overlayRelief = waterOrIce
                    ? reliefMultiplier(x, z, true, surfaceY, fluidDepth, kind, neighborhood)
                    : surfaceRelief;
                int shadedOverlay = overlayArgb[idx] == Argb.TRANSPARENT
                    ? Argb.TRANSPARENT
                    : ShadingPipeline.applyBrightnessMultiplier(
                        ShadingPipeline.applyShade(overlayArgb[idx], overlayHeightShade), overlayRelief
                    );
                if (waterOrIce && shadedOverlay != Argb.TRANSPARENT) {
                    shadedOverlay = ShadingPipeline.applyBrightnessMultiplier(
                        shadedOverlay, ShadingPipeline.seafloorBrightness(fluidDepth[idx] & 0xFF)
                    );
                }
                int composed = waterOrIce
                    ? ShadingPipeline.compositeOver(shadedBase, shadedOverlay)
                    : ShadingPipeline.compositeOver(shadedOverlay, shadedBase);
                if (applyDaylight) {
                    composed = ShadingPipeline.applyDaylight(composed, daylightFactor, light[idx]);
                } else if (applyNetherCeilingLight) {
                    composed = LightTint.applyBlockLightOverAmbient(
                        composed, light[idx] & 0xFF, true
                    );
                }
                outPixels[idx] = composed;
            }
        }
    }

    private static double reliefMultiplier(
        final int x,
        final int z,
        final boolean bathymetry,
        final short[] localSurfaceY,
        final byte[] localFluidDepth,
        final byte[] localKind,
        final RegionNeighborhood neighborhood
    ) {
        return ShadingPipeline.directionalReliefMultiplier(
            reliefHeight(x - 1, z, bathymetry, localSurfaceY, localFluidDepth, localKind, neighborhood),
            reliefHeight(x, z + 1, bathymetry, localSurfaceY, localFluidDepth, localKind, neighborhood),
            reliefHeight(x - 1, z + 1, bathymetry, localSurfaceY, localFluidDepth, localKind, neighborhood),
            reliefHeight(x + 1, z, bathymetry, localSurfaceY, localFluidDepth, localKind, neighborhood),
            reliefHeight(x, z - 1, bathymetry, localSurfaceY, localFluidDepth, localKind, neighborhood),
            reliefHeight(x + 1, z - 1, bathymetry, localSurfaceY, localFluidDepth, localKind, neighborhood),
            1
        );
    }

    private static Integer reliefHeight(
        final int x,
        final int z,
        final boolean bathymetry,
        final short[] localSurfaceY,
        final byte[] localFluidDepth,
        final byte[] localKind,
        final RegionNeighborhood neighborhood
    ) {
        if (x >= 0 && x < RegionColumns.SIZE && z >= 0 && z < RegionColumns.SIZE) {
            final int index = z * RegionColumns.SIZE + x;
            final short value = localSurfaceY[index];
            if (value == ChunkSnapshot.NO_SURFACE) {
                return null;
            }
            if (!bathymetry
                || (localKind[index] != SurfaceKind.WATER.ordinal()
                    && localKind[index] != SurfaceKind.ICE.ordinal())) {
                return (int) value;
            }
            return (int) value - (localFluidDepth[index] & 0xFF);
        }
        final int regionDx = x < 0 ? -1 : x >= RegionColumns.SIZE ? 1 : 0;
        final int regionDz = z < 0 ? -1 : z >= RegionColumns.SIZE ? 1 : 0;
        final RegionColumns region = neighborhood.at(regionDx, regionDz);
        if (region == null) {
            return null;
        }
        final int localX = Math.floorMod(x, RegionColumns.SIZE);
        final int localZ = Math.floorMod(z, RegionColumns.SIZE);
        final short value = region.reliefYAt(localX, localZ, bathymetry);
        return value == ChunkSnapshot.NO_SURFACE ? null : (int) value;
    }

    private record RegionNeighborhood(
        RegionColumns center,
        RegionColumns north,
        RegionColumns northEast,
        RegionColumns east,
        RegionColumns southEast,
        RegionColumns south,
        RegionColumns southWest,
        RegionColumns west,
        RegionColumns northWest
    ) {
        RegionColumns at(final int dx, final int dz) {
            if (dx == 0 && dz == 0) {
                return center;
            }
            if (dx == 0 && dz < 0) {
                return north;
            }
            if (dx > 0 && dz < 0) {
                return northEast;
            }
            if (dx > 0 && dz == 0) {
                return east;
            }
            if (dx > 0) {
                return southEast;
            }
            if (dx == 0) {
                return south;
            }
            if (dz > 0) {
                return southWest;
            }
            if (dz == 0) {
                return west;
            }
            return northWest;
        }
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

    private record ViewportRect(
        MapLayer.Type layerType, int lod, int minTileX, int maxTileX, int minTileZ, int maxTileZ
    ) {
        boolean contains(final TileKey key) {
            return key.lod() == lod
                && key.tileX() >= minTileX && key.tileX() <= maxTileX
                && key.tileZ() >= minTileZ && key.tileZ() <= maxTileZ;
        }
    }

    /**
     * Public entry point for a composer that isn't this class's own {@link #pump}/{@link
     * #composeAndFinish} pipeline (see {@code core.predict.PredictionTileService}) to share this
     * same bounded, key-deduped upload queue instead of maintaining a second one.
     */
    public void submitUpload(final TileUpdate update) {
        pushUpload(update);
    }

    /** Drops queued uploads matching a cache-plane predicate before that plane is rebuilt. */
    public synchronized void discardUploads(final Predicate<TileKey> predicate) {
        uploads.keySet().removeIf(predicate);
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
