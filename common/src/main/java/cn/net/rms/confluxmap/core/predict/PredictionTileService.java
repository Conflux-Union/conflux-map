package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;

/**
 * Predicted-underlay twin of {@code core.tile.TileService}: a synchronized dirty-tile queue
 * bounded by an in-flight cap (one per worker outside a visible viewport, a small bounded cap
 * inside one that still picks tiles in top-left row-major order), session-token guarded - same discipline,
 * entirely separate data. Predictions never enter {@code ColumnStore}/{@code
 * RegionDiskCache}; composition here samples cubiomes directly (via {@link NativeBaselineSampler})
 * and shares {@code TileService}'s upload queue through {@link TileService#submitUpload}. Native
 * availability, a known seed, and a supported dimension all gate every request - see {@link
 * PredictionState#predictable} - so a missing seed or a native-load failure degrades to a silent
 * no-op rather than an error anywhere in this class.
 */
public final class PredictionTileService {
    public enum LowerCoverageState {
        READY,
        PENDING,
        MISSING_OR_STALE
    }

    /** Re-entry freshness window for corrections; expiry never schedules background polling. */
    public static final long CORRECTION_REUSE_TTL_MS = 30L * 60L * 1_000L;
    /** About 38 MiB worst case; enough for one ordinary fullscreen viewport plus its next mip. */
    private static final int REUSABLE_TILE_LIMIT = 64;
    private final SessionGuard sessionGuard;
    private final PredictionState state;
    private final MapExecutors executors;
    private final TileService uploads;
    private final LongSupplier millisClock;
    private final int maxConcurrentCompositions;
    /**
     * Bounded concurrency while a fullscreen viewport is active. Strict serialization (1) left the
     * predicted underlay trailing the real tile's transparent unexplored pixels during pans, so the
     * screen background bled through as black gaps; row-major ordering still picks the next tile.
     */
    private static final int VISIBLE_CONCURRENCY = 6;
    private volatile CorrectionStore correctionStore;
    private volatile DaylightModel daylightModel;
    private volatile PredictionViewMode viewMode = PredictionViewMode.EVERYWHERE;

    /** Guarded by {@code this}: tiles waiting to be composed, with the session token that requested them. */
    private final Map<TileKey, Long> dirty = new HashMap<>();
    /** Guarded by {@code this}: tiles currently being composed on a worker. */
    private final Set<TileKey> inFlight = new HashSet<>();
    /** Prediction variants requested or uploaded at least once this session. */
    private final Set<TileKey> requestedTiles = new HashSet<>();
    /** Tracked textures that were last composed under an older view mode. */
    private final Set<TileKey> staleViewModeTiles = new HashSet<>();
    /** Tracked textures whose local real-map coverage changed while they were off-screen. */
    private final Set<TileKey> staleRealCoverageTiles = new HashSet<>();
    /** Guarded by {@code this}: output-pixel metadata retained for fullscreen cursor/actions. */
    private final Map<TileKey, TileMetadata> metadataTiles = new HashMap<>();
    /** Recent CPU results used to build a coarser tile without resampling. */
    private final PredictionMipCache mipCache = new PredictionMipCache(REUSABLE_TILE_LIMIT);
    /** Parent tiles waiting for one bounded disk-backed lower-LOD reduction. */
    private final LinkedHashMap<TileKey, Long> lowerCoverageQueue = new LinkedHashMap<>();
    private final Set<TileKey> lowerCoverageInFlight = new HashSet<>();
    private final Set<TileKey> missingLowerCoverage = new HashSet<>();
    private long lowerCoverageGeneration;
    /** Guarded by {@code this}: invalidates compositions started before a manual/session reload. */
    private long reloadGeneration;

    /** Latest fullscreen viewport; visible predictions are scheduled in row-major order. */
    private ViewportRect viewport;

    private volatile int viewpointX;
    private volatile int viewpointZ;

    public PredictionTileService(
        final SessionGuard sessionGuard,
        final PredictionState state,
        final MapExecutors executors,
        final TileService uploads
    ) {
        this(sessionGuard, state, executors, uploads, System::currentTimeMillis);
    }

    public PredictionTileService(
        final SessionGuard sessionGuard,
        final PredictionState state,
        final MapExecutors executors,
        final TileService uploads,
        final LongSupplier millisClock
    ) {
        this.sessionGuard = sessionGuard;
        this.state = state;
        this.executors = executors;
        this.uploads = uploads;
        this.millisClock = millisClock;
        this.maxConcurrentCompositions = Math.max(1, executors.workerCount());
        uploads.bindRealCoverageListener(this::onRealCoverageChanged);
    }

    private void onRealCoverageChanged(final TileKey realKey) {
        final SessionGuard.Session session = sessionGuard.current();
        final MapLayer layer;
        try {
            layer = MapLayer.parse(BiomeTileKeys.realLayerId(realKey.layerId()));
        } catch (final IllegalArgumentException e) {
            return;
        }
        if (!session.active()
            || !realKey.world().equals(session.world())
            || !realKey.dimension().equals(session.dimension())
            || !layer.equals(PredictionDimensions.layer(session.dimension()))) {
            return;
        }
        synchronized (this) {
            queueRealCoverageRefresh(PredictedTileKeys.toPredicted(realKey), session.token());
            queueRealCoverageRefresh(
                PredictedTileKeys.toPredicted(BiomeTileKeys.toBiome(realKey)), session.token()
            );
        }
        pump();
    }

    /** Caller must hold the monitor. */
    private void queueRealCoverageRefresh(final TileKey key, final long token) {
        invalidateCachedTileAndAncestors(key);
        metadataTiles.remove(key);
        if (requestedTiles.contains(key)) {
            staleRealCoverageTiles.add(key);
            if (viewport != null && viewport.contains(key)) {
                dirty.put(key, token);
            }
        }
    }

    public void bindCorrectionStore(final CorrectionStore store) {
        this.correctionStore = store;
    }

