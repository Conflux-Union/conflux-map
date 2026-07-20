package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

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
    private final SessionGuard sessionGuard;
    private final PredictionState state;
    private final MapExecutors executors;
    private final TileService uploads;
    private final int maxConcurrentCompositions;
    /**
     * Bounded concurrency while a fullscreen viewport is active. Strict serialization (1) left the
     * predicted underlay trailing the real tile's transparent unexplored pixels during pans, so the
     * screen background bled through as black gaps; row-major ordering still picks the next tile.
     */
    private static final int VISIBLE_CONCURRENCY = 3;
    private volatile CorrectionStore correctionStore;
    private volatile PredictionViewMode viewMode = PredictionViewMode.EVERYWHERE;

    /** Guarded by {@code this}: tiles waiting to be composed, with the session token that requested them. */
    private final Map<TileKey, Long> dirty = new HashMap<>();
    /** Guarded by {@code this}: tiles currently being composed on a worker. */
    private final Set<TileKey> inFlight = new HashSet<>();
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
        this.sessionGuard = sessionGuard;
        this.state = state;
        this.executors = executors;
        this.uploads = uploads;
        this.maxConcurrentCompositions = Math.max(1, executors.workerCount());
    }

    public void bindCorrectionStore(final CorrectionStore store) {
        this.correctionStore = store;
    }

    public void setViewMode(final PredictionViewMode mode) {
        this.viewMode = mode == null ? PredictionViewMode.EVERYWHERE : mode;
    }

    public PredictionViewMode viewMode() {
        return viewMode;
    }

    /** Applies a server patch and queues the affected predicted tile for recomposition. */
    public boolean applyCorrection(
        final CorrectionStore.Key key, final long revision, final byte[] presence, final cn.net.rms.confluxmap.core.net.PatchCodec.Patch patch
    ) {
        final CorrectionStore store = correctionStore;
        if (store == null || !store.apply(key, revision, presence, patch)) {
            return false;
        }
        final SessionGuard.Session session = sessionGuard.current();
        final DimensionId patchDimension = DimensionId.parse(key.dimension());
        final String realLayer = PredictionDimensions.isEnd(patchDimension)
            ? MapLayer.END_SURFACE.cacheId() : MapLayer.SURFACE.cacheId();
        final TileKey tile = new TileKey(
            session.world(), session.dimension(), realLayer + PredictedTileKeys.SUFFIX, key.lod(), key.tileX(), key.tileZ()
        );
        markDirty(tile, session.token());
        return true;
    }

    /** Main thread, from the session tracker: forget every queued/in-flight predicted tile, and invalidate cached native contexts. */
    public void onSessionChanged(final SessionGuard.Session session) {
        synchronized (this) {
            reloadGeneration++;
            dirty.clear();
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
            dirty.clear();
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
        synchronized (this) {
            viewport = rect;
            dirty.keySet().removeIf(key -> !rect.containsPadded(key));
        }
        pump();
    }

    /** Clears fullscreen ordering after the screen closes. */
    public void clearViewport() {
        synchronized (this) {
            viewport = null;
        }
        pump();
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
            // The renderer retries a missing texture every frame. Keep that retry idempotent so
            // one slow native composition cannot continuously requeue itself.
            if (dirty.containsKey(key) || inFlight.contains(key)) {
                return;
            }
            dirty.put(key, session.token());
        }
        pump();
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
        TileUpdate update = null;
        try {
            update = composeTile(key, token);
        } finally {
            synchronized (this) {
                if (update != null && generation == reloadGeneration) {
                    uploads.submitUpload(update);
                }
                inFlight.remove(key);
            }
            pump();
        }
    }

    private TileUpdate composeTile(final TileKey key, final long token) {
        final SessionGuard.Session session = sessionGuard.current();
        if (!sessionGuard.isCurrent(token) || !session.active()
            || !key.world().equals(session.world()) || !key.dimension().equals(session.dimension())
            || !state.predictable(key.dimension())) {
            return null;
        }
        final int nativeDim = PredictionDimensions.nativeDim(key.dimension());
        if (nativeDim < 0) {
            return null;
        }
        try {
            MapLayer.parse(PredictedTileKeys.realLayerId(key.layerId()));
        } catch (final IllegalArgumentException e) {
            return null;
        }

        final boolean end = PredictionDimensions.isEnd(key.dimension());
        final int lod = key.lod();
        final int tileOriginX = key.originBlockX();
        final int tileOriginZ = key.originBlockZ();
        final long seed = state.seed();

        final BaselineSampler sampler = new NativeBaselineSampler(state.mcVersion(), seed, nativeDim);
        final BaselineGrid grid = LodSampling.sample(sampler, end, lod, tileOriginX, tileOriginZ);
        if (grid == null) {
            return null;
        }
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, sampler, seed, lod, tileOriginX, tileOriginZ);

        final CorrectionStore store = correctionStore;
        final CorrectionTile corrections = store == null
            ? null
            : store.get(key.dimension(), lod, key.tileX(), key.tileZ());
        final int[] pixels = PredictedTileComposer.compose(
            derived, grid, state.palette(), corrections, viewMode, lod
        );
        return TileUpdate.fullTile(key, pixels);
    }

    /** Test-support only: a snapshot of currently-queued (not yet in-flight) predicted tile keys. */
    public synchronized Set<TileKey> pendingKeysForTest() {
        return new HashSet<>(dirty.keySet());
    }

    /** Test-support only: whether every queued/in-flight tile has drained. */
    public synchronized boolean isIdleForTest() {
        return dirty.isEmpty() && inFlight.isEmpty();
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
