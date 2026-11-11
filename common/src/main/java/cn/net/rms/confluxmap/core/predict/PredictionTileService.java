package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.PredictorVersion;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/**
 * Predicted-underlay twin of {@code core.tile.TileService}: a synchronized dirty-tile queue
 * bounded by an in-flight cap of one composition per worker (tiles inside a visible viewport
 * are still picked outward from the viewport center, and user-waited menu-target tiles
 * always first), session-token guarded - same discipline,
 * entirely separate data. Predictions never enter {@code ColumnStore}/{@code
 * RegionDiskCache}; composition here samples cubiomes directly (via {@link NativeBaselineSampler})
 * and shares {@code TileService}'s upload queue through {@link TileService#submitUpload}. Native
 * availability, a known seed, and a supported dimension all gate every request - see {@link
 * PredictionState#predictable} - so a missing seed or a native-load failure degrades to a silent
 * no-op rather than an error anywhere in this class.
 */
public final class PredictionTileService {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/PredictionTiles");

    public enum LowerCoverageState {
        READY,
        PENDING,
        MISSING_OR_STALE
    }

    /** What the e2e gametest asserts about the last closed viewport load (debug timing only). */
    public record ViewportLoadResult(boolean complete, int lod, int tiles, long wallNanos) {
    }

    /** Re-entry freshness window for corrections; expiry never schedules background polling. */
    public static final long CORRECTION_REUSE_TTL_MS = 30L * 60L * 1_000L;
    /**
     * Construction default only - the client applies the MapQuality tier at startup, so this
     * survives for tests. On the planar layout 64 tiles cost ~21 MB (a corrected LOD4 tile is
     * the ~0.83 MB outlier); enough for one ordinary fullscreen viewport plus its next mip.
     */
    private static final int REUSABLE_TILE_LIMIT = 64;
    private final SessionGuard sessionGuard;
    private final PredictionState state;
    private final MapExecutors executors;
    private final TileService uploads;
    private final LongSupplier millisClock;
    private final int maxConcurrentCompositions;
    /** Debug-gated viewport cold-load aggregation; consulted only while the timing flag is on. */
    private final ViewportLoadTiming viewportTiming = new ViewportLoadTiming();
    /**
     * Debug-gated enqueue timestamps feeding the scheduling-delay stat; only ever populated
     * while the timing flag is on, and pruned alongside the dirty queue.
     */
    private final Map<TileKey, Long> queuedAtNanos = new HashMap<>();
    /** Debug-gated first-visible marker state: the marker logs once per open window. */
    private boolean firstVisibleLogged;
    /** Debug-gated outcome of the last closed viewport load; the e2e gametest asserts on it. */
    private volatile ViewportLoadResult lastViewportLoadForTest;
    /** Finished tiles' six-stage wall vectors (debug timing only); benchmark harness seam. */
    private final Map<TileKey, long[]> tileOverworldStageNanosForTest = new HashMap<>();

    /** Wave members' sampler factory signature, mirroring {@link NativeBaselineSampler}'s constructor. */
    interface WaveSamplerFactory {
        BaselineSampler create(int mcVersion, long seed, int nativeDim, int flags);
    }

    /**
     * Test-only override of the factory {@link #admitWave} builds members' sampler through; null
     * in production. Package-private so PredictionTileServiceTest can pin wave formation against
     * a recording fake - the batch seam is otherwise unobservable from the service's public surface.
     */
    volatile WaveSamplerFactory waveSamplerFactoryForTest;

    /**
     * Test-only observation of staged wave admission shape: the member count of every staged wave
     * admitted this session, in admission order, CPU waves included. A lone staged candidate keeps
     * the retained single-shot path and records {@code 1}. Recorded only while a wave sampler
     * factory is installed (tests do; production never does, so the list stays empty there).
     * Cleared on session change.
     */
    final List<Integer> admittedWaveSizesForTest = new ArrayList<>();

    /** Appends to {@link #admittedWaveSizesForTest} only when the test wave sampler is installed. */
    private void noteWaveAdmissionForTest(final int size) {
        if (waveSamplerFactoryForTest != null) {
            admittedWaveSizesForTest.add(size);
        }
    }

    /** Dispatch tiers, lowest first: user-waited, visible viewport, padded ring, background. */
    private static final int TIER_URGENT = 0;
    private static final int TIER_VISIBLE = 1;
    private static final int TIER_RING = 2;
    private static final int TIER_BACKGROUND = 3;

    private volatile CorrectionStore correctionStore;
    private volatile DaylightModel daylightModel;
    private volatile PredictionViewMode viewMode = PredictionViewMode.EVERYWHERE;
    private volatile BooleanSupplier serverPolicyAllowsPrediction = () -> true;
    private volatile MapColorStyle mapColorStyle = MapColorStyle.CONFLUX;
    private volatile ConfluxConfig.VoidColumnColor voidColumnColor = ConfluxConfig.VoidColumnColor.OFF;

    /** Guarded by {@code this}: tiles waiting to be composed, with the session token that requested them. */
    private final Map<TileKey, Long> dirty = new HashMap<>();
    /** Guarded by {@code this}: tiles currently being composed on a worker. */
    private final Set<TileKey> inFlight = new HashSet<>();
    /**
     * Guarded by {@code this}: per-wave capacity reservations, keyed by wave identity. A GPU wave
     * holds its whole slot group until its LAST member finishes, so staggered completions cannot
     * trickle singleton admissions into the wave's capacity. The array is {remaining, held}:
     * members still expected to reach {@code finishAndReport}, and members already finished whose
     * slot stays reserved until the record releases.
     */
    private final Map<WaveComposer, int[]> waveReservations = new IdentityHashMap<>();
    /**
     * Guarded by {@code this}: queued tiles a map action is actively waiting on (a right-click
     * menu target). Dispatched before the viewport center-out order; the flag is consumed by
     * the composition that finishes the tile.
     */
    private final Set<TileKey> urgent = new HashSet<>();
    /** Prediction variants requested or uploaded at least once this session. */
    private final Set<TileKey> requestedTiles = new HashSet<>();
    /** Tracked textures that were last composed under an older view mode. */
    private final Set<TileKey> staleViewModeTiles = new HashSet<>();
    /**
     * Tiles this session composed but could not planar-cache (a synced flat baseline declared
     * biome ids outside a byte). They can never enter the mip cache, so request dedup that
     * consults the cache must consult this set instead or it re-enqueues them every frame.
     */
    private final Set<TileKey> nonCacheableTiles = new HashSet<>();
    /** Tracked textures whose local real-map coverage changed while they were off-screen. */
    private final Set<TileKey> staleRealCoverageTiles = new HashSet<>();
    /** Guarded by {@code this}: output-pixel metadata retained for fullscreen cursor/actions. */
    private final Map<TileKey, TileMetadata> metadataTiles = new HashMap<>();
    /** Recent CPU results used to build a coarser tile without resampling. */
    private final PredictionMipCache mipCache = new PredictionMipCache(REUSABLE_TILE_LIMIT);
    /**
     * Directly sampled baseline planes, keyed independently of corrections, styling, and view
     * mode. Sits behind the mip cache: a mip hit serves finished planes, a baseline hit serves
     * the sampled inputs for a fresh shade instead of paying the sampler again.
     */
    private final PredictionBaselineCache baselineCache = new PredictionBaselineCache(REUSABLE_TILE_LIMIT);
    /**
     * Test-only override of the effective serving-engine snapshot; null in production. Package-
     * private so the baseline-identity tests can flip the backend the way a device loss would.
     */
    volatile IntSupplier servingEngineForTest;
    private final SyncedMaterialPalette syncedMaterials = new SyncedMaterialPalette();
    /** Parent tiles waiting for one bounded disk-backed lower-LOD reduction. */
    private final LinkedHashMap<TileKey, Long> lowerCoverageQueue = new LinkedHashMap<>();
    private final Set<TileKey> lowerCoverageInFlight = new HashSet<>();
    private final Set<TileKey> missingLowerCoverage = new HashSet<>();
    private long lowerCoverageGeneration;
    /** Guarded by {@code this}: invalidates compositions started before a manual/session reload. */
    private volatile long reloadGeneration;
    /**
     * Guarded by {@code this}: a reload dropped every cache, so the next viewport change must
     * schedule its tiles regardless of reduction-cache state; consumed by the first sweep.
     */
    private boolean reloadAwaitingViewport;

    /** Latest fullscreen viewport; visible predictions are scheduled outward from its center. */
    private ViewportRect viewport;