    public void bindDaylightModel(final DaylightModel model) {
        this.daylightModel = model;
    }

    public void setViewMode(final PredictionViewMode mode) {
        final PredictionViewMode nextMode = mode == null ? PredictionViewMode.EVERYWHERE : mode;
        final SessionGuard.Session session = sessionGuard.current();
        synchronized (this) {
            if (nextMode == viewMode) {
                return;
            }
            viewMode = nextMode;
            staleViewModeTiles.addAll(requestedTiles);
            if (viewport != null && session.active()
                && viewport.dimension().equals(session.dimension())) {
                for (final TileKey key : staleViewModeTiles) {
                    if (viewport.contains(key)) {
                        metadataTiles.remove(key);
                        dirty.put(key, session.token());
                    }
                }
            }
        }
        pump();
    }

    public PredictionViewMode viewMode() {
        return viewMode;
    }

    private float applyLayerLighting(
        final TileKey key,
        final int[] pixels,
        final byte[] blockLight
    ) {
        if (BiomeTileKeys.isBiome(key)) {
            return Float.NaN;
        }
        final String realLayer = BiomeTileKeys.realLayerId(
            PredictedTileKeys.realLayerId(key.layerId())
        );
        final MapLayer.Type layer = MapLayer.parse(realLayer).type();
        if (layer == MapLayer.Type.NETHER_CEILING) {
            for (int pixel = 0; pixel < pixels.length; pixel++) {
                pixels[pixel] = LightTint.applyBlockLightOverAmbient(
                    pixels[pixel], blockLight[pixel] & 0xFF, true
                );
            }
            return Float.NaN;
        }
        if (layer != MapLayer.Type.SURFACE) {
            return Float.NaN;
        }
        final DaylightModel model = daylightModel;
        if (model == null) {
            return Float.NaN;
        }
        final float factor = model.factor();
        for (int pixel = 0; pixel < pixels.length; pixel++) {
            pixels[pixel] = ShadingPipeline.applyDaylight(
                pixels[pixel], factor, blockLight[pixel] & 0xFF
            );
        }
        return factor;
    }

    private static TileUpdate tileUpdate(
        final TileKey key,
        final int[] pixels,
        final byte[] blockLight,
        final float composedDaylight
    ) {
        return Float.isNaN(composedDaylight)
            ? TileUpdate.fullTile(key, pixels)
            : TileUpdate.fullTile(
                key, pixels, new TileUpdate.Relight(composedDaylight, blockLight)
            );
    }

    private static long[] unknownPixelRevisions() {
        final long[] revisions = new long[PatchCodec.PIXELS];
        java.util.Arrays.fill(revisions, Long.MIN_VALUE);
        return revisions;
    }

    private static boolean hasKnownRevision(final long[] revisions) {
        for (final long revision : revisions) {
            if (revision != Long.MIN_VALUE) {
                return true;
            }
        }
        return false;
    }