    private volatile int viewpointX;
    private volatile int viewpointZ;
    private volatile int netherBiomeY = PredictionDimensions.NETHER_ROOF_Y;
    /** Guarded by {@code this}: rejects current-layer compositions started at an older pivot Y. */
    private long netherBiomeGeneration;

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
        uploads.bindPredictedUploadReloader(this::requestTile);
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
            || !realKey.dimension().equals(session.dimension())) {
            return;
        }
        boolean queued = false;
        synchronized (this) {
            if (isLowerNetherLayer(realKey.dimension(), layer)) {
                queueRealCoverageRefresh(
                    PredictedTileKeys.toPredicted(BiomeTileKeys.toBiome(realKey)),
                    session.token()
                );
                queued = true;
            } else if (layer.equals(PredictionDimensions.layer(session.dimension()))) {
                queueRealCoverageRefresh(PredictedTileKeys.toPredicted(realKey), session.token());
                queueRealCoverageRefresh(
                    PredictedTileKeys.toPredicted(BiomeTileKeys.toBiome(realKey)), session.token()
                );
                queued = true;
            }
        }
        if (queued) {
            pump();
        }
    }

    /** Caller must hold the monitor. */
    private void queueRealCoverageRefresh(final TileKey key, final long token) {
        invalidateCachedTileAndAncestors(key);
        if (requestedTiles.contains(key)) {
            staleRealCoverageTiles.add(key);
            if (viewport != null && viewport.contains(key)) {
                dirtyPut(key, token);
            }
        }
    }

    public void bindCorrectionStore(final CorrectionStore store) {
        this.correctionStore = store;
    }

    public void bindDaylightModel(final DaylightModel model) {
        this.daylightModel = model;
    }

    public void setMapColorStyle(final MapColorStyle style) {
        mapColorStyle = style == null ? MapColorStyle.CONFLUX : style;
    }

    public void setVoidColumnColor(final ConfluxConfig.VoidColumnColor color) {
        voidColumnColor = color == null ? ConfluxConfig.VoidColumnColor.OFF : color;
    }

    public SyncedMaterialPalette syncedMaterials() {
        return syncedMaterials;
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
                        dirtyPut(key, session.token());
                    }
                }
            }
        }
        pump();
    }

    /**
     * Server feature gate for the prediction underlay. When the companion forbids prediction,
     * every composition and cache read resolves to {@link PredictionViewMode#VISITED_ONLY}, which
     * emits no predicted pixels: the underlay vanishes while the locally captured REAL layer and
     * the upload queue keep working. The supplier stays {@code () -> true} on the common side;
     * only the client wires the companion policy in.
     */
    public void setServerPolicyAllowsPrediction(final BooleanSupplier allowed) {
        serverPolicyAllowsPrediction = allowed == null ? () -> true : allowed;
    }

    private PredictionViewMode effectiveViewMode() {
        return serverPolicyAllowsPrediction.getAsBoolean()
            ? viewMode
            : PredictionViewMode.VISITED_ONLY;
    }

    public void setMapQuality(final ConfluxConfig.MapQuality quality) {
        if (quality != null) {
            setCacheLimit(quality.predictionTileCacheLimit());
        }
    }

    public void setCacheLimit(final int limit) {
        mipCache.setLimit(limit);
        baselineCache.setLimit(limit);
    }

    int cacheLimitForTest() {
        return mipCache.limit();
    }

    public PredictionViewMode viewMode() {
        return viewMode;
    }

    private AppliedLighting applyLayerLighting(
        final TileKey key,
        final int[] pixels,
        final byte[] blockLight,
        final MapColorStyle style
    ) {
        if (BiomeTileKeys.isBiome(key)) {
            return AppliedLighting.NONE;
        }
        final String realLayer = BiomeTileKeys.baseLayerId(key);
        final MapLayer.Type layer = MapLayer.parse(realLayer).type();
        final DaylightModel model = daylightModel;
        final DaylightModel.State lighting = model == null
            ? new DaylightModel.State(1f, 0f)
            : model.state();
        final float gamma = lighting.gamma();
        if (layer == MapLayer.Type.NETHER_CEILING) {
            // Captured Xaero roof tiles keep their zero-light ambient bake untouched
            // (TileService's composeXaeroColumn applies no light replacement to
            // NETHER_CEILING), so the prediction must match instead of being re-lit.
            if (style != MapColorStyle.XAERO) {
                for (int pixel = 0; pixel < pixels.length; pixel++) {
                    pixels[pixel] = LightTint.applyBlockLightOverAmbient(
                        pixels[pixel], blockLight[pixel] & 0xFF, true, gamma
                    );
                }
            }
            return AppliedLighting.NONE;
        }
        if (layer == MapLayer.Type.END_SURFACE) {
            // The End plane composes with the zero-light ambient baked in (see composeTile);
            // the per-column block-light plane replaces it here, matching TileService's
            // raw END_SURFACE composition. The Xaero style composes without the bake (its
            // terrain shading adds light, which a ratio replace cannot invert) and takes the
            // curve applied over the shaded pixel, exactly like captured Xaero End tiles.
            for (int pixel = 0; pixel < pixels.length; pixel++) {
                pixels[pixel] = style == MapColorStyle.XAERO
                    ? LightTint.applyBlockLightTint(
                        pixels[pixel], blockLight[pixel] & 0xFF, false, gamma
                    )
                    : LightTint.applyBlockLightOverAmbient(
                        pixels[pixel], blockLight[pixel] & 0xFF, false, gamma
                    );
            }
            return AppliedLighting.NONE;
        }
        if (layer != MapLayer.Type.SURFACE) {
            return AppliedLighting.NONE;
        }
        if (model == null) {
            return AppliedLighting.NONE;
        }
        final float factor = lighting.daylight();
        for (int pixel = 0; pixel < pixels.length; pixel++) {
            pixels[pixel] = style == MapColorStyle.XAERO
                ? Argb.scale(
                    pixels[pixel],
                    ShadingPipeline.applyGamma(
                        XaeroMapStyle.daylightScale(factor, blockLight[pixel] & 0xFF), gamma
                    )
                )
                : ShadingPipeline.applyDaylight(
                    pixels[pixel], factor, blockLight[pixel] & 0xFF, gamma
                );
        }
        return new AppliedLighting(factor, gamma);
    }

    private static TileUpdate tileUpdate(
        final TileKey key,
        final int[] pixels,
        final byte[] blockLight,
        final float composedDaylight,
        final float composedGamma,
        final MapColorStyle style
    ) {
        return Float.isNaN(composedDaylight)
            ? TileUpdate.fullTile(key, pixels)
            : TileUpdate.fullTile(
                key, pixels, new TileUpdate.Relight(
                    composedDaylight, composedGamma, blockLight, style
                )
            );
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
            final Composition composition = composeTile(
                key, session.token(), mode, netherBiomeY
            );
            if (composition == null) {
                return null;
            }
            return composition.update().argbPixels();
        }, executors.workers());
    }

    /** Applies a server patch and queues the affected predicted tile for recomposition. */
    public boolean applyCorrection(
        final CorrectionStore.Key key, final long revision, final byte[] presence,
        final PatchCodec.Patch patch
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
            Proto.PATCH_MODE_RESIDUAL,
            MapSyncCompatibility.STABLE_PREDICTOR,
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
        return applyRegionCorrection(
            dimension, lod, slice, patch, patchMode, baselineProfile, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public boolean applyRegionCorrection(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final ChunkPatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.applyRegionSlice(
            dimension, lod, slice, patch, patchMode, baselineProfile, validatedAtMillis,
            correctionProfile
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
        return validateRegionCorrection(
            dimension, lod, slice, revision, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public boolean validateRegionCorrection(
        final String dimension,
        final int lod,
        final ChunkRegionSlice slice,
        final long revision,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.validateRegionSlice(
            dimension, lod, slice, revision, validatedAtMillis, correctionProfile
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
        }
        return true;
    }

    public boolean applyCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final long validatedAtMillis
    ) {
        return applyCorrection(
            key, revision, presence, patch,
            Proto.PATCH_MODE_RESIDUAL,
            MapSyncCompatibility.STABLE_PREDICTOR,
            validatedAtMillis
        );
    }

    public boolean applyCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis
    ) {
        return applyCorrection(
            key, revision, presence, patch, patchMode, baselineProfile, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public boolean applyCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final PatchCodec.Patch patch,
        final int patchMode,
        final String baselineProfile,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        if (store == null || !store.apply(
            key, revision, presence, patch, patchMode, baselineProfile, validatedAtMillis,
            correctionProfile
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
        return validateCorrection(
            key, revision, presence, validatedAtMillis,
            CorrectionProfile.SOURCE_LIGHT_V2
        );
    }

    public boolean validateCorrection(
        final CorrectionStore.Key key,
        final long revision,
        final byte[] presence,
        final long validatedAtMillis,
        final CorrectionProfile correctionProfile
    ) {
        final CorrectionStore store = correctionStore;
        if (store == null || !store.validate(
            key, revision, presence, validatedAtMillis, correctionProfile
        )) {
            return false;
        }
        markCorrectionDirty(key);
        return true;
    }

    /** Records uncommitted scan progress without replacing or recomposing the drawable snapshot. */
    public boolean applyPartialCorrection(
        final CorrectionStore.Key key,
        final byte[] presence,
        final PatchCodec.Patch patch
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
            // The biome twin reads the same patch, and an exact cache hit would otherwise keep
            // serving the pixels of the correction this call just replaced.
            mipCache.removeCoverage(BiomeTileKeys.toBiome(tile));
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
                // Same reason as applyPartialCorrection: the biome twin carries the coverage too.
                mipCache.removeCoverage(BiomeTileKeys.toBiome(tile));
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
            invalidateCachedTileAndAncestors(tile);
            // The biome twin composes from the same patch, so its cached pixels are stale for the
            // same reason. Dropping them here is what stops an exact cache hit from resurrecting a
            // correction the store has already replaced or cleared.
            invalidateCachedTileAndAncestors(BiomeTileKeys.toBiome(tile));
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
            // The ending session's armed reload resweep must not leak into the new world's
            // first viewport: that sweep belongs to the session that requested it.
            reloadAwaitingViewport = false;
            dirty.clear();
            queuedAtNanos.clear();
            urgent.clear();
            requestedTiles.clear();
            staleViewModeTiles.clear();
            nonCacheableTiles.clear();
            staleRealCoverageTiles.clear();
            metadataTiles.clear();
            mipCache.clear();
            baselineCache.clear();
            lowerCoverageQueue.clear();
            missingLowerCoverage.clear();
            // A stale record would pin capacity for waves whose members may never matter again;
            // their in-flight members still release their own slots on the way out.
            waveReservations.clear();
            admittedWaveSizesForTest.clear();
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
            // Reload drops every reduction cache; resident-texture tiles then get neither a
            // cache entry nor a missing-texture retry - only the next viewport sweep
            // reschedules them.
            reloadAwaitingViewport = true;
            dirty.clear();
            queuedAtNanos.clear();
            urgent.clear();
            staleViewModeTiles.clear();
            nonCacheableTiles.clear();
            staleRealCoverageTiles.clear();
            metadataTiles.clear();
            mipCache.clear();
            baselineCache.clear();
            lowerCoverageQueue.clear();
            missingLowerCoverage.clear();
            // Same ledger reset as onSessionChanged: reloadAll purges the queue, so held wave
            // reservations must not outlive the work they were reserved for.
            waveReservations.clear();
            admittedWaveSizesForTest.clear();
            if (session.active()) {
                for (final TileKey key : inFlight) {
                    if (key.world().equals(session.world()) && key.dimension().equals(session.dimension())) {
                        dirtyPut(key, session.token());
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

    /** Updates the debounced Y used by the live Nether current-layer biome plane. */
    public void setNetherBiomeY(final int blockY) {
        final SessionGuard.Session session = sessionGuard.current();
        synchronized (this) {
            if (netherBiomeY == blockY) {
                return;
            }
            netherBiomeY = blockY;
            netherBiomeGeneration++;
            for (final TileKey key : requestedTiles) {
                if (isCurrentNetherBiomeKey(key)) {
                    invalidateCachedTileAndAncestors(key);
                    dirtyPut(key, session.token());
                }
            }
        }
        pump();
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
        ViewportLoadTiming.Summary closedViewportLoad = null;
        synchronized (this) {
            // Baseline entries resize with the viewport (bounded by the configured limit) so a
            // small GUI scale or a 4K screen can hold the visible set without LRU thrashing.
            final int spanX = Math.max(0, maxTileX - minTileX + 1);
            final int spanZ = Math.max(0, maxTileZ - minTileZ + 1);
            baselineCache.adaptToViewport(spanX * spanZ);
            final boolean changed = !rect.equals(viewport);
            viewport = rect;
            dirty.keySet().removeIf(key -> {
                if (rect.containsPadded(key)) {
                    return false;
                }
                urgent.remove(key);
                return true;
            });
            metadataTiles.keySet().removeIf(key -> !rect.containsPadded(key));
            if (!queuedAtNanos.isEmpty()) {
                queuedAtNanos.keySet().removeIf(key ->
                    !dirty.containsKey(key) && !inFlight.contains(key));
            }
            if (debugTimingEnabled() && viewportTiming.active()) {
                // A viewport move can leave the open window with nothing pending (its tiles
                // pruned, none in flight) and no later finish to run the close check.
                closedViewportLoad = viewportTiming.closeIfIdle(
                    viewportTimingIdle(viewportTiming.windowRect()), System.nanoTime()
                );
            }
            if (changed && session.active() && dimension.equals(session.dimension()) && lod > 0) {
                final MapLayer predictedLayer = PredictionDimensions.layer(dimension);
                if (predictedLayer == null) {
                    return;
                }
                // A reload armed a resweep: its reduction caches are gone, so the cache
                // gate below would leave resident-texture tiles unscheduled forever.
                final boolean fullResweep = reloadAwaitingViewport;
                reloadAwaitingViewport = false;
                final String layer = predictedLayer.cacheId();
                for (int tileZ = minTileZ; tileZ <= maxTileZ; tileZ++) {
                    for (int tileX = minTileX; tileX <= maxTileX; tileX++) {
                        final TileKey key = new TileKey(
                            session.world(), dimension, layer + PredictedTileKeys.SUFFIX,
                            lod, tileX, tileZ
                        );
                        if (fullResweep
                            || mipCache.lowerCoverageValidatedAt(key, effectiveViewMode()) != PredictionMipCache.MISSING) {
                            dirtyPut(key, session.token());
                        }
                    }
                }
            }
            if (changed && session.active() && dimension.equals(session.dimension())) {
                for (final TileKey key : staleViewModeTiles) {
                    if (rect.contains(key)) {
                        dirtyPut(key, session.token());
                    }
                }
                for (final TileKey key : staleRealCoverageTiles) {
                    if (rect.contains(key)) {
                        dirtyPut(key, session.token());
                    }
                }
            }
        }
        logViewportLoad(closedViewportLoad);
        pump();
    }

    /**
     * Schedules low-priority preloading for the 1-tile outer ring around the current visible viewport.
     * Safe to call after visible tiles are bound/requested.
     */
    public void preloadViewportPaddedRing() {
        preloadViewportPaddedRing(null);
    }

    /**
     * Schedules low-priority preloading for the 1-tile outer ring around the current visible viewport,
     * targeting the specified layer namespace (or the dimension's default predicted layer when null).
     */
    public void preloadViewportPaddedRing(final String targetLayerId) {
        final SessionGuard.Session session = sessionGuard.current();
        final ViewportRect rect;
        synchronized (this) {
            rect = viewport;
            if (rect == null || !session.active() || !rect.dimension().equals(session.dimension())
                || !state.predictable(rect.dimension())) {
                return;
            }
            final String layer;
            if (targetLayerId != null && !targetLayerId.isEmpty()) {
                layer = targetLayerId;
            } else {
                final MapLayer predictedLayer = PredictionDimensions.layer(rect.dimension());
                if (predictedLayer == null) {
                    return;
                }
                layer = predictedLayer.cacheId();
            }
            final String predictedLayerId = layer.endsWith(PredictedTileKeys.SUFFIX)
                ? layer
                : layer + PredictedTileKeys.SUFFIX;
            for (int tileZ = rect.minTileZ() - 1; tileZ <= rect.maxTileZ() + 1; tileZ++) {
                for (int tileX = rect.minTileX() - 1; tileX <= rect.maxTileX() + 1; tileX++) {
                    if (rect.contains(tileX, tileZ)) {
                        continue;
                    }
                    final TileKey key = new TileKey(
                        session.world(), rect.dimension(), predictedLayerId,
                        rect.lod(), tileX, tileZ
                    );
                    // Called every frame: a ring tile that already composed at this LOD and view
                    // mode has nothing left to prefetch. Without this check the whole ring would be
                    // recomposed (and re-uploaded) once per frame, which is exactly the kind of
                    // background load that starves the visible tiles.
                    if (dirty.containsKey(key) || inFlight.contains(key)
                        || mipCache.exact(key, effectiveViewMode()) != null
                        || nonCacheableTiles.contains(key)) {
                        continue;
                    }
                    dirtyPut(key, session.token());
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
        return predictedBiomeAt(
            dimension, PredictionDimensions.layer(dimension), lod, blockX, blockZ
        );
    }

    /**
     * Predicted biome on the requested fullscreen layer, including Nether height planes. The
     * answer prefers the layer's own metadata slot and falls back to the biome-identity tile's
     * slot, so a cursor readout works in the biome view even though only its cheap biome tiles
     * (never the terrain twins) composed there.
     */
    public OptionalInt predictedBiomeAt(
        final DimensionId dimension,
        final MapLayer layer,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        final PixelLookup lookup = visiblePixelLookup(
            dimension, layer, lod, blockX, blockZ
        );
        if (lookup == null) {
            return OptionalInt.empty();
        }
        final TileMetadata metadata;
        synchronized (this) {
            final TileMetadata own = metadataTiles.get(lookup.key());
            metadata = own != null
                ? own
                : metadataTiles.get(BiomeTileKeys.toBiome(lookup.key()));
        }
        if (metadata == null) {
            // GPU textures can outlive viewport metadata. Rehydrate the biome tile lazily when
            // the cursor returns to it; its composition is the cheap terrain-free one, and
            // requestTile is idempotent while a composition is queued/running.
            requestTile(BiomeTileKeys.toBiome(lookup.requestKey()));
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
        return surfaceLookupAt(dimension, lod, blockX, blockZ, false).surfaceY();
    }

    /**
     * Predicted ground at one visible fullscreen-map pixel, distinguishing a composed void
     * column ({@code known} with no surface Y) from a tile that has not composed yet. A
     * missing tile is queued ahead of the viewport order, because a map action is waiting on
     * it.
     */
    public ColumnStore.SurfaceLookup predictedSurfaceAt(
        final DimensionId dimension,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        return surfaceLookupAt(dimension, lod, blockX, blockZ, true);
    }

    private ColumnStore.SurfaceLookup surfaceLookupAt(
        final DimensionId dimension,
        final int lod,
        final int blockX,
        final int blockZ,
        final boolean urgentRequest
    ) {
        final PixelLookup lookup = visiblePixelLookup(dimension, lod, blockX, blockZ);
        if (lookup == null) {
            return ColumnStore.SurfaceLookup.UNKNOWN;
        }
        final TileMetadata metadata;
        synchronized (this) {
            metadata = metadataTiles.get(lookup.key());
        }
        if (metadata == null) {
            if (urgentRequest) {
                requestTileUrgently(lookup.key());
            } else {
                requestTile(lookup.key());
            }
            return ColumnStore.SurfaceLookup.UNKNOWN;
        }
        final int surfaceY = metadata.surfaceYAt(lookup.pixel());
        return surfaceY == BaselineGrid.NO_SURFACE
            ? new ColumnStore.SurfaceLookup(true, OptionalInt.empty())
            : new ColumnStore.SurfaceLookup(true, OptionalInt.of(surfaceY));
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
            dirtyPut(key, session.token());
        }
        pump();
    }

    /**
     * Queues one tile a map action is actively waiting on - a right-click menu target whose
     * surface height gates teleport - ahead of the viewport and background order. Idempotent
     * while queued or in flight, exactly like {@link #requestTile}; the urgent flag is consumed
     * by the composition that finishes the tile.
     */
    public void requestTileUrgently(final TileKey key) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!key.world().equals(session.world()) || !key.dimension().equals(session.dimension())) {
            return;
        }
        synchronized (this) {
            requestedTiles.add(key);
            urgent.add(key);
            if (dirty.containsKey(key) || inFlight.contains(key)) {
                return;
            }
            dirtyPut(key, session.token());
        }
        pump();
    }

    /**
     * Whether the composition last uploaded for {@code key} still matches the state it was
     * composed under. A predicted tile's pixels are transparent exactly where real data won at
     * compose time; once real coverage (or the view mode) changes under it, that mask overstates
     * prediction until a recompose lands - and only the current viewport's LOD recomposes. A
     * cross-LOD consumer drawing a stale tile would paint fabricated terrain over newer real
     * tiles, so {@code TileTextureManager}'s fallback probes consult this before binding.
     */
    public boolean compositionCurrent(final TileKey key) {
        synchronized (this) {
            return !staleRealCoverageTiles.contains(key) && !staleViewModeTiles.contains(key);
        }
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
            dirtyPut(key, token);
            if (inFlight.contains(key)) {
                return;
            }
        }
        pump();
    }

    /**
     * Caller must hold the monitor. Every enqueue into the dirty queue goes through here so
     * the debug scheduling-delay stat can stamp the enqueue time in one place; without the
     * timing flag the map stays empty and dispatches report no scheduling delay.
     */
    private void dirtyPut(final TileKey key, final long token) {
        dirty.put(key, token);
        if (debugTimingEnabled()) {
            queuedAtNanos.put(key, System.nanoTime());
        }
    }

    private void pump() {
        final boolean timing = debugTimingEnabled();
        while (true) {
            final Runnable dispatch;
            synchronized (this) {
                // The ledger holds back only slots whose member already finished; the wave's
                // unfinished members still count through inFlight. Summing the whole reservation
                // here would double-count them and pin slots no wave owns.
                final int capacity = maxConcurrentCompositions - inFlight.size() - reservedWaveSlots();
                if (capacity <= 0 || dirty.isEmpty()) {
                    return;
                }
                dispatch = admit(capacity, timing);
                if (dispatch == null) {
                    return;
                }
            }
            // Submission only: a caller thread never waits on workers, wave-mates included.
            dispatch.run();
        }
    }

    /**
     * Caller must hold the monitor and pass the free composition slots. One admission step,
     * always starting from the globally best queued tile so dispatch priority is unchanged: a
     * whole wave when that tile is staged-eligible and same-group tiles fit the room, otherwise
     * one legacy tile. Wave admission never splits a wave - whatever fits the room now IS the
     * wave, the rest waits for the next pump as a future wave.
     */
    private Runnable admit(final int capacity, final boolean timing) {
        final TileKey best = nearestDirty();
        if (best == null) {
            return null;
        }
        if (stagedWaveCandidate(best)) {
            final int room = Math.min(capacity, WaveComposer.WAVE_CAP);
            final int lod = best.lod();
            final String layerId = best.layerId();
            final List<TileKey> group = new ArrayList<>(room);
            group.add(best);
            while (group.size() < room) {
                // Keys leave dirty only at admission below; without the exclusion nearestDirty re-picks the seed.
                final TileKey member = nearestDirty(
                    key -> !group.contains(key) && stagedWaveCandidate(key)
                        && key.lod() == lod && key.layerId().equals(layerId)
                );
                if (member == null) {
                    break;
                }
                group.add(member);
            }
            if (group.size() > 1) {
                return admitWave(group, timing);
            }
            // A lone staged candidate is a degenerate wave: keep the retained single-shot path.
            noteWaveAdmissionForTest(1);
        }
        return admitSingle(best, timing);
    }

    /** Caller must hold the monitor; {@code key} is still queued in {@code dirty}. */
    private Runnable admitSingle(final TileKey key, final boolean timing) {
        final long token = dirty.remove(key);
        final long generation = reloadGeneration;
        final long netherGeneration = netherBiomeGeneration;
        final int netherY = netherBiomeY;
        inFlight.add(key);
        if (timing) {
            noteTimingDispatch(key);
        }
        return () -> submitSingle(key, token, generation, netherGeneration, netherY);
    }

    /** Caller must hold the monitor; every key is still queued in {@code dirty}. */
    private Runnable admitWave(final List<TileKey> group, final boolean timing) {
        final int size = group.size();
        final long generation = reloadGeneration;
        final long netherGeneration = netherBiomeGeneration;
        final int netherY = netherBiomeY;
        final long token = sessionGuard.current().token();
        final long[] memberTokens = new long[size];
        final int[] originX = new int[size];
        final int[] originZ = new int[size];
        for (int i = 0; i < size; i++) {
            final TileKey key = group.get(i);
            memberTokens[i] = dirty.remove(key);
            originX[i] = key.originBlockX();
            originZ[i] = key.originBlockZ();
            inFlight.add(key);
            if (timing) {
                noteTimingDispatch(key);
            }
        }
        final TileKey any = group.get(0);
        final WaveSamplerFactory waveSamplerFactory = waveSamplerFactoryForTest;
        final BaselineSampler sampler = waveSamplerFactory != null
            ? waveSamplerFactory.create(
                state.mcVersion(), state.seed(), PredictionDimensions.nativeDim(any.dimension()),
                state.cubiomesFlags(any.dimension())
            )
            : new NativeBaselineSampler(
                state.mcVersion(), state.seed(), PredictionDimensions.nativeDim(any.dimension()),
                state.cubiomesFlags(any.dimension())
            );
        final WaveComposer.BatchDispatch batch;
        if (waveSamplerFactory != null) {
            batch = sampler instanceof WaveComposer.BatchDispatch dispatch ? dispatch : null;
        } else {
            // CPU backend or an unavailable/refusal-latched engine admits a null seam: a live
            // one would serialize refusal repairs onto the leader thread.
            batch = GpuBackend.batchDispatchAvailable()
                ? (WaveComposer.BatchDispatch) sampler
                : null;
        }
        final WaveComposer wave = new WaveComposer(
            size,
            () -> !isCompositionCurrent(generation, token),
            sampler,
            state.seed(),
            state.mcVersion(),
            timing,
            any.lod(),
            originX,
            originZ,
            batch
        );
        final List<TileKey> keys = List.copyOf(group);
        noteWaveAdmissionForTest(size);
        if (batch != null) {
            // GPU wave: hold the whole group's slots against mid-wave singleton admissions until
            // the last member finishes. CPU waves keep today's per-member release behavior.
            waveReservations.put(wave, new int[] {size, 0});
        }
        return () -> submitWave(wave, keys, memberTokens, generation, netherGeneration, netherY);
    }

    private void submitSingle(
        final TileKey key, final long token, final long generation, final long netherGeneration, final int netherY
    ) {
        try {
            executors.workers().execute(
                () -> composeAndFinish(key, token, generation, netherGeneration, netherY)
            );
        } catch (final RejectedExecutionException shuttingDown) {
            synchronized (this) {
                inFlight.remove(key);
            }
        }
    }

    private void submitWave(
        final WaveComposer wave,
        final List<TileKey> keys,
        final long[] memberTokens,
        final long generation,
        final long netherGeneration,
        final int netherY
    ) {
        int submitted = 0;
        try {
            for (int i = 0; i < keys.size(); i++) {
                final int index = i;
                final TileKey key = keys.get(i);
                final long token = memberTokens[index];
                executors.workers().execute(
                    () -> composeWaveMemberAndFinish(
                        wave, index, key, token, generation, netherGeneration, netherY
                    )
                );
                submitted++;
            }
        } catch (final RejectedExecutionException shuttingDown) {
            // Free the members already queued on the wave's barriers, then drop the tiles that
            // never got a worker; submitted members release their own slots on the way out.
            wave.dissolve();
            synchronized (this) {
                for (int i = submitted; i < keys.size(); i++) {
                    inFlight.remove(keys.get(i));
                }
                final int[] reservation = waveReservations.get(wave);
                if (reservation != null) {
                    // The never-submitted members held their slots through inFlight, freed by the
                    // removals above; the record now owes exactly the submissions the pool
                    // accepted, which is none on a shut-down pool - drop the record outright.
                    if (submitted == 0) {
                        waveReservations.remove(wave);
                    } else {
                        reservation[0] = submitted;
                    }
                }
            }
        }
    }

    /**
     * Caller must hold the monitor: one wave member finished, so its composition slot moves from
     * {@code inFlight} into the wave's reservation. The slot only returns to the free pool when
     * the LAST member finishes - a staggered finish must not admit a singleton into the wave's
     * capacity. A missing record (CPU wave, or a session change already dropped the ledger) has
     * nothing to hold.
     */
    private void releaseWaveMemberSlot(final WaveComposer wave) {
        final int[] reservation = waveReservations.get(wave);
        if (reservation == null) {
            return;
        }
        reservation[0]--;
        reservation[1]++;
        if (reservation[0] == 0) {
            waveReservations.remove(wave);
        }
    }

    /**
     * Whether a dirty tile belongs to a staged overworld wave: the six-stage skeleton only
     * serves seeded, non-superflat Overworld terrain tiles of one layer and LOD. Everything
     * else (biome view, Nether, End, superflat, unseeded) keeps the legacy single-shot path;
     * the per-member precheck re-validates all of this at compose time.
     */
    private boolean stagedWaveCandidate(final TileKey key) {
        if (!key.dimension().equals(DimensionId.OVERWORLD) || BiomeTileKeys.isBiome(key)) {
            return false;
        }
        if (isSuperflat(key.dimension()) || !state.predictable(key.dimension())) {
            return false;
        }
        final MapLayer requestedLayer;
        try {
            requestedLayer = MapLayer.parse(BiomeTileKeys.baseLayerId(key));
        } catch (final IllegalArgumentException e) {
            return false;
        }
        return requestedLayer.equals(PredictionDimensions.layer(key.dimension()));
    }

    /** Whether a composition admitted at {@code generation}/{@code token} may still sample and upload. */
    private boolean isCompositionCurrent(final long generation, final long token) {
        synchronized (this) {
            if (generation != reloadGeneration) {
                return false;
            }
        }
        return sessionGuard.isCurrent(token);
    }

    /**
     * Tier of one queued tile; caller must hold the monitor. Only the visible viewport earns
     * worker slots ahead of everything else - the ring and background tiers are prefetch.
     */
    private int dispatchTier(final TileKey key) {
        if (urgent.contains(key)) {
            return TIER_URGENT;
        }
        final ViewportRect activeViewport = viewport;
        if (activeViewport == null) {
            return TIER_BACKGROUND;
        }
        if (activeViewport.contains(key)) {
            return TIER_VISIBLE;
        }
        return activeViewport.containsPadded(key) ? TIER_RING : TIER_BACKGROUND;
    }

    /**
     * Caller must hold the monitor. Dispatch tiers stay strict - 0 = user-waited (menu
     * target), 1 = visible viewport, 2 = padded ring around visible viewport,
     * 3 = background - and each tier keeps its own order: menu targets row-major,
     * viewport and ring tiles outward from the viewport center, background tiles
     * viewpoint-nearest first. Row-major breaks equal-distance ties so the pick never
     * depends on {@code dirty}'s hash iteration order.
     */
    private TileKey nearestDirty() {
        return nearestDirty(null);
    }

    /** As {@link #nearestDirty()}, restricted to keys the predicate accepts (null accepts all). */
    private TileKey nearestDirty(final Predicate<TileKey> eligible) {
        final int vx = viewpointX;
        final int vz = viewpointZ;
        final ViewportRect activeViewport = viewport;
        TileKey best = null;
        int bestTier = Integer.MAX_VALUE;
        long bestDist = Long.MAX_VALUE;
        for (final TileKey key : dirty.keySet()) {
            if (inFlight.contains(key) || (eligible != null && !eligible.test(key))) {
                continue;
            }
            final int tier = dispatchTier(key);
            if (tier > bestTier) {
                continue;
            }
            final long dist = tierOrderDistance(key, tier, activeViewport, vx, vz);
            if (tier < bestTier || dist < bestDist
                || (dist == bestDist && rowMajorBefore(key, best))) {
                best = key;
                bestTier = tier;
                bestDist = dist;
            }
        }
        return best;
    }

    /** Ordering key inside one dispatch tier; menu targets keep their plain row-major order. */
    private static long tierOrderDistance(
        final TileKey key,
        final int tier,
        final ViewportRect viewport,
        final int vx,
        final int vz
    ) {
        if (tier == 1 || tier == 2) {
            return viewport.centerDistanceSq(key);
        }
        return tier == 3 ? distanceToViewpoint(key, vx, vz) : 0L;
    }

    private static long distanceToViewpoint(final TileKey key, final int vx, final int vz) {
        final long half = 128L << key.lod();
        final long dx = key.originBlockX() + half - vx;
        final long dz = key.originBlockZ() + half - vz;
        return dx * dx + dz * dz;
    }

    private static boolean rowMajorBefore(final TileKey candidate, final TileKey current) {
        return candidate.tileZ() < current.tileZ()
            || (candidate.tileZ() == current.tileZ() && candidate.tileX() < current.tileX());
    }

    private void composeAndFinish(
        final TileKey key,
        final long token,
        final long generation,
        final long netherGeneration,
        final int netherY
    ) {
        // The mode this composition runs under: composeTile reads the same field, so comparing
        // it against the live field inside the monitor is the same staleness check comparing the
        // composition's mode used to be.
        final PredictionViewMode requestedMode = viewMode;
        final boolean timing = debugTimingEnabled();
        final long composeStartNanos = timing ? System.nanoTime() : 0L;
        Composition composition = null;
        try {
            composition = composeTile(key, token, requestedMode, netherY);
        } finally {
            finishAndReport(key, null, composition, token, generation, netherGeneration, requestedMode,
                composeStartNanos, timing);
        }
    }

    private void composeWaveMemberAndFinish(
        final WaveComposer wave,
        final int index,
        final TileKey key,
        final long token,
        final long generation,
        final long netherGeneration,
        final int netherY
    ) {
        final PredictionViewMode requestedMode = viewMode;
        final boolean timing = debugTimingEnabled();
        final long composeStartNanos = timing ? System.nanoTime() : 0L;
        Composition composition = null;
        try {
            try {
                composition = composeStagedMember(wave, index, key, token, requestedMode, netherY);
            } catch (final Throwable fault) {
                // Anything failing around the member's stages must free its wave-mates.
                LOGGER.warn("Predicted wave member {} failed around its stages", index, fault);
                wave.dissolve();
            }
        } finally {
            finishAndReport(key, wave, composition, token, generation, netherGeneration, requestedMode,
                composeStartNanos, timing);
        }
    }

    /** Shared completion path of both admission shapes: commit-or-drop, release the slot, pump, log. */
    private void finishAndReport(
        final TileKey key,
        final WaveComposer wave,
        final Composition composition,
        final long token,
        final long generation,
        final long netherGeneration,
        final PredictionViewMode requestedMode,
        final long composeStartNanos,
        final boolean timing
    ) {
        final long composeNanos = composeStartNanos != 0L ? System.nanoTime() - composeStartNanos : 0L;
        boolean submittedUpload = false;
        ViewportLoadTiming.Summary closedViewportLoad = null;
        synchronized (this) {
            if (composition != null && generation == reloadGeneration
                && (!isCurrentNetherBiomeKey(key)
                    || netherGeneration == netherBiomeGeneration)
                && requestedMode == viewMode) {
                requestedTiles.add(key);
                metadataTiles.put(metadataKey(key), composition.metadata());
                // A null tile means "not planar-cacheable" (see planarTile): it still
                // composed and uploads, it just never enters the mip cache. Recording the
                // key lets per-frame request dedup skip it without a cache to consult.
                if (composition.tile() != null) {
                    mipCache.put(key, composition.tile());
                } else {
                    nonCacheableTiles.add(key);
                }
                staleViewModeTiles.remove(key);
                staleRealCoverageTiles.remove(key);
                invalidateCachedAncestorsAndQueueVisible(key, token);
                uploads.submitUpload(composition.update());
                submittedUpload = true;
            }
            inFlight.remove(key);
            urgent.remove(key);
            if (wave != null) {
                releaseWaveMemberSlot(wave);
            }
            if (timing) {
                // Arguments evaluate before the call: the idle check reads the window the
                // finish is about to accumulate into (and possibly close).
                closedViewportLoad = viewportTiming.finished(
                    key, composeNanos,
                    composition == null ? null : composition.stageNanos(),
                    composition == null ? null : composition.overworldStageNanos(),
                    composition == null ? 0L : composition.sampledPoints(),
                    submittedUpload,
                    viewportTimingIdle(viewportTiming.windowRect()),
                    System.nanoTime()
                );
                if (composition != null && composition.overworldStageNanos() != null) {
                    tileOverworldStageNanosForTest.put(key, composition.overworldStageNanos());
                }
            }
        }
        pump();
        if (timing) {
            if (closedViewportLoad != null) {
                logViewportLoad(closedViewportLoad);
            }
            if (composition != null) {
                logTileTiming(
                    key, composition.sampledPoints(), composition.stageGpuDelta(),
                    composition.stageNanos(), composeNanos
                );
            }
        }
    }

    /**
     * Prediction tile-timing diagnostics switch: {@code -Dconfluxmap.debugPredictionTiming=true}
     * or {@code CONFLUXMAP_DEBUG_TIMING=1} (Gradle launches inherit env vars, not -D flags).
     * Public so the Fabric render side can gate its upload-completion reports without paying
     * for them when timing is off.
     */
    public static boolean debugTimingEnabled() {
        return Boolean.getBoolean("confluxmap.debugPredictionTiming")
            || "1".equals(System.getenv("CONFLUXMAP_DEBUG_TIMING"));
    }

    /** Caller must hold the monitor and the debug-timing gate. */
    private void noteTimingDispatch(final TileKey key) {
        final ViewportRect rect = viewport;
        if (rect == null || dispatchTier(key) != TIER_VISIBLE) {
            return;
        }
        final Long queuedAt = queuedAtNanos.remove(key);
        final long now = System.nanoTime();
        final long schedNanos = queuedAt == null ? 0L : now - queuedAt;
        final boolean windowWasActive = viewportTiming.active();
        final ViewportLoadTiming.Summary superseded =
            viewportTiming.dispatched(timingRect(rect), key, schedNanos, now);
        if (superseded != null || !windowWasActive) {
            firstVisibleLogged = false;
        }
        logViewportLoad(superseded);
    }

    /** Caller must hold the monitor and the debug-timing gate. */
    private boolean viewportTimingIdle(final ViewportLoadTiming.Rect rect) {
        if (rect == null) {
            return true;
        }
        for (final TileKey key : dirty.keySet()) {
            if (rect.contains(key)) {
                return false;
            }
        }
        for (final TileKey key : inFlight) {
            if (rect.contains(key)) {
                return false;
            }
        }
        return true;
    }

    private static ViewportLoadTiming.Rect timingRect(final ViewportRect rect) {
        return new ViewportLoadTiming.Rect(
            rect.dimension(), rect.lod(),
            rect.minTileX(), rect.maxTileX(), rect.minTileZ(), rect.maxTileZ()
        );
    }

    /**
     * Debug log label for a packed stage-stats delta: {@code gpu} when every tracked stage call
     * was GPU-served, {@code cpu} when none was, {@code mixed} when the GPU served only some
     * (e.g. a dispatch refused mid-tile), and {@code none} when no tracked stage ran at all
     * (cache hit, superflat, non-Overworld, stale library) or the context was rebuilt mid-tile.
     */
    static String backendLabel(final long stageGpuDelta) {
        if (stageGpuDelta <= 0L) {
            return "none";
        }
        final long gpu = stageGpuDelta >>> 32;
        final long total = stageGpuDelta & 0xffffffffL;
        if (gpu == 0L) {
            return "cpu";
        }
        return gpu == total ? "gpu" : "mixed";
    }

    /** One finished composition's generation cost: identity, sampled workload, backend, stage split, wall time. */
    private void logTileTiming(
        final TileKey key,
        final long points,
        final long stageGpuDelta,
        final long[] stageNanos,
        final long composeNanos
    ) {
        LOGGER.info(String.format(Locale.ROOT,
            "[predict-timing] tile dim=%s layer=%s lod=%d tile=(%d,%d) points=%d backend=%s"
                + " gen=%.1fms%s ms=%.1f",
            key.dimension().path(), key.layerId(), key.lod(),
            key.tileX(), key.tileZ(), points, backendLabel(stageGpuDelta),
            SampleStage.total(stageNanos) / 1.0e6,
            formatStageBreakdown(stageNanos),
            composeNanos / 1.0e6
        ));
    }

    /**
     * One viewport load's end-to-end aggregate: dispatch-to-display for the whole visible
     * rect, with the generation stage split, composition total, scheduling delay, upload-queue
     * wait, texture upload time, first-visible latency and wall clock. {@code complete=false}
     * marks a window the viewport left behind; {@code attempts} appears when compositions
     * finished inside the rect without their upload landing (stale or superseded).
     */
    private void logViewportLoad(final ViewportLoadTiming.Summary summary) {
        if (summary == null) {
            return;
        }
        logBackendSnapshot();
        lastViewportLoadForTest = new ViewportLoadResult(
            summary.complete(), summary.lod(), summary.tiles(), summary.wallNanos()
        );
        final long[] stageNanos = summary.stageNanos();
        LOGGER.info(String.format(Locale.ROOT,
            "[predict-e2e] %s dim=%s lod=%d rect=[%d..%d,%d..%d] tiles=%d%s pts=%d"
                + " gen=%.1fms%s stages6=%s compose=%.1fms sched=%.1fms uploadWait=%.1fms upload=%.1fms"
                + " first=%.1fms wall=%.1fms",
            summary.complete() ? "done" : "abandoned",
            summary.dimension().path(), summary.lod(),
            summary.minTileX(), summary.maxTileX(), summary.minTileZ(), summary.maxTileZ(),
            summary.tiles(),
            summary.attempts() == summary.tiles()
                ? "" : String.format(Locale.ROOT, " attempts=%d", summary.attempts()),
            summary.points(),
            SampleStage.total(stageNanos) / 1.0e6,
            formatStageBreakdown(stageNanos),
            formatOverworldStageBreakdown(summary.overworldStageNanos()),
            summary.composeNanos() / 1.0e6,
            summary.schedNanos() / 1.0e6,
            summary.uploadWaitNanos() / 1.0e6,
            summary.uploadNanos() / 1.0e6,
            Math.max(0L, summary.firstUploadWallNanos()) / 1.0e6,
            summary.wallNanos() / 1.0e6
        ));
    }

    /**
     * Nonzero sampler stages of a stage array as {@code (h=1.2 b=3.4)}, fixed enum order;
     * empty when nothing was sampled natively (cache hits, superflat, non-counting paths).
     */
    private static String formatStageBreakdown(final long[] stageNanos) {
        if (SampleStage.total(stageNanos) <= 0L) {
            return "";
        }
        final StringBuilder stages = new StringBuilder("(");
        for (final SampleStage stage : SampleStage.values()) {
            final long nanos = stageNanos[stage.ordinal()];
            if (nanos > 0L) {
                if (stages.length() > 1) {
                    stages.append(' ');
                }
                stages.append(stage.name().toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(String.format(Locale.ROOT, "%.1f", nanos / 1.0e6));
            }
        }
        return stages.append(')').toString();
    }

    /**
     * Nonzero {@link LodSampling.OverworldStage} stages of a six-slot wall array as
     * {@code (h_lattice=1.2 cells=3.4)}, fixed enum order; empty when the tile never ran the
     * staged skeleton (cache hits, superflat, non-Overworld).
     */
    static String formatOverworldStageBreakdown(final long[] stageNanos) {
        long total = 0L;
        for (final long nanos : stageNanos) {
            total += nanos;
        }
        if (total <= 0L) {
            return "";
        }
        final StringBuilder stages = new StringBuilder("(");
        for (final LodSampling.OverworldStage stage : LodSampling.OverworldStage.values()) {
            final long nanos = stageNanos[stage.ordinal()];
            if (nanos > 0L) {
                if (stages.length() > 1) {
                    stages.append(' ');
                }
                stages.append(stage.name().toLowerCase(Locale.ROOT))
                    .append('=')
                    .append(String.format(Locale.ROOT, "%.1f", nanos / 1.0e6));
            }
        }
        return stages.append(')').toString();
    }

    /**
     * Render thread, from the texture manager: a predicted tile's texture upload completed, so
     * it is now displayed. Completes the tile's entry in the open window's upload set and may
     * close the window; the first completion inside a window logs the first-visible marker.
     */
    public void onPredictedUploaded(final TileKey key, final long uploadNanos) {
        if (!debugTimingEnabled()) {
            return;
        }
        final ViewportLoadTiming.Summary closedWindow;
        final long firstWallNanos;
        final TileKey firstTile;
        synchronized (this) {
            closedWindow = viewportTiming.uploaded(
                key, uploadNanos,
                viewportTimingIdle(viewportTiming.windowRect()), System.nanoTime()
            );
            firstWallNanos = viewportTiming.firstUploadWallNanos();
            firstTile = viewportTiming.firstUploadedTile();
        }
        if (closedWindow != null) {
            logViewportLoad(closedWindow);
            return;
        }
        if (!firstVisibleLogged && firstWallNanos >= 0L && firstTile != null) {
            firstVisibleLogged = true;
            LOGGER.info(String.format(Locale.ROOT,
                "[predict-e2e] first dim=%s lod=%d tile=(%d,%d) wall=%.1fms",
                firstTile.dimension().path(), firstTile.lod(),
                firstTile.tileX(), firstTile.tileZ(), firstWallNanos / 1.0e6
            ));
        }
    }

    /**
     * Engine-side GPU state beside a viewport aggregate: the engine's own mode, the probed OpenCL
     * device and its successful dispatch count (surface-biome + height dispatches). Across two
     * loads, a growing count is the GPU engaging; a flat count under {@code mode=auto} is the
     * AUTO fallback quietly serving the CPU.
     */
    private void logBackendSnapshot() {
        final GpuBackend.GpuStats stats = GpuBackend.stats();
        if (stats == null) {
            LOGGER.info(
                "[predict-backend] mode=unknown device='' dispatches=-1"
                    + " (native library missing or predates the stats export)"
            );
            return;
        }
        LOGGER.info(String.format(Locale.ROOT,
            "[predict-backend] mode=%s device='%s' dispatches=%d",
            GpuBackend.engineModeLabel(stats.engineMode()), stats.device(),
            stats.dispatches() + stats.heightDispatches()
        ));
    }

    /** A whole-dimension flat surface declares the same top material in every quadrant. */
    private static String[] uniformFlatMaterials(final String topMaterialId) {
        return new String[] {topMaterialId, topMaterialId, topMaterialId, topMaterialId};
    }

    private Composition composeTile(final TileKey key, final long token) {
        return composeTile(key, token, effectiveViewMode(), netherBiomeY);
    }

    private Composition composeTile(
        final TileKey key,
        final long token,
        final PredictionViewMode requestedMode,
        final int currentNetherY
    ) {
        final CompositionPlan plan = precheckTile(key, token, requestedMode, currentNetherY);
        if (plan == null) {
            return null;
        }
        if (plan.cached() != null) {
            return plan.cached();
        }
        if (plan.servedGrid() != null) {
            return composeSampledTile(
                plan, plan.servedGrid(), plan.servedDerived(), plan.servedQuadrantMask(),
                0L, 0L, null, null
            );
        }
        final BaselineGrid grid;
        final DerivedGrid derived;
        final QuadrantLayout quadrants = state.quadrantLayout(key.dimension());
        final QuadrantLayout.Mask quadrantMask;
        final boolean timing = debugTimingEnabled();
        CountingSampler counting = null;
        long[] overworldStageSink = null;
        long stageGpuDelta = 0L;
        if (plan.lowerNetherBiome()) {
            final BaselineSampler injected = testInjectedSampler(plan);
            final NativeBaselineSampler nativeSampler = injected == null
                ? new NativeBaselineSampler(
                    state.mcVersion(), plan.seed(), plan.nativeDim(),
                    state.cubiomesFlags(key.dimension())
                )
                : null;
            final BaselineSampler sampler;
            if (injected != null) {
                sampler = injected;
            } else if (timing) {
                counting = new CountingSampler(nativeSampler);
                sampler = counting;
            } else {
                sampler = nativeSampler;
            }
            final long stagesBefore = timing && nativeSampler != null ? nativeSampler.gpuStageStats() : 0L;
            grid = LodSampling.sampleNetherBiomesAtY(
                sampler, plan.lod(), plan.tileOriginX(), plan.tileOriginZ(), plan.netherY()
            );
            if (timing && nativeSampler != null) {
                stageGpuDelta = nativeSampler.gpuStageStats() - stagesBefore;
            }
            if (grid == null) {
                return null;
            }
            // Only flat quadrants carry a configured uniform biome; cleared quadrants keep the
            // real sampled biomes quadra-gen preserved.
            if (quadrants != null) {
                quadrants.applyBiomes(grid);
            }
            derived = BaselineDeriver.derive(grid);
            quadrantMask = null;
        } else {
            final FlatBaseline flat = state.flatBaseline(key.dimension());
            if (isSuperflat(key.dimension())) {
                // Superflat: every column is the same known surface - no seed, no native sampling.
                grid = flat.toBaselineGrid();
                derived = flat.toDerivedGrid();
                quadrantMask = flatQuadrantMask(key.dimension());
            } else {
                final BaselineSampler injected = testInjectedSampler(plan);
                final NativeBaselineSampler nativeSampler = injected == null
                    ? new NativeBaselineSampler(
                        state.mcVersion(), plan.seed(), plan.nativeDim(),
                        state.cubiomesFlags(key.dimension())
                    )
                    : null;
                final BaselineSampler sampler;
                if (injected != null) {
                    sampler = injected;
                } else if (timing) {
                    counting = new CountingSampler(nativeSampler);
                    sampler = counting;
                } else {
                    sampler = nativeSampler;
                }
                final boolean overworldBiomeView = BiomeTileKeys.isBiome(key)
                    && key.dimension().equals(DimensionId.OVERWORLD);
                final long stagesBefore = timing && nativeSampler != null
                    ? nativeSampler.gpuStageStats() : 0L;
                if (overworldBiomeView) {
                    // The biome-identity view never reads terrain: biomes come from the
                    // terrain-free sampler path, and the canopy (a terrain-map color pass) is
                    // dead work for the flat-color composer.
                    grid = LodSampling.sampleOverworldBiomes(
                        sampler, plan.lod(), plan.tileOriginX(), plan.tileOriginZ()
                    );
                    if (timing && nativeSampler != null) {
                        stageGpuDelta = nativeSampler.gpuStageStats() - stagesBefore;
                    }
                    if (grid == null) {
                        return null;
                    }
                    if (quadrants != null) {
                        quadrants.applyBiomes(grid);
                    }
                    derived = BaselineDeriver.derive(grid);
                    quadrantMask = null;
                } else {
                    final long[] overworldStageNanos = timing
                        ? new long[LodSampling.OVERWORLD_STAGE_COUNT] : null;
                    grid = key.dimension().equals(DimensionId.NETHER)
                        ? LodSampling.sampleNetherRoof(
                            sampler, plan.lod(), plan.tileOriginX(), plan.tileOriginZ()
                        )
                        : LodSampling.sample(
                            sampler, plan.end(), plan.lod(),
                            plan.tileOriginX(), plan.tileOriginZ(), overworldStageNanos
                        );
                    if (timing && nativeSampler != null) {
                        stageGpuDelta = nativeSampler.gpuStageStats() - stagesBefore;
                    }
                    if (grid == null) {
                        return null;
                    }
                    final OverworldSampled sampled = finishOverworldSample(plan, grid);
                    derived = sampled.derived();
                    quadrantMask = sampled.quadrantMask();
                    overworldStageSink = overworldStageNanos;
                }
            }
        }
        return composeSampledTile(
            plan, grid, derived, quadrantMask,
            counting == null ? 0L : counting.points(), stageGpuDelta,
            counting == null ? null : counting.stageNanos(), overworldStageSink
        );
    }

    /**
     * One wave member's composition: the shared precheck (a cache-served member bypasses the
     * stages but still attends every barrier), the staged sampling, then the same overworld
     * post-sampling steps and tail as the single-shot path.
     */
    private Composition composeStagedMember(
        final WaveComposer wave,
        final int index,
        final TileKey key,
        final long token,
        final PredictionViewMode requestedMode,
        final int currentNetherY
    ) {
        final CompositionPlan plan = precheckTile(key, token, requestedMode, currentNetherY);
        if (plan == null || plan.cached() != null) {
            wave.bypassMember(index);
            wave.runMember(index);
            return plan == null ? null : plan.cached();
        }
        if (plan.servedGrid() != null) {
            // Same bypass protocol as a mip-served member: decided before the stages, but it
            // still attends every barrier and releases its reservation through runMember.
            wave.bypassMember(index);
            wave.runMember(index);
            return composeSampledTile(
                plan, plan.servedGrid(), plan.servedDerived(), plan.servedQuadrantMask(),
                0L, 0L, null, null
            );
        }
        if (!wave.runMember(index)) {
            return null;
        }
        final OverworldSampled sampled = finishOverworldSample(plan, wave.grid(index));
        // Wave members account per-tile sampling plus their batch shares; stageGpuDelta stays 0 -
        // the GPU stage counters are per-context and the batch lands on the leader's context.
        return composeSampledTile(
            plan, wave.grid(index), sampled.derived(), sampled.quadrantMask(),
            wave.sampledPoints(index), 0L, wave.stageNanos(index),
            wave.overworldStageNanos(index)
        );
    }

    /** Post-sampling overworld terrain steps shared by the single-shot path and wave members. */
    private OverworldSampled finishOverworldSample(final CompositionPlan plan, final BaselineGrid grid) {
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        if (!BiomeTileKeys.isBiome(plan.key())) {
            CanopyStylizer.apply(
                derived, grid, plan.seed(), plan.lod(), plan.tileOriginX(), plan.tileOriginZ()
            );
        }
        final QuadrantLayout quadrants = state.quadrantLayout(plan.key().dimension());
        // Quadrant layouts (quadra-gen) overwrite their columns after derivation and canopy, so
        // synthetic trees cannot survive in a cleared or flat quadrant. The companion's
        // PatchBuilder masks its residual baseline with the same code.
        return new OverworldSampled(derived, quadrants == null ? null : quadrants.apply(grid, derived));
    }

    /** The fresh-sampled tail both composition shapes share: shade, sync planes, masking, upload. */
    private Composition composeSampledTile(
        final CompositionPlan plan,
        final BaselineGrid grid,
        final DerivedGrid derived,
        final QuadrantLayout.Mask quadrantMask,
        final long sampledPoints,
        final long stageGpuDelta,
        final long[] stageNanos,
        final long[] overworldStageNanos
    ) {
        final TileKey key = plan.key();
        final CorrectionTile directCorrections = plan.directCorrections();
        final int[] pixels = shade(
            key, derived, grid, directCorrections, plan.compositionMode(), quadrantMask
        );
        final byte[] syncEvaluated = directCorrections == null
            ? new byte[PatchCodec.MASK_BYTES] : directCorrections.copyEvaluated();
        // No server differential means no column can be an evaluated sync pixel, so the
        // chunk-granular revision plane is left unallocated instead of being filled with
        // Long.MIN_VALUE.
        final long[] syncChunkSourceRevisions = directCorrections == null
            ? null : directCorrections.copyChunkSourceRevisions();
        final byte[] blockLight = directCorrections == null
            ? new byte[PatchCodec.PIXELS] : directCorrections.copyBlockLight();
        uploads.maskPredictedPixels(
            realKey(key), pixels, syncEvaluated, syncChunkSourceRevisions,
            directCorrections == null
                ? CorrectionProfile.LEGACY_V1 : directCorrections.correctionProfile()
        );
        final AppliedLighting appliedLighting = applyLayerLighting(
            key, pixels, blockLight, plan.style()
        );
        final PredictionMipCache.Tile planar = planarTile(
            key, grid, derived, blockLight, syncEvaluated, syncChunkSourceRevisions,
            plan.compositionMode(), plan.directHasServerState(), plan.directValidatedAt(),
            directCorrections == null
                ? CorrectionProfile.LEGACY_V1 : directCorrections.correctionProfile()
        );
        publishBaseline(plan, grid, planar);
        return new Composition(
            tileUpdate(
                key, pixels, blockLight, appliedLighting.daylight(), appliedLighting.gamma(),
                plan.style()
            ),
            new TileMetadata(
                biomeIds(grid, directCorrections, grid),
                surfaceYs(derived, directCorrections, derived)
            ),
            planar,
            sampledPoints,
            stageGpuDelta,
            stageNanos,
            overworldStageNanos
        );
    }

    /**
     * Publishes the directly sampled planes behind this composition. Only a baseline-eligible
     * miss publishes: a hit already owns its entry, supersampled grids cannot round-trip their
     * sub-samples, and a tile whose identity (token, generation, serving engine) changed between
     * lookup and publication belongs to a state this cache no longer serves - a late composition
     * must never refill a reloaded session's entries.
     */
    private void publishBaseline(
        final CompositionPlan plan, final BaselineGrid grid, final PredictionMipCache.Tile planar
    ) {
        final PredictionBaselineCache.Key key = plan.baselineKey();
        if (key == null || planar == null || plan.servedBaseline() != null || grid.supersampled()) {
            return;
        }
        final int baselineNetherY = plan.lowerNetherBiome() ? plan.netherY() : 0;
        if (!key.equals(baselineCacheKey(
            plan.key(), plan.token(), baselineNetherY, servingEngineSnapshot()
        ))) {
            return;
        }
        baselineCache.put(key, planar);
    }

    /** The composition's sampler: the test-injected fake (same seam as the wave factory) wins. */
    private BaselineSampler testInjectedSampler(final CompositionPlan plan) {
        final WaveSamplerFactory factory = waveSamplerFactoryForTest;
        return factory == null
            ? null
            : factory.create(
                state.mcVersion(), plan.seed(), plan.nativeDim(),
                state.cubiomesFlags(plan.key().dimension())
            );
    }

    /**
     * The effective backend identity this session's planes are being produced under: the engine
     * mode alone cannot separate results (GPU auto silently serves the exact CPU reference when
     * no OpenCL device survives), so the probed device and the engine's death latch decide. A
     * dead engine keeps its mode and device name while serving the CPU loops, so without the
     * latch check a GPU-death fallback would keep reading and writing the GPU identity. Mixed
     * or unknown states read as {@link PredictionBaselineCache#ENGINE_NONE}, which still keys
     * entries consistently - only identity *changes* between lookup and publication reject an
     * entry.
     */
    private int servingEngineSnapshot() {
        final IntSupplier override = servingEngineForTest;
        if (override != null) {
            return override.getAsInt();
        }
        return servingEngine(GpuBackend.stats());
    }

    /**
     * Pure mapping from one stats snapshot to the baseline-cache engine identity: OFF and a
     * dead or device-less engine all serve the CPU reference, everything else with a probed
     * device is the GPU.
     */
    static int servingEngine(final GpuBackend.GpuStats stats) {
        if (stats == null) {
            return PredictionBaselineCache.ENGINE_NONE;
        }
        if (GpuBackend.modeOff(stats.engineMode()) || stats.engineDead()) {
            return PredictionBaselineCache.ENGINE_CPU;
        }
        final String device = stats.device();
        return device == null || device.isEmpty()
            ? PredictionBaselineCache.ENGINE_CPU
            : PredictionBaselineCache.ENGINE_GPU;
    }

    private PredictionBaselineCache.Key baselineCacheKey(
        final TileKey key, final long token, final int netherY, final int servingEngine
    ) {
        final PredictionState state = this.state;
        return new PredictionBaselineCache.Key(
            key, token, reloadGeneration, state.seed(), state.mcVersion(),
            state.preset(key.dimension()), state.flatBaseline(key.dimension()),
            state.quadrantLayout(key.dimension()), netherY, servingEngine
        );
    }

    /**
     * The pre-sampling section of every composition: session/layer gates plus the two cache
     * planes. Returns null when the tile must not compose at all, a plan whose {@link
     * CompositionPlan#cached()} composition is already complete on a cache hit, and otherwise
     * the plan the sampling branches and the fresh tail consume.
     */
    private CompositionPlan precheckTile(
        final TileKey key,
        final long token,
        final PredictionViewMode requestedMode,
        final int currentNetherY
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
        final MapLayer requestedLayer;
        try {
            requestedLayer = MapLayer.parse(BiomeTileKeys.baseLayerId(key));
        } catch (final IllegalArgumentException e) {
            return null;
        }
        final boolean lowerNetherBiome = BiomeTileKeys.isBiome(key)
            && isLowerNetherLayer(key.dimension(), requestedLayer);
        if (!requestedLayer.equals(expectedLayer) && !lowerNetherBiome) {
            return null;
        }
        final int netherY = requestedLayer.type() == MapLayer.Type.NETHER_SLICE
            ? requestedLayer.param()
            : currentNetherY;

        final int lod = key.lod();
        final int tileOriginX = key.originBlockX();
        final int tileOriginZ = key.originBlockZ();
        // Clamped here so every entry path (setViewMode, snapshotTile, background composition)
        // lands on the same cache-keyed mode.
        final PredictionViewMode compositionMode = serverPolicyAllowsPrediction.getAsBoolean()
            ? requestedMode == null ? PredictionViewMode.EVERYWHERE : requestedMode
            : PredictionViewMode.VISITED_ONLY;

        // Two cache planes are checked in cost order: this tile's own last composition (identical
        // pixels, nothing to aggregate) and then the four children aggregated to this LOD. Both
        // let the tile skip native sampling, canopy styling and colouring entirely, which is what
        // keeps a re-requested (re-evicted texture, view-mode re-entry, LOD return) tile cheap.
        final PredictionMipCache.Tile exact = mipCache.exact(key, compositionMode);
        final PredictionMipCache.Tile lower = exact != null
            ? null : mipCache.lowerTile(key, compositionMode);
        final CorrectionStore store = lowerNetherBiome || state.manualSeed()
            ? null : correctionStore;
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
        final MapColorStyle style = mapColorStyle;
        final PredictionMipCache.Tile reusable = reusableCacheTile(
            exact, lower, nowMillis, directHasServerState, directValidatedAt
        );
        if (reusable != null) {
            // The cached tile carries derived planes, not pixels: rebuild the grids it encodes
            // and re-run the fresh path's own composer seam (shade), so the hit is bit-identical
            // to a fresh composition and tracks the live styling instead of the composing-time
            // one.
            final DerivedGrid cachedDerived = new DerivedGrid();
            final BaselineGrid cachedGrid = new BaselineGrid(lod, tileOriginX, tileOriginZ);
            reconstructGrids(reusable, cachedGrid, cachedDerived);
            final QuadrantLayout.Mask quadrantMask =
                reapplyCachedLayering(key, cachedGrid, cachedDerived);
            final int[] pixels = shade(
                key, cachedDerived, cachedGrid, directCorrections, compositionMode, quadrantMask
            );
            // Live corrections win; the cached planes are the no-corrections fallback - the same
            // expressions the fresh path derives these planes with.
            final byte[] syncEvaluated = directCorrections == null
                ? reusable.syncEvaluated() : directCorrections.copyEvaluated();
            final long[] syncChunkSourceRevisions = directCorrections == null
                ? reusable.syncChunkSourceRevisions() : directCorrections.copyChunkSourceRevisions();
            final byte[] blockLight = directCorrections == null
                ? reusable.blockLight() : directCorrections.copyBlockLight();
            uploads.maskPredictedPixels(
                realKey(key), pixels, syncEvaluated, syncChunkSourceRevisions,
                directCorrections == null
                    ? reusable.correctionProfile() : directCorrections.correctionProfile()
            );
            final AppliedLighting appliedLighting = applyLayerLighting(
                key, pixels, blockLight, style
            );
            return new CompositionPlan(
                key, token, compositionMode, style, lowerNetherBiome, netherY, lod, tileOriginX,
                tileOriginZ, state.seed(), nativeDim, directCorrections, directHasServerState,
                directValidatedAt,
                new Composition(
                    tileUpdate(
                        key, pixels, blockLight, appliedLighting.daylight(), appliedLighting.gamma(),
                        style
                    ),
                    new TileMetadata(
                        biomeIds(cachedGrid, directCorrections, cachedGrid),
                        surfaceYs(cachedDerived, directCorrections, cachedDerived)
                    ),
                    reusable,
                    0L,
                    0L,
                    null,
                    null
                ),
                null, null, null, null, null
            );
        }
        // Third cache plane, behind the mip tiles: directly sampled baselines. Mode- and
        // correction-independent, so it serves exactly where the mip planes cannot - a mode
        // flip, an expired correction window, or a mip eviction would otherwise re-pay the
        // sampler for inputs nothing but the sampler can produce. Superflat grids carry their
        // own fixed texture origin and never sample, so they neither read nor publish entries.
        final int baselineNetherY = lowerNetherBiome ? netherY : 0;
        final PredictionBaselineCache.Key baselineKey = isSuperflat(key.dimension())
            ? null
            : baselineCacheKey(key, token, baselineNetherY, servingEngineSnapshot());
        final PredictionBaselineCache.Planes baseline = baselineKey == null
            ? null
            : baselineCache.get(baselineKey);
        final BaselineGrid servedGrid;
        final DerivedGrid servedDerived;
        final QuadrantLayout.Mask servedQuadrantMask;
        if (baseline != null) {
            servedGrid = new BaselineGrid(lod, tileOriginX, tileOriginZ);
            servedDerived = new DerivedGrid();
            baseline.reconstruct(servedGrid, servedDerived);
            servedQuadrantMask = reapplyCachedLayering(key, servedGrid, servedDerived);
        } else {
            servedGrid = null;
            servedDerived = null;
            servedQuadrantMask = null;
        }
        return new CompositionPlan(
            key, token, compositionMode, style, lowerNetherBiome, netherY, lod, tileOriginX,
            tileOriginZ, state.seed(), nativeDim, directCorrections, directHasServerState,
            directValidatedAt, null, servedGrid, servedDerived, servedQuadrantMask,
            baseline, baselineKey
        );
    }

    /**
     * The single composer invocation both composition paths run: the fresh path right after
     * sampling and canopy, the cache-hit path after rebuilding the grids from a cached tile's
     * planes. Sharing this seam is what makes a cache hit bit-identical to a fresh composition by
     * construction rather than by luck; styling inputs (palette, map-color style, ambient tint)
     * are read live here, so a re-shade tracks the current settings instead of stale ones.
     */
    private int[] shade(
        final TileKey key,
        final DerivedGrid derived,
        final BaselineGrid grid,
        final CorrectionTile directCorrections,
        final PredictionViewMode compositionMode,
        final QuadrantLayout.Mask quadrantMask
    ) {
        if (BiomeTileKeys.isBiome(key)) {
            return PredictedBiomeComposer.compose(
                derived, grid, directCorrections, compositionMode, key.lod(), derived, grid,
                voidColumnColor.argb()
            );
        }
        final MapColorStyle style = mapColorStyle;
        final int baselineMapColorId = baselineMapColorId(key);
        return PredictedTileComposer.compose(
            derived, grid, state.palette(), directCorrections, compositionMode, key.lod(),
            baselineMapColorId, derived, grid, baselineMapColorId,
            !key.dimension().equals(DimensionId.NETHER),
            ambientLightTint(key, style),
            syncedMaterials, style, XaeroMapStyle.shadowFor(key.dimension()),
            quadrantMask == null ? null : quadrantMask.mapColorOverrides(),
            quadrantMask == null ? null : quadrantMask.topMaterials(),
            voidColumnColor.argb()
        );
    }

    /**
     * The compose-wide vanilla map colour non-corrected baseline pixels paint: a superflat's
     * actual top block, the fixed Nether roof plane, or the biome palette. Purely a function of
     * the tile's identity and live state, so both composition paths derive the same value.
     */
    private int baselineMapColorId(final TileKey key) {
        if (isSuperflat(key.dimension())) {
            return state.flatBaseline(key.dimension()).mapColorId();
        }
        // Terrain mode owns the roof material, while the separate biome composer owns
        // biome identity. Treat the fixed Nether plane like a uniform bedrock superflat.
        return key.dimension().equals(DimensionId.NETHER)
            ? PredictionDimensions.NETHER_ROOF_MAP_COLOR_ID
            : Proto.MAP_COLOR_NONE;
    }

    /** Ambient tint baked in before height and relief shading. */
    private static int ambientLightTint(final TileKey key, final MapColorStyle style) {
        if (key.dimension().equals(DimensionId.NETHER)) {
            return LightTint.multiplier(0, 0, true);
        }
        if (key.dimension().equals(DimensionId.END) && style == MapColorStyle.CONFLUX) {
            // The End owes the cave dark-light contract too: bake the zero-light ambient in
            // here (baseline and corrections alike), then the per-column block-light plane
            // replaces it in applyLayerLighting. The Xaero style bakes nothing - its terrain
            // shading adds light, which the ambient ratio-replace cannot invert - and instead
            // takes the curve applied over the shaded pixel, exactly like the captured Xaero
            // End tiles.
            return LightTint.multiplier(0, 0, false);
        }
        return 0xFFFFFFFF;
    }

    /** Whether the dimension composes from a uniform flat baseline instead of sampled terrain. */
    private boolean isSuperflat(final DimensionId dimension) {
        return state.preset(dimension) == WorldPreset.FLAT && state.flatBaseline(dimension) != null;
    }

    /** A whole-dimension flat surface declares the same top material in every quadrant. */
    private QuadrantLayout.Mask flatQuadrantMask(final DimensionId dimension) {
        final String topMaterialId = state.flatBaseline(dimension).topMaterialId();
        return topMaterialId.isEmpty() ? null
            : new QuadrantLayout.Mask(null, uniformFlatMaterials(topMaterialId));
    }

    private boolean lowerNetherBiomeView(final TileKey key) {
        if (!BiomeTileKeys.isBiome(key)) {
            return false;
        }
        try {
            return isLowerNetherLayer(
                key.dimension(), MapLayer.parse(BiomeTileKeys.baseLayerId(key))
            );
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    /**
     * Re-applies the quadrant/flat layering a fresh composition applied before its planes were
     * snapshotted, so the composer arguments (the {@link QuadrantLayout.Mask} in particular)
     * come out identical on a cache hit. Every apply overwrites its columns with constants, so
     * re-running it on reconstructed grids is idempotent.
     */
    private QuadrantLayout.Mask reapplyCachedLayering(
        final TileKey key,
        final BaselineGrid grid,
        final DerivedGrid derived
    ) {
        final QuadrantLayout quadrants = state.quadrantLayout(key.dimension());
        if (lowerNetherBiomeView(key)) {
            if (quadrants != null) {
                quadrants.applyBiomes(grid);
            }
            return null;
        }
        if (isSuperflat(key.dimension())) {
            return flatQuadrantMask(key.dimension());
        }
        if (BiomeTileKeys.isBiome(key)) {
            if (quadrants != null) {
                quadrants.applyBiomes(grid);
            }
            return null;
        }
        return quadrants == null ? null : quadrants.apply(grid, derived);
    }

    /**
     * Rebuilds the sampled grids a cached tile's planes encode: the packed plane unpacks to the
     * full margin-inclusive derived arrays (void cells back to {@link BaselineGrid#NO_SURFACE})
     * and the biome plane fills the inner cells; margin biome cells stay zero because the
     * composer and the metadata helpers only ever read inner indices. Package-visible so the
     * plane parity test round-trips through the exact seam the hit path uses.
     */
    static void reconstructGrids(
        final PredictionMipCache.Tile tile,
        final BaselineGrid grid,
        final DerivedGrid derived
    ) {
        reconstructGrids(tile.packedSurfaceKind(), tile.fluidDepth(), tile.biomes(), grid, derived);
    }

    private static void reconstructGrids(
        final short[] surfaceKind, final byte[] fluidDepth, final byte[] biomes,
        final BaselineGrid grid, final DerivedGrid derived
    ) {
        PackedSurfacePlane.unpackPlane(surfaceKind, derived.surfaceY, derived.kind);
        for (int i = 0; i < PackedSurfacePlane.CELLS; i++) {
            derived.fluidDepth[i] = fluidDepth[i] & 0xFF;
        }
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                grid.biomeId[BaselineGrid.index(x, z)] = biomes[z * BaselineGrid.PIXELS + x] & 0xFF;
            }
        }
    }

    /**
     * Compacts the freshly derived planes into a cache tile, or returns null when the tile
     * cannot round-trip: the biome plane is byte-wide, and a server-synced flat baseline can
     * declare biome ids beyond it. A null result still composes and uploads; the tile just never
     * enters the mip cache.
     */
    private static PredictionMipCache.Tile planarTile(
        final TileKey key,
        final BaselineGrid grid,
        final DerivedGrid derived,
        final byte[] blockLight,
        final byte[] syncEvaluated,
        final long[] syncChunkSourceRevisions,
        final PredictionViewMode compositionMode,
        final boolean directHasServerState,
        final long directValidatedAt,
        final CorrectionProfile correctionProfile
    ) {
        final short[] packedSurfaceKind = PackedSurfacePlane.packPlane(derived.surfaceY, derived.kind);
        // Fluid depths are already 0-255, so the narrowing byte is the plane's own storage form.
        final byte[] fluidDepth = new byte[PackedSurfacePlane.CELLS];
        for (int i = 0; i < PackedSurfacePlane.CELLS; i++) {
            fluidDepth[i] = (byte) derived.fluidDepth[i];
        }
        final byte[] biomes = new byte[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        for (int pixel = 0; pixel < biomes.length; pixel++) {
            final int biomeId = grid.biomeId[BaselineGrid.index(pixel & 255, pixel >>> 8)];
            if (biomeId < 0 || biomeId > 0xFF) {
                return null;
            }
            biomes[pixel] = (byte) biomeId;
        }
        return new PredictionMipCache.Tile(
            key.lod(),
            packedSurfaceKind,
            fluidDepth,
            biomes,
            blockLight,
            syncEvaluated,
            syncChunkSourceRevisions,
            compositionMode,
            directHasServerState,
            directValidatedAt,
            directHasServerState ? directValidatedAt : 0L,
            correctionProfile
        );
    }

    /**
     * Picks the cheapest cache source that is still allowed to stand in for a fresh composition,
     * preferring the tile's own previous result over an aggregated parent. A hit must be inside the
     * correction reuse window and must not be older than the committed server state already waiting
     * for this tile - otherwise the cached (possibly predicted-only) pixels would outrank newer
     * authoritative data on screen.
     */
    private static PredictionMipCache.Tile reusableCacheTile(
        final PredictionMipCache.Tile exact,
        final PredictionMipCache.Tile lower,
        final long nowMillis,
        final boolean directHasServerState,
        final long directValidatedAt
    ) {
        if (isReusable(exact, nowMillis, directHasServerState, directValidatedAt)) {
            return exact;
        }
        return isReusable(lower, nowMillis, directHasServerState, directValidatedAt) ? lower : null;
    }

    private static boolean isReusable(
        final PredictionMipCache.Tile tile,
        final long nowMillis,
        final boolean directHasServerState,
        final long directValidatedAt
    ) {
        return tile != null
            && tile.isVisuallyReusableAt(nowMillis, CORRECTION_REUSE_TTL_MS)
            && (!directHasServerState
                || directValidatedAt == 0L
                || tile.freshnessValidatedAtMillis() >= directValidatedAt);
    }

    private static TileKey realKey(final TileKey predictedKey) {
        return new TileKey(
            predictedKey.world(), predictedKey.dimension(),
            PredictedTileKeys.realLayerId(predictedKey.layerId()),
            predictedKey.lod(), predictedKey.tileX(), predictedKey.tileZ()
        );
    }

    /**
     * Whether the parent can be reconstructed entirely from newer, committed lower-LOD results
     * (tests only). The live network-sync admission decision runs through {@code
     * prepareFreshLowerCoverage}, which also drives the parent patch requests.
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
        final PredictionMipCache.Tile exact = mipCache.exact(parent, effectiveViewMode());
        final long exactValidatedAt = exact == null ? PredictionMipCache.MISSING
            : exact.serverCoverageValidatedAtMillis();
        final long validatedAt = exactValidatedAt != PredictionMipCache.MISSING
            ? exactValidatedAt
            : mipCache.lowerCoverageValidatedAt(parent, effectiveViewMode());
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
        final PredictionMipCache.Tile exact = mipCache.exact(parent, effectiveViewMode());
        final long validatedAt = exact != null && exact.serverCoverageValidatedAtMillis() > 0L
            ? exact.serverCoverageValidatedAtMillis()
            : mipCache.lowerCoverageValidatedAt(parent, effectiveViewMode());
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
        TileMetadata reducedMetadata = null;
        int[] reducedPixels = null;
        try {
            final long nowMillis = millisClock.getAsLong();
            if (hasFreshPersistedChildren(parent, token, nowMillis)) {
                reduced = reduceFreshChildren(parent, token, nowMillis, effectiveViewMode());
            }
            if (reduced != null) {
                // The reduced tile is planar, so its upload re-shades through the same seam a
                // cache hit uses. The reduction path has no parent-level CorrectionTile to
                // re-apply; the aggregated correction planes already ride along on the tile.
                // Shaded out here so the compose never runs under the service monitor.
                final DerivedGrid cachedDerived = new DerivedGrid();
                final BaselineGrid cachedGrid = new BaselineGrid(
                    parent.lod(), parent.originBlockX(), parent.originBlockZ()
                );
                reconstructGrids(reduced, cachedGrid, cachedDerived);
                final QuadrantLayout.Mask quadrantMask =
                    reapplyCachedLayering(parent, cachedGrid, cachedDerived);
                reducedPixels = shade(parent, cachedDerived, cachedGrid, null, effectiveViewMode(), quadrantMask);
                applyLayerLighting(parent, reducedPixels, reduced.blockLight(), mapColorStyle);
                reducedMetadata = new TileMetadata(
                    biomeIds(cachedGrid, null, cachedGrid),
                    surfaceYs(cachedDerived, null, cachedDerived)
                );
            }
        } finally {
            synchronized (this) {
                if (generation == lowerCoverageGeneration && sessionGuard.isCurrent(token)) {
                    if (reduced == null) {
                        missingLowerCoverage.add(parent);
                    } else {
                        mipCache.put(parent, reduced);
                        metadataTiles.put(metadataKey(parent), reducedMetadata);
                        uploads.submitUpload(TileUpdate.fullTile(parent, reducedPixels));
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
                    child = composition == null ? null : composition.tile();
                } else {
                    child = reduceFreshChildren(childKey, token, nowMillis, mode);
                }
                if (child == null) {
                    return null;
                }
                children[childZ * 2 + childX] = child;
            }
        }
        return PredictionMipCache.aggregate(children, mode, parent.lod());
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
            if (viewport != null && viewport.contains(ancestor)) {
                dirtyPut(ancestor, token);
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

    private static short[] surfaceYs(
        final DerivedGrid derived,
        final CorrectionTile corrections,
        final DerivedGrid correctionDerived
    ) {
        final short[] surfaces = new short[BaselineGrid.PIXELS * BaselineGrid.PIXELS];
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                surfaces[z * BaselineGrid.PIXELS + x] =
                    packedSurfaceY(derived.surfaceY[BaselineGrid.index(x, z)]);
            }
        }
        if (corrections != null) {
            final PatchCodec.Patch correctionPatch = corrections.copyPatch();
            if (corrections.patchMode() == Proto.PATCH_MODE_RESIDUAL) {
                final byte[] evaluated = correctionPatch.evaluated();
                for (int pixel = 0; pixel < surfaces.length; pixel++) {
                    if ((evaluated[pixel >>> 3] & (1 << (pixel & 7))) != 0) {
                        surfaces[pixel] = packedSurfaceY(correctionDerived.surfaceY[
                            BaselineGrid.index(pixel & 255, pixel >>> 8)
                        ]);
                    }
                }
            }
            for (final PatchCodec.Sample sample : correctionPatch.samples()) {
                final SurfaceKind sampleKind = SurfaceKind.byOrdinal(sample.kind());
                final int pixel = sample.pixelIndex();
                if (sampleKind == SurfaceKind.UNKNOWN) {
                    surfaces[pixel] = packedSurfaceY(derived.surfaceY[
                        BaselineGrid.index(pixel & 255, pixel >>> 8)
                    ]);
                } else if (sampleKind == SurfaceKind.VOID) {
                    // The wire carries a short-domain sentinel here; the lookup reads back
                    // against BaselineGrid.NO_SURFACE, and a synchronized void column must
                    // surface as "known, no surface" to teleport staging.
                    surfaces[pixel] = BaselineGrid.NO_SURFACE_SHORT;
                } else {
                    surfaces[pixel] = packedSurfaceY(sample.surfaceY());
                }
            }
        }
        return surfaces;
    }

    /**
     * Maps the int-domain surface height onto the packed short plane. {@code (short) NO_SURFACE}
     * is {@code 0} - a perfectly valid Y - so the sentinel is translated, never narrowed.
     */
    private static short packedSurfaceY(final int surfaceY) {
        if (surfaceY == BaselineGrid.NO_SURFACE) {
            return BaselineGrid.NO_SURFACE_SHORT;
        }
        if (surfaceY < Short.MIN_VALUE + 1 || surfaceY > Short.MAX_VALUE) {
            // Only a corrupt/unknown-domain sample can land here; keep it "no surface" rather
            // than wrap it into a plausible-looking height.
            return BaselineGrid.NO_SURFACE_SHORT;
        }
        return (short) surfaceY;
    }

    public synchronized Set<TileKey> queuedOrInFlightKeysForTest() {
        final Set<TileKey> keys = new HashSet<>(dirty.keySet());
        keys.addAll(inFlight);
        return keys;
    }

    /** Test-support only: how many baseline plane sets the reuse cache currently holds. */
    synchronized int baselineCacheSizeForTest() {
        return baselineCache.size();
    }

    /** Test-support only: a snapshot of currently-queued (not yet in-flight) predicted tile keys. */
    public synchronized Set<TileKey> pendingKeysForTest() {
        return new HashSet<>(dirty.keySet());
    }

    /** Caller must hold the monitor: slots pinned by members that finished but whose wave has not. */
    private int reservedWaveSlots() {
        int held = 0;
        for (final int[] reservation : waveReservations.values()) {
            held += reservation[1];
        }
        return held;
    }

    /**
     * Test seam for the wave reservation ledger: composition slots currently pinned by waves that
     * have not fully finished. Every live wave pins its whole size - its unfinished members count
     * through {@code inFlight}, its finished members through the ledger - so an eight-member wave
     * reads 8 from admission until its last member releases all of them; 0 when quiescent. Same
     * debug-seam category as {@link #tileOverworldStageNanosForTest()}.
     */
    int reservedWaveSlotsForTest() {
        synchronized (this) {
            int reserved = 0;
            for (final int[] reservation : waveReservations.values()) {
                reserved += reservation[0] + reservation[1];
            }
            return reserved;
        }
    }

    /** Test-support only: whether every queued/in-flight tile has drained. */
    public synchronized boolean isIdleForTest() {
        return dirty.isEmpty() && inFlight.isEmpty()
            && lowerCoverageQueue.isEmpty() && lowerCoverageInFlight.isEmpty();
    }

    /**
     * Test-support only: the outcome of the last closed viewport load, or null while none has
     * closed yet. Debug-timing data only - populated when the aggregate logs, so a null says
     * "the window is still open", e.g. generation still settling.
     */
    public ViewportLoadResult lastViewportLoadForTest() {
        return lastViewportLoadForTest;
    }

    /**
     * Every finished composition's six-stage wall vector, keyed by tile. Package-private
     * benchmark seam: the load window only tracks tiles inside the viewport rect, while the
     * ring-only staging topology composes tiles outside it - the harness aggregates this map
     * itself. A missing entry means the tile did not run the staged sampler.
     */
    Map<TileKey, long[]> tileOverworldStageNanosForTest() {
        synchronized (this) {
            return new HashMap<>(tileOverworldStageNanosForTest);
        }
    }

    /**
     * Test-support only: the fullscreen viewport's predicted tile keys - the set the map screen
     * binds every frame - empty when no viewport is set or the dimension has no predicted layer.
     */
    public synchronized Set<TileKey> viewportTilesForTest() {
        final ViewportRect rect = viewport;
        final SessionGuard.Session session = sessionGuard.current();
        if (rect == null || !session.active()) {
            return Set.of();
        }
        final MapLayer predictedLayer = PredictionDimensions.layer(rect.dimension());
        if (predictedLayer == null) {
            return Set.of();
        }
        final String layer = predictedLayer.cacheId() + PredictedTileKeys.SUFFIX;
        final Set<TileKey> keys = new HashSet<>();
        for (int tileZ = rect.minTileZ(); tileZ <= rect.maxTileZ(); tileZ++) {
            for (int tileX = rect.minTileX(); tileX <= rect.maxTileX(); tileX++) {
                keys.add(new TileKey(session.world(), rect.dimension(), layer, rect.lod(), tileX, tileZ));
            }
        }
        return keys;
    }

    private PixelLookup visiblePixelLookup(
        final DimensionId dimension,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        return visiblePixelLookup(
            dimension, PredictionDimensions.layer(dimension), lod, blockX, blockZ
        );
    }

    private PixelLookup visiblePixelLookup(
        final DimensionId dimension,
        final MapLayer layer,
        final int lod,
        final int blockX,
        final int blockZ
    ) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!session.active() || layer == null
            || !dimension.equals(session.dimension()) || !state.predictable(dimension)) {
            return null;
        }
        final int tileX = TileMath.blockToTile(blockX, lod);
        final int tileZ = TileMath.blockToTile(blockZ, lod);
        final int pixelX = TileMath.blockToPixelInTile(blockX, lod);
        final int pixelZ = TileMath.blockToPixelInTile(blockZ, lod);
        final int pixel = pixelZ * TileMath.TILE_SIZE + pixelX;
        final CorrectionStore store = state.manualSeed() ? null : correctionStore;
        final CorrectionTile corrections = store == null ? null : store.get(dimension, lod, tileX, tileZ);
        if (!effectiveViewMode().showsPredictedPixels(corrections, pixel, lod)) {
            return null;
        }
        if (!layer.equals(PredictionDimensions.layer(dimension))
            && !isLowerNetherLayer(dimension, layer)) {
            return null;
        }
        final TileKey metadataKey = new TileKey(
            session.world(), dimension, layer.cacheId() + PredictedTileKeys.SUFFIX,
            lod, tileX, tileZ
        );
        final TileKey requestKey = isLowerNetherLayer(dimension, layer)
            ? BiomeTileKeys.toBiome(metadataKey)
            : metadataKey;
        return new PixelLookup(metadataKey, requestKey, pixel);
    }

    /**
     * One finished composition: what to upload, the cursor metadata it published, the planar
     * cache tile it produced or served - {@code null} when the tile is not planar-cacheable (see
     * {@link #planarTile}) - the sampler query points it paid for (0 when served from a cache or
     * sampled by a non-counting path), the packed stage-stats delta that attributes its
     * sampling to GPU or CPU (see {@link NativeBaselineSampler#gpuStageStats}), and the
     * per-{@link SampleStage} wall-time split of its native sampling (null on the same
     * non-counting paths). Every correction or styling plane a composition needs is either
     * inside the tile or rebuilt locally by the path that produced it.
     */
    /**
     * Everything the sampling branches and the fresh tail need from the pre-sampling section:
     * identity and styling snapshots, correction references, and - on a cache hit - the already
     * complete {@link #cached()} composition. A baseline hit instead fills the {@code served*}
     * grids (reconstructed planes plus the re-applied quadrant layering) and leaves {@code
     * cached()} null, so both composition shapes run the same fresh tail; {@code baselineKey}
     * stays set on every baseline-eligible tile (hit or miss) and drives publication.
     */
    private record CompositionPlan(
        TileKey key,
        long token,
        PredictionViewMode compositionMode,
        MapColorStyle style,
        boolean lowerNetherBiome,
        int netherY,
        int lod,
        int tileOriginX,
        int tileOriginZ,
        long seed,
        int nativeDim,
        CorrectionTile directCorrections,
        boolean directHasServerState,
        long directValidatedAt,
        Composition cached,
        BaselineGrid servedGrid,
        DerivedGrid servedDerived,
        QuadrantLayout.Mask servedQuadrantMask,
        PredictionBaselineCache.Planes servedBaseline,
        PredictionBaselineCache.Key baselineKey
    ) {
        boolean end() {
            return PredictionDimensions.isEnd(key.dimension());
        }
    }

    /** Post-sampling overworld products handed to the fresh composition tail. */
    private record OverworldSampled(DerivedGrid derived, QuadrantLayout.Mask quadrantMask) {
    }

    private record Composition(
        TileUpdate update,
        TileMetadata metadata,
        PredictionMipCache.Tile tile,
        long sampledPoints,
        long stageGpuDelta,
        long[] stageNanos,
        long[] overworldStageNanos
    ) {
    }

    private record TileMetadata(byte[] biomes, short[] surfaces) {
        /**
         * Int-domain surface height, or {@link BaselineGrid#NO_SURFACE}. Keeping this as the only
         * read seam means callers never compare against the packed short sentinel.
         */
        int surfaceYAt(final int pixel) {
            final short packed = surfaces[pixel];
            return packed == BaselineGrid.NO_SURFACE_SHORT ? BaselineGrid.NO_SURFACE : packed;
        }
    }

    private record AppliedLighting(float daylight, float gamma) {
        private static final AppliedLighting NONE = new AppliedLighting(Float.NaN, 0f);
    }

    private record PixelLookup(TileKey key, TileKey requestKey, int pixel) {
    }

    /**
     * Metadata slot a composition publishes under: the tile's own layer, prediction suffix
     * stripped but the biome-identity suffix kept. Biome tiles therefore own a separate slot
     * from terrain tiles of the same base layer - their terrain-free surfaces would otherwise
     * masquerade as teleport heights whenever they compose last.
     */
    private static TileKey metadataKey(final TileKey key) {
        final String layer = PredictedTileKeys.isPredicted(key)
            ? PredictedTileKeys.realLayerId(key.layerId())
            : key.layerId();
        return new TileKey(
            key.world(), key.dimension(), layer + PredictedTileKeys.SUFFIX,
            key.lod(), key.tileX(), key.tileZ()
        );
    }

    private static boolean isLowerNetherLayer(
        final DimensionId dimension,
        final MapLayer layer
    ) {
        return dimension.equals(DimensionId.NETHER)
            && layer.type().isNetherFloor();
    }

    private static boolean isCurrentNetherBiomeKey(final TileKey key) {
        if (!key.dimension().equals(DimensionId.NETHER) || !BiomeTileKeys.isBiome(key)) {
            return false;
        }
        try {
            return MapLayer.parse(BiomeTileKeys.baseLayerId(key)).type()
                == MapLayer.Type.NETHER_CURRENT;
        } catch (final IllegalArgumentException e) {
            return false;
        }
    }

    private record ViewportRect(DimensionId dimension, int lod, int minTileX, int maxTileX, int minTileZ, int maxTileZ) {
        boolean contains(final TileKey key) {
            if (!dimension.equals(key.dimension()) || lod != key.lod()) {
                return false;
            }
            return contains(key.tileX(), key.tileZ());
        }

        boolean contains(final int tileX, final int tileZ) {
            return tileX >= minTileX && tileX <= maxTileX
                && tileZ >= minTileZ && tileZ <= maxTileZ;
        }

        boolean containsPadded(final TileKey key) {
            return containsPadded(key, 1);
        }

        boolean containsPadded(final TileKey key, final int pad) {
            if (!dimension.equals(key.dimension()) || lod != key.lod()) {
                return false;
            }
            return key.tileX() >= minTileX - pad && key.tileX() <= maxTileX + pad
                && key.tileZ() >= minTileZ - pad && key.tileZ() <= maxTileZ + pad;
        }

        /**
         * Squared distance between the rect's center and a tile's center, measured on a doubled
         * tile grid so an odd tile count per axis keeps an exact integer center. Four times the
         * real squared distance in tile units - ordering only.
         */
        long centerDistanceSq(final TileKey key) {
            final long dx = 2L * key.tileX() - minTileX - maxTileX;
            final long dz = 2L * key.tileZ() - minTileZ - maxTileZ;
            return dx * dx + dz * dz;
        }
    }
}