    /**
     * Composes one immutable CPU tile under an explicit view mode without mutating the visible
     * viewport, GPU upload queue, metadata cache, or the globally selected prediction mode.
     */
    public CompletableFuture<int[]> snapshotTile(
        final TileKey key,
        final PredictionViewMode mode
    ) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active()
            || !key.world().equals(session.world())
            || !key.dimension().equals(session.dimension())) {
            return CompletableFuture.failedFuture(new CancellationException("Map session changed"));
        }
        return CompletableFuture.supplyAsync(() -> {
            final Composition composition = composeTile(key, session.token(), mode);
            if (composition == null) {
                return null;
            }
            return composition.update().argbPixels();
        }, executors.workers());
    }

    /** Applies a server patch and queues the affected predicted tile for recomposition. */
    public boolean applyCorrection(
        final CorrectionStore.Key key, final long revision, final byte[] presence, final cn.net.rms.confluxmap.core.net.PatchCodec.Patch patch
    ) {
        return applyCorrection(key, revision, presence, patch, System.currentTimeMillis());
    }

    public boolean applyRegionCorrection(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkPatchCodec.Patch patch,
        final long validatedAtMillis
    ) {
        return applyRegionCorrection(
            dimension, lod, slice, patch,
            cn.net.rms.confluxmap.core.net.Proto.PATCH_MODE_RESIDUAL,
            cn.net.rms.confluxmap.core.net.MapSyncCompatibility.STABLE_PREDICTOR,
            validatedAtMillis
        );
    }

    public boolean applyRegionCorrection(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkPatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.applyRegionSlice(
            dimension, lod, slice, patch, patchMode, baselineProfile, validatedAtMillis
        )) {
            return false;
        }
        markCorrectionDirty(regionKey(dimension, lod, slice));
        return true;
    }

    public boolean validateRegionCorrection(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long revision,
        final long validatedAtMillis
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.validateRegionSlice(
            dimension, lod, slice, revision, validatedAtMillis
        )) {
            return false;
        }
        markCorrectionDirty(regionKey(dimension, lod, slice));
        return true;
    }

    public boolean invalidateRegionCorrection(
        final String dimension, final int lod, final ChunkRegionSlice slice
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.invalidateRegionSlice(dimension, lod, slice)) {
            return false;
        }
        final CorrectionStore.Key key = regionKey(dimension, lod, slice);
        final SessionGuard.Session session = sessionGuard.current();
        final TileKey tile = correctionTile(session, key);
        if (tile == null) {
            return true;
        }
        synchronized (this) {
            lowerCoverageGeneration++;
            missingLowerCoverage.clear();
            mipCache.removeCoverage(tile);
            metadataTiles.remove(tile);
        }
        return true;
    }

    public boolean applyCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final cn.net.rms.confluxmap.core.net.PatchCodec.Patch patch,
        final long validatedAtMillis
    ) {
        return applyCorrection(
            key, revision, presence, patch,
            cn.net.rms.confluxmap.core.net.Proto.PATCH_MODE_RESIDUAL,
            cn.net.rms.confluxmap.core.net.MapSyncCompatibility.STABLE_PREDICTOR,
            validatedAtMillis
        );
    }

    public boolean applyCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final cn.net.rms.confluxmap.core.net.PatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.apply(
            key, revision, presence, patch, patchMode, baselineProfile, validatedAtMillis
        )) {
            return false;
        }
        markCorrectionDirty(key);
        return true;
    }

    /** Validates an unchanged committed correction snapshot. */
    public boolean validateCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final long validatedAtMillis
    ) {
        final CorrectionStore store = correctionStore;
        if (store == null || !store.validate(key, revision, presence, validatedAtMillis)) {
            return false;
        }
        markCorrectionDirty(key);
        return true;
    }

    /** Records uncommitted scan progress without replacing or recomposing the drawable snapshot. */
    public boolean applyPartialCorrection(
        final CorrectionStore.Key key,
        final byte[] presence,
        final cn.net.rms.confluxmap.core.net.PatchCodec.Patch patch
    ) {
        final CorrectionStore store = correctionStore;
        if (store == null || !store.applyPartial(key, presence, patch)) {
            return false;
        }
        final SessionGuard.Session session = sessionGuard.current();
        final TileKey tile = correctionTile(session, key);
        if (tile == null) {
            return true;
        }
        synchronized (this) {
            mipCache.removeCoverage(tile);
        }
        return true;
    }

    /** Invalidates correction freshness and every derived mip while keeping the uploaded tile visible. */
    public boolean invalidateCorrectionValidation(final CorrectionStore.Key key) {
        return invalidateCorrectionValidations(List.of(key));
    }

    /** Applies one server invalidation batch without rewriting the persistent journal per tile. */
    public boolean invalidateCorrectionValidations(final Collection<CorrectionStore.Key> keys) {
        final CorrectionStore store = correctionStore;
        if (store == null || keys == null || keys.isEmpty()) {
            return false;
        }
        final boolean changed = store.invalidateCoverages(keys);
        final SessionGuard.Session session = sessionGuard.current();
        synchronized (this) {
            lowerCoverageGeneration++;
            missingLowerCoverage.clear();
            for (final CorrectionStore.Key key : keys) {
                if (key == null) {
                    continue;
                }
                final TileKey tile = correctionTile(session, key);
                if (tile == null) {
                    continue;
                }
                mipCache.removeCoverage(tile);
                metadataTiles.remove(tile);
            }
        }
        return changed;
    }

    private void markCorrectionDirty(final CorrectionStore.Key key) {
        final SessionGuard.Session session = sessionGuard.current();
        final TileKey tile = correctionTile(session, key);
        if (tile == null) {
            return;
        }
        synchronized (this) {
            lowerCoverageGeneration++;
            missingLowerCoverage.clear();
            metadataTiles.remove(tile);
            metadataTiles.remove(BiomeTileKeys.toBiome(tile));
            invalidateCachedTileAndAncestors(tile);
        }
        markDirty(tile, session.token());
        markBiomeDirtyIfRequested(BiomeTileKeys.toBiome(tile), session.token());
    }

    private static CorrectionStore.Key regionKey(
        final String dimension, final int lod, final ChunkRegionSlice slice
    ) {
        final int chunksPerTile = 16 << lod;
        return new CorrectionStore.Key(
            dimension,
            lod,
            Math.floorDiv(slice.minChunkX(), chunksPerTile),
            Math.floorDiv(slice.minChunkZ(), chunksPerTile)
        );
    }

    private static TileKey correctionTile(
        final SessionGuard.Session session, final CorrectionStore.Key key
    ) {
        final DimensionId dimension = DimensionId.parse(key.dimension());
        final MapLayer layer = PredictionDimensions.layer(dimension);
        if (!session.active() || layer == null || !dimension.equals(session.dimension())) {
            return null;
        }
        return new TileKey(
            session.world(), dimension, layer.cacheId() + PredictedTileKeys.SUFFIX,
            key.lod(), key.tileX(), key.tileZ()
        );
    }

    /** Main thread, from the session tracker: forget every queued/in-flight predicted tile, and invalidate cached native contexts. */
    public void onSessionChanged(final SessionGuard.Session session) {
        synchronized (this) {
            reloadGeneration++;
            lowerCoverageGeneration++;
            dirty.clear();
            requestedTiles.clear();
            staleViewModeTiles.clear();
            staleRealCoverageTiles.clear();
            metadataTiles.clear();
            mipCache.clear();
            lowerCoverageQueue.clear();
            missingLowerCoverage.clear();
        }
        CubiomesContexts.bumpEpoch();
    }

    /**
     * Drops every queued/uploaded prediction and invalidates native contexts. Visible tiles are
     * requested again by the next map render, making this a clean diagnostic reload without
     * touching real-map tiles or persisted server corrections.
     */
    public void reloadAll() {
        final SessionGuard.Session session = sessionGuard.current();
        synchronized (this) {
            reloadGeneration++;
            lowerCoverageGeneration++;
            dirty.clear();
            staleViewModeTiles.clear();
            staleRealCoverageTiles.clear();
            metadataTiles.clear();
            mipCache.clear();
            lowerCoverageQueue.clear();
            missingLowerCoverage.clear();
            if (session.active()) {
                for (final TileKey key : inFlight) {
                    if (key.world().equals(session.world()) && key.dimension().equals(session.dimension())) {
                        dirty.put(key, session.token());
                    }
                }
            }
        }
        uploads.discardUploads(PredictedTileKeys::isPredicted);
        CubiomesContexts.bumpEpoch();
        pump();
    }

    /** Where the viewer currently is, for dirty-tile composition priority (mirrors {@code TileService#setViewpoint}). */
    public void setViewpoint(final int blockX, final int blockZ) {
        viewpointX = blockX;
        viewpointZ = blockZ;
    }

    /** Recompose selected-source tiles after the player's local-authority boundary moves. */
    public void refreshLiveCoverage() {
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active()) {
            return;
        }
        synchronized (this) {
            for (final TileKey key : requestedTiles) {
                if (key.world().equals(session.world())
                    && key.dimension().equals(session.dimension())) {
                    queueRealCoverageRefresh(key, session.token());
                }
            }
        }
        pump();
    }

    /**
     * Called once per frame by the fullscreen map with the exact range of predicted tiles it's
     * about to draw; prunes any queued request outside that rect (a 1-tile pad beyond it is kept,
     * so a tile that's about to be scrolled into view isn't discarded and immediately
     * re-requested) or at a different LOD/dimension - e.g. after a zoom change, stale requests
     * for the previous LOD are dropped rather than wastefully composed.
     */
    public void setViewport(
        final DimensionId dimension, final int lod, final int minTileX, final int maxTileX, final int minTileZ, final int maxTileZ
    ) {
        final ViewportRect rect = new ViewportRect(dimension, lod, minTileX, maxTileX, minTileZ, maxTileZ);
        final SessionGuard.Session session = sessionGuard.current();
        synchronized (this) {
            final boolean changed = !rect.equals(viewport);
            viewport = rect;
            dirty.keySet().removeIf(key -> !rect.containsPadded(key));
            metadataTiles.keySet().removeIf(key -> !rect.containsPadded(key));
            if (changed && session.active() && dimension.equals(session.dimension()) && lod > 0) {
                final MapLayer predictedLayer = PredictionDimensions.layer(dimension);
                if (predictedLayer == null) {
                    return;
                }
                final String layer = predictedLayer.cacheId();
                for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                    for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                        final TileKey key = new TileKey(
                            session.world(), dimension, layer + PredictedTileKeys.SUFFIX,
                            lod, tileX, tileZ
                        );
                        if (mipCache.lowerCoverageValidatedAt(key, viewMode) != PredictionMipCache.MISSING) {
                            metadataTiles.remove(key);
                            dirty.put(key, session.token());
                        }
                    }
                }
            }
            if (changed && session.active() && dimension.equals(session.dimension())) {
                for (final TileKey key : staleViewModeTiles) {
                    if (rect.contains(key)) {
                        metadataTiles.remove(key);
                        dirty.put(key, session.token());
                    }
                }
                for (final TileKey key : staleRealCoverageTiles) {
                    if (rect.contains(key)) {
                        metadataTiles.remove(key);
                        dirty.put(key, session.token());
                    }
                }
            }
        }
        pump();
    }

    /** Clears fullscreen ordering after the screen closes. */
    public void clearViewport() {
        synchronized (this) {
            viewport = null;
            metadataTiles.clear();
        }
        pump();
    }

    /**
     * Predicted biome at one visible fullscreen-map pixel, using the same LOD sample that produced
     * the rendered tile. Returns empty until that tile has composed, when prediction is hidden at
     * the pixel, or when the requested dimension is not the active predictable session.
     */
    public OptionalInt predictedBiomeAt(
        final DimensionId dimension,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        final PixelLookup lookup = visiblePixelLookup(dimension, lod, blockX, blockZ);
        if (lookup == null) {
            return OptionalInt.empty();
        }
        final TileMetadata metadata;
        synchronized (this) {
            metadata = metadataTiles.get(lookup.key());
        }
        if (metadata == null) {
            // GPU textures can outlive viewport metadata. Rehydrate this one tile lazily when the
            // cursor returns to it; requestTile is idempotent while a composition is queued/running.
            requestTile(lookup.key());
            return OptionalInt.empty();
        }
        return OptionalInt.of(Byte.toUnsignedInt(metadata.biomes()[lookup.pixel()]));
    }

    /** Predicted highest surface block at the same visible pixel used by the underlay. */
    public OptionalInt predictedSurfaceYAt(
        final DimensionId dimension,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        final PixelLookup lookup = visiblePixelLookup(dimension, lod, blockX, blockZ);
        if (lookup == null) {
            return OptionalInt.empty();
        }
        final TileMetadata metadata;
        synchronized (this) {
            metadata = metadataTiles.get(lookup.key());
        }
        if (metadata == null) {
            requestTile(lookup.key());
            return OptionalInt.empty();
        }
        final int surfaceY = metadata.surfaces()[lookup.pixel()];
        return surfaceY == BaselineGrid.NO_SURFACE ? OptionalInt.empty() : OptionalInt.of(surfaceY);
    }

    /**
     * For a predicted tile that's visible but has never been composed. The authoritative
     * availability gate (native library loaded, seed known, dimension supported) lives in {@link
     * #composeTile} rather than here - {@link cn.net.rms.confluxmap.mc.render.TileTextureManager}
     * only ever routes here for a key {@code FullscreenMapScreen} already decided prediction is
     * active for, and keeping this method's own check to just the session match makes the
     * dirty-queue/viewport-pruning behavior independently testable without a loaded native
     * library (see {@code PredictionTileServiceTest}).
     */
    public void requestTile(final TileKey key) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!key.world().equals(session.world()) || !key.dimension().equals(session.dimension())) {
            return;
        }
        synchronized (this) {
            requestedTiles.add(key);
            // The renderer retries a missing texture every frame. Keep that retry idempotent so
            // one slow native composition cannot continuously requeue itself.
            if (dirty.containsKey(key) || inFlight.contains(key)) {
                return;
            }
            dirty.put(key, session.token());
        }
        pump();
    }

    private void markBiomeDirtyIfRequested(final TileKey key, final long token) {
        synchronized (this) {
            if (!requestedTiles.contains(key)) {
                return;
            }
        }
        markDirty(key, token);
    }

    private void markDirty(final TileKey key, final long token) {
        synchronized (this) {
            dirty.put(key, token);
            if (inFlight.contains(key)) {
                return;
            }
        }
        pump();
    }

    private void pump() {
        while (true) {
            final TileKey next;
            final long token;
            final long generation;
            synchronized (this) {
                final int compositionLimit = viewport == null
                    ? maxConcurrentCompositions
                    : Math.min(maxConcurrentCompositions, VISIBLE_CONCURRENCY);
                if (inFlight.size() >= compositionLimit || dirty.isEmpty()) {
                    return;
                }
                next = nearestDirty();
                if (next == null) {
                    return;
                }
                token = dirty.remove(next);
                generation = reloadGeneration;
                inFlight.add(next);
            }
            executors.workers().execute(() -> composeAndFinish(next, token, generation));
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

    private void composeAndFinish(final TileKey key, final long token, final long generation) {
        Composition composition = null;
        try {
            composition = composeTile(key, token);
        } finally {
            synchronized (this) {
                if (composition != null && generation == reloadGeneration
                    && composition.mode() == viewMode) {
                    requestedTiles.add(key);
                    metadataTiles.put(key, composition.metadata());
                    mipCache.put(key, new PredictionMipCache.Tile(
                        composition.update().argbPixels(),
                        composition.metadata().biomes(),
                        composition.metadata().surfaces(),
                        composition.syncEvaluated(),
                        composition.syncSourceRevisions(),
                        composition.blockLight(),
                        composition.composedDaylight(),
                        composition.mode(),
                        composition.hasServerState(),
                        composition.freshnessValidatedAtMillis(),
                        composition.serverCoverageValidatedAtMillis()
                    ));
                    staleViewModeTiles.remove(key);
                    staleRealCoverageTiles.remove(key);
                    invalidateCachedAncestorsAndQueueVisible(key, token);
                    uploads.submitUpload(composition.update());
                }
                inFlight.remove(key);
            }
            pump();
        }
    }

    private Composition composeTile(final TileKey key, final long token) {
        return composeTile(key, token, viewMode);
    }

    private Composition composeTile(
        final TileKey key,
        final long token,
        final PredictionViewMode requestedMode
    ) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!sessionGuard.isCurrent(token) || !session.active()
            || !key.world().equals(session.world()) || !key.dimension().equals(session.dimension())
            || !state.predictable(key.dimension())) {
            return null;
        }
        final int nativeDim = PredictionDimensions.nativeDim(key.dimension());
        if (nativeDim == Integer.MIN_VALUE) {
            return null;
        }
        final MapLayer expectedLayer = PredictionDimensions.layer(key.dimension());
        try {
            final MapLayer requestedLayer = MapLayer.parse(BiomeTileKeys.realLayerId(
                PredictedTileKeys.realLayerId(key.layerId())
            ));
            if (!requestedLayer.equals(expectedLayer)) {
                return null;
            }
        } catch (final IllegalArgumentException e) {
            return null;
        }

        final boolean end = PredictionDimensions.isEnd(key.dimension());
        final int lod = key.lod();
        final int tileOriginX = key.originBlockX();
        final int tileOriginZ = key.originBlockZ();
        final PredictionViewMode compositionMode = requestedMode == null
            ? PredictionViewMode.EVERYWHERE
            : requestedMode;

        final PredictionMipCache.Tile lower = mipCache.lowerTile(key, compositionMode);
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        final CorrectionTile storedCorrections = store == null
            ? null
            : store.get(key.dimension(), lod, key.tileX(), key.tileZ());
        final CorrectionTile directCorrections = supportsCorrectionBaseline(
            storedCorrections, PredictorVersion.full()
        ) ? storedCorrections : null;
        final boolean directHasServerState = directCorrections != null
            && directCorrections.hasCommittedState();
        final long directValidatedAt = directCorrections == null
            ? 0L
            : directCorrections.reusableValidatedAtMillis();
        final long nowMillis = millisClock.getAsLong();
        final boolean lowerFresh = lower != null
            && lower.isVisuallyReusableAt(nowMillis, CORRECTION_REUSE_TTL_MS);
        if (lowerFresh && (!directHasServerState
            || directValidatedAt == 0L
            || lower.freshnessValidatedAtMillis() >= directValidatedAt)) {
            final Composition composition = new Composition(
                tileUpdate(key, lower.pixels(), lower.blockLight(), lower.composedDaylight()),
                new TileMetadata(lower.biomes(), lower.surfaces()),
                lower.syncEvaluated(),
                lower.syncSourceRevisions(),
                lower.blockLight(),
                lower.composedDaylight(),
                lower.mode(),
                lower.hasServerState(),
                lower.freshnessValidatedAtMillis(),
                lower.serverCoverageValidatedAtMillis()
            );
            uploads.maskPredictedPixels(
                realKey(key), composition.update().argbPixels(),
                lower.syncEvaluated(), lower.syncSourceRevisions(),
                hasKnownRevision(lower.syncSourceRevisions())
            );
            return composition;
        }

        final BaselineGrid grid;
        final DerivedGrid derived;
        final int baselineMapColorId;
        final FlatBaseline flat = state.flatBaseline(key.dimension());
        if (state.preset(key.dimension()) == WorldPreset.FLAT && flat != null) {
            // Superflat: every column is the same known surface - no seed, no native sampling.
            grid = flat.toBaselineGrid();
            derived = flat.toDerivedGrid();
            baselineMapColorId = flat.mapColorId();
        } else {
            final long seed = state.seed();
            final BaselineSampler sampler = new NativeBaselineSampler(
                state.mcVersion(), seed, nativeDim, state.cubiomesFlags(key.dimension())
            );
            grid = key.dimension().equals(DimensionId.NETHER)
                ? LodSampling.sampleNetherRoof(sampler, lod, tileOriginX, tileOriginZ)
                : LodSampling.sample(sampler, end, lod, tileOriginX, tileOriginZ);
            if (grid == null) {
                return null;
            }
            derived = BaselineDeriver.derive(grid);
            CanopyStylizer.apply(derived, grid, seed, lod, tileOriginX, tileOriginZ);
            // Terrain mode owns the roof material, while the separate biome composer below owns
            // biome identity. Treat the fixed Nether plane like a uniform bedrock superflat.
            baselineMapColorId = key.dimension().equals(DimensionId.NETHER)
                ? PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID
                : Proto.MAP_COLOR_NONE;
        }

        final int[] pixels = BiomeTileKeys.isBiome(key)
            ? PredictedBiomeComposer.compose(
                derived, grid, directCorrections, compositionMode, lod, derived, grid
            )
            : PredictedTileComposer.compose(
                derived, grid, state.palette(), directCorrections, compositionMode, lod,
                baselineMapColorId, derived, grid, baselineMapColorId,
                !key.dimension().equals(DimensionId.NETHER),
                key.dimension().equals(DimensionId.NETHER)
                    ? LightTint.multiplier(0, 0, true)
                    : 0xFFFFFFFF
            );
        final byte[] syncEvaluated = directCorrections == null
            ? new byte[PatchCodec.MASK_BYTES] : directCorrections.copyEvaluated();
        final long[] syncSourceRevisions = directCorrections == null
            ? unknownPixelRevisions() : directCorrections.copyPixelSourceRevisions();
        final byte[] blockLight = directCorrections == null
            ? new byte[PatchCodec.PIXELS] : directCorrections.copyBlockLight();
        uploads.maskPredictedPixels(
            realKey(key), pixels, syncEvaluated, syncSourceRevisions,
            directCorrections != null && directCorrections.hasSourceRevisionMetadata()
        );
        final float composedDaylight = applyLayerLighting(key, pixels, blockLight);
        return new Composition(
            tileUpdate(key, pixels, blockLight, composedDaylight),
            new TileMetadata(
                biomeIds(grid, directCorrections, grid),
                surfaceYs(derived, directCorrections, derived)
            ),
            syncEvaluated,
            syncSourceRevisions,
            blockLight,
            composedDaylight,
            compositionMode,
            directHasServerState,
            directValidatedAt,
            directHasServerState ? directValidatedAt : 0L
        );
    }

    private static TileKey realKey(final TileKey predictedKey) {
        return new TileKey(
            predictedKey.world(), predictedKey.dimension(),
            PredictedTileKeys.realLayerId(predictedKey.layerId()),
            predictedKey.lod(), predictedKey.tileX(), predictedKey.tileZ()
        );
    }

    /**
     * Whether the parent can be reconstructed entirely from newer, committed lower-LOD results.
     * This is also the network-sync admission seam: a true result means the server would only
     * reproduce data the client already has.
     */
    public synchronized boolean hasFreshLowerCoverage(
        final DimensionId dimension,
        final int lod,
        final int tileX,
        final int tileZ,
        final long nowMillis
    ) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active() || lod <= 0 || lod > TileMath.MAX_LOD
            || !dimension.equals(session.dimension())) {
            return false;
        }
        final MapLayer predictedLayer = PredictionDimensions.layer(dimension);
        if (predictedLayer == null) {
            return false;
        }
        final String layer = predictedLayer.cacheId();
        final TileKey parent = new TileKey(
            session.world(), dimension, layer + PredictedTileKeys.SUFFIX, lod, tileX, tileZ
        );
        final PredictionMipCache.Tile exact = mipCache.exact(parent, viewMode);
        final long exactValidatedAt = exact == null ? PredictionMipCache.MISSING
            : exact.serverCoverageValidatedAtMillis();
        final long validatedAt = exactValidatedAt != PredictionMipCache.MISSING
            ? exactValidatedAt
            : mipCache.lowerCoverageValidatedAt(parent, viewMode);
        return validatedAt > 0L
            && nowMillis >= validatedAt
            && nowMillis - validatedAt <= CORRECTION_REUSE_TTL_MS;
    }

    /**
     * Resolves complete lower-LOD correction coverage without blocking the render thread on disk
     * or native prediction work. The reducer prefers the nearest fresh child LOD and recursively
     * falls back to finer persisted tiles only where needed.
     */
    public LowerCoverageState prepareFreshLowerCoverage(
        final DimensionId dimension,
        final int lod,
        final int tileX,
        final int tileZ,
        final long nowMillis
    ) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active() || lod <= 0 || lod > TileMath.MAX_LOD
            || !dimension.equals(session.dimension())) {
            return LowerCoverageState.MISSING_OR_STALE;
        }
        final MapLayer predictedLayer = PredictionDimensions.layer(dimension);
        if (predictedLayer == null) {
            return LowerCoverageState.MISSING_OR_STALE;
        }
        final String layer = predictedLayer.cacheId();
        final TileKey parent = new TileKey(
            session.world(), dimension, layer + PredictedTileKeys.SUFFIX, lod, tileX, tileZ
        );
        synchronized (this) {
            if (freshLowerCoverageAt(parent, nowMillis)) {
                return LowerCoverageState.READY;
            }
            if (missingLowerCoverage.contains(parent)) {
                return LowerCoverageState.MISSING_OR_STALE;
            }
            if (!lowerCoverageInFlight.contains(parent)) {
                lowerCoverageQueue.putIfAbsent(parent, session.token());
            }
        }
        pumpLowerCoverage();
        return LowerCoverageState.PENDING;
    }

    /** Caller must hold the monitor. */
    private boolean freshLowerCoverageAt(final TileKey parent, final long nowMillis) {
        final PredictionMipCache.Tile exact = mipCache.exact(parent, viewMode);
        final long validatedAt = exact != null && exact.serverCoverageValidatedAtMillis() > 0L
            ? exact.serverCoverageValidatedAtMillis()
            : mipCache.lowerCoverageValidatedAt(parent, viewMode);
        return validatedAt > 0L
            && nowMillis >= validatedAt
            && nowMillis - validatedAt <= CORRECTION_REUSE_TTL_MS;
    }

    private void pumpLowerCoverage() {
        final TileKey key;
        final long token;
        final long generation;
        synchronized (this) {
            if (executors.workers().isShutdown()) {
                lowerCoverageQueue.clear();
                lowerCoverageInFlight.clear();
                return;
            }
            if (!lowerCoverageInFlight.isEmpty() || lowerCoverageQueue.isEmpty()) {
                return;
            }
            final Map.Entry<TileKey, Long> next = lowerCoverageQueue.entrySet().iterator().next();
            key = next.getKey();
            token = next.getValue();
            lowerCoverageQueue.remove(key);
            lowerCoverageInFlight.add(key);
            generation = lowerCoverageGeneration;
        }
        try {
            executors.workers().execute(() -> reduceLowerCoverageAndFinish(key, token, generation));
        } catch (final java.util.concurrent.RejectedExecutionException e) {
            synchronized (this) {
                lowerCoverageInFlight.remove(key);
                lowerCoverageQueue.clear();
            }
        }
    }

    private void reduceLowerCoverageAndFinish(
        final TileKey parent,
        final long token,
        final long generation
    ) {
        PredictionMipCache.Tile reduced = null;
        try {
            final long nowMillis = millisClock.getAsLong();
            if (hasFreshPersistedChildren(parent, token, nowMillis)) {
                reduced = reduceFreshChildren(parent, token, nowMillis, viewMode);
            }
        } finally {
            synchronized (this) {
                if (generation == lowerCoverageGeneration && sessionGuard.isCurrent(token)) {
                    if (reduced == null) {
                        missingLowerCoverage.add(parent);
                    } else {
                        mipCache.put(parent, reduced);
                        metadataTiles.put(parent, new TileMetadata(reduced.biomes(), reduced.surfaces()));
                        uploads.submitUpload(TileUpdate.fullTile(parent, reduced.pixels()));
                    }
                }
                lowerCoverageInFlight.remove(parent);
            }
            pumpLowerCoverage();
        }
    }

    /** Checks the complete persisted coverage before spending native work composing any child. */
    private boolean hasFreshPersistedChildren(
        final TileKey parent,
        final long token,
        final long nowMillis
    ) {
        if (parent.lod() <= 0 || !sessionGuard.isCurrent(token)) {
            return false;
        }
        final CorrectionStore store = correctionStore;
        if (store == null) {
            return false;
        }
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final TileKey childKey = new TileKey(
                    parent.world(), parent.dimension(), parent.layerId(), parent.lod() - 1,
                    parent.tileX() * 2 + childX, parent.tileZ() * 2 + childZ
                );
                final CorrectionTile correction = store.get(
                    childKey.dimension(), childKey.lod(), childKey.tileX(), childKey.tileZ()
                );
                if (!correction.hasCommittedState()
                    || !correction.isFreshAt(nowMillis, CORRECTION_REUSE_TTL_MS)) {
                    if (!hasFreshPersistedChildren(childKey, token, nowMillis)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    private PredictionMipCache.Tile reduceFreshChildren(
        final TileKey parent,
        final long token,
        final long nowMillis,
        final PredictionViewMode mode
    ) {
        if (parent.lod() <= 0 || !sessionGuard.isCurrent(token)) {
            return null;
        }
        final PredictionMipCache.Tile[] children = new PredictionMipCache.Tile[4];
        for (int childZ = 0; childZ < 2; childZ++) {
            for (int childX = 0; childX < 2; childX++) {
                final TileKey childKey = new TileKey(
                    parent.world(), parent.dimension(), parent.layerId(), parent.lod() - 1,
                    parent.tileX() * 2 + childX, parent.tileZ() * 2 + childZ
                );
                final CorrectionStore store = correctionStore;
                final CorrectionTile correction = store == null ? null : store.get(
                    childKey.dimension(), childKey.lod(), childKey.tileX(), childKey.tileZ()
                );
                final PredictionMipCache.Tile child;
                if (correction != null && correction.hasCommittedState()
                    && correction.isFreshAt(nowMillis, CORRECTION_REUSE_TTL_MS)) {
                    final Composition composition = composeTile(childKey, token);
                    child = composition == null ? null : mipTile(composition);
                } else {
                    child = reduceFreshChildren(childKey, token, nowMillis, mode);
                }
                if (child == null) {
                    return null;
                }
                children[childZ * 2 + childX] = child;
            }
        }
        return PredictionMipCache.aggregate(children, mode);
    }

    private static PredictionMipCache.Tile mipTile(final Composition composition) {
        return new PredictionMipCache.Tile(
            composition.update().argbPixels(),
            composition.metadata().biomes(),
            composition.metadata().surfaces(),
            composition.syncEvaluated(),
            composition.syncSourceRevisions(),
            composition.blockLight(),
            composition.composedDaylight(),
            composition.mode(),
            composition.hasServerState(),
            composition.freshnessValidatedAtMillis(),
            composition.serverCoverageValidatedAtMillis()
        );
    }

    /** Caller must hold the monitor. */
    private void invalidateCachedTileAndAncestors(final TileKey key) {
        mipCache.remove(key);
        TileKey ancestor = key;
        while (ancestor.lod() < TileMath.MAX_LOD) {
            ancestor = new TileKey(
                ancestor.world(), ancestor.dimension(), ancestor.layerId(), ancestor.lod() + 1,
                Math.floorDiv(ancestor.tileX(), 2), Math.floorDiv(ancestor.tileZ(), 2)
            );
            mipCache.remove(ancestor);
            metadataTiles.remove(ancestor);
        }
    }

    /** Caller must hold the monitor. */
    private void invalidateCachedAncestorsAndQueueVisible(final TileKey key, final long token) {
        TileKey ancestor = key;
        while (ancestor.lod() < TileMath.MAX_LOD) {
            ancestor = new TileKey(
                ancestor.world(), ancestor.dimension(), ancestor.layerId(), ancestor.lod() + 1,
                Math.floorDiv(ancestor.tileX(), 2), Math.floorDiv(ancestor.tileZ(), 2)
            );
            mipCache.remove(ancestor);
            metadataTiles.remove(ancestor);
            if (viewport != null && viewport.contains(ancestor)) {
                dirty.put(ancestor, token);
            }
        }
    }

    static boolean supportsCorrectionBaseline(
        final CorrectionTile corrections,
        final String currentBaselineProfile
    ) {
        return corrections == null
            || corrections.patchMode() == Proto.PATCH_MODE_ABSOLUTE
            || corrections.baselineProfile().equals(currentBaselineProfile);
    }

    private static byte[] biomeIds(
        final BaselineGrid grid,
        final CorrectionTile corrections,
        final BaselineGrid correctionGrid
    ) {
        final byte[] biomes = new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                biomes[z * BaselineGrid.PIXELS + x] = (byte) grid.biomeId[BaselineGrid.index(x, z)];
            }
        }
        if (corrections != null) {
            final PatchCodec.Patch correctionPatch = corrections.copyPatch();
            if (corrections.patchMode() == Proto.PATCH_MODE_RESIDUAL) {
                final byte[] evaluated = correctionPatch.evaluated();
                for (int pixel = 0; pixel < biomes.length; pixel++) {
                    if ((evaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0) {
                        biomes[pixel] = (byte) correctionGrid.biomeId[
                            BaselineGrid.index(pixel & 255, pixel >>> 8)
                        ];
                    }
                }
            }
            for (final PatchCodec.Sample sample : correctionPatch.samples()) {
                if (SurfaceKind.byOrdinal(sample.kind()) == SurfaceKind.UNKNOWN) {
                    final int pixel = sample.pixelIndex();
                    biomes[pixel] = (byte) grid.biomeId[
                        BaselineGrid.index(pixel & 255, pixel >>> 8)
                    ];
                } else {
                    biomes[sample.pixelIndex()] = (byte) sample.biomeId();
                }
            }
        }
        return biomes;
    }

    private static int[] surfaceYs(
        final DerivedGrid derived,
        final CorrectionTile corrections,
        final DerivedGrid correctionDerived
    ) {
        final int[] surfaces = new int[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                surfaces[z * BaselineGrid.PIXELS + x] = derived.surfaceY[BaselineGrid.index(x, z)];
            }
        }
        if (corrections != null) {
            final PatchCodec.Patch correctionPatch = corrections.copyPatch();
            if (corrections.patchMode() == Proto.PATCH_MODE_RESIDUAL) {
                final byte[] evaluated = correctionPatch.evaluated();
                for (int pixel = 0; pixel < surfaces.length; pixel++) {
                    if ((evaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0) {
                        surfaces[pixel] = correctionDerived.surfaceY[
                            BaselineGrid.index(pixel & 255, pixel >>> 8)
                        ];
                    }
                }
            }
            for (final PatchCodec.Sample sample : correctionPatch.samples()) {
                if (SurfaceKind.byOrdinal(sample.kind()) == SurfaceKind.UNKNOWN) {
                    final int pixel = sample.pixelIndex();
                    surfaces[pixel] = derived.surfaceY[
                        BaselineGrid.index(pixel & 255, pixel >>> 8)
                    ];
                } else {
                    surfaces[sample.pixelIndex()] = sample.surfaceY();
                }
            }
        }
        return surfaces;
    }

    /** Test-support only: a snapshot of currently-queued (not yet in-flight) predicted tile keys. */
    public synchronized Set<TileKey> pendingKeysForTest() {
        return new HashSet<>(dirty.keySet());
    }

    /** Test-support only: whether every queued/in-flight tile has drained. */
    public synchronized boolean isIdleForTest() {
        return dirty.isEmpty() && inFlight.isEmpty()
            && lowerCoverageQueue.isEmpty() && lowerCoverageInFlight.isEmpty();
    }

    private PixelLookup visiblePixelLookup(
        final DimensionId dimension,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active() || !dimension.equals(session.dimension()) || !state.predictable(dimension)) {
            return null;
        }
        final int tileX = TileMath.blockToTile(blockX, lod);
        final int tileZ = TileMath.blockToTile(blockZ, lod);
        final int pixelX = TileMath.blockToPixelInTile(blockX, lod);
        final int pixelZ = TileMath.blockToPixelInTile(blockZ, lod);
        final int pixel = pixelZ * TileMath.TILE_SIZE + pixelX;
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        final CorrectionTile corrections = store == null ? null : store.get(dimension, lod, tileX, tileZ);
        if (!viewMode.showsPredictedPixels(corrections, pixel, lod)) {
            return null;
        }
        final MapLayer predictedLayer = PredictionDimensions.layer(dimension);
        if (predictedLayer == null) {
            return null;
        }
        final String layer = predictedLayer.cacheId();
        return new PixelLookup(new TileKey(
            session.world(), dimension, layer + PredictedTileKeys.SUFFIX, lod, tileX, tileZ
        ), pixel);
    }

    private record Composition(
        TileUpdate update,
        TileMetadata metadata,
        byte[] syncEvaluated,
        long[] syncSourceRevisions,
        byte[] blockLight,
        float composedDaylight,
        PredictionViewMode mode,
        boolean hasServerState,
        long freshnessValidatedAtMillis,
        long serverCoverageValidatedAtMillis
    ) {
    }

    private record TileMetadata(byte[] biomes, int[] surfaces) {
    }

    private record PixelLookup(TileKey key, int pixel) {
    }

    private record ViewportRect(DimensionId dimension, int lod, int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
        boolean contains(final TileKey key) {
            if (!dimension.equals(key.dimension()) || lod != key.lod()) {
                return false;
            }
            return key.tileX() >= minTileX && key.tileX() <= maxTileX
                && key.tileZ() >= minTileZ && key.tileZ() <= maxTileZ;
        }

        boolean containsPadded(final TileKey key) {
            if (!dimension.equals(key.dimension()) || lod != key.lod()) {
                return false;
            }
            return key.tileX() >= minTileX - 1 && key.tileX() <= maxTileX + 1
                && key.tileZ() >= minTileZ - 1 && key.tileZ() <= maxTileZ + 1;
        }
    }
}
