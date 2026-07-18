package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
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
 * bounded by an in-flight cap (one per worker), nearest-viewpoint-first, session-token guarded -
 * same discipline, entirely separate data. Predictions never enter {@code ColumnStore}/{@code
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
    private final ConfluxConfig config;
    private final DaylightModel daylightModel;
    private final int maxConcurrentCompositions;

    /** Guarded by {@code this}: tiles waiting to be composed, with the session token that requested them. */
    private final Map<TileKey, Long> dirty = new HashMap<>();
    /** Guarded by {@code this}: tiles currently being composed on a worker. */
    private final Set<TileKey> inFlight = new HashSet<>();

    private volatile int viewpointX;
    private volatile int viewpointZ;

    public PredictionTileService(
        final SessionGuard sessionGuard,
        final PredictionState state,
        final MapExecutors executors,
        final TileService uploads,
        final ConfluxConfig config,
        final DaylightModel daylightModel
    ) {
        this.sessionGuard = sessionGuard;
        this.state = state;
        this.executors = executors;
        this.uploads = uploads;
        this.config = config;
        this.daylightModel = daylightModel;
        this.maxConcurrentCompositions = Math.max(1, executors.workerCount());
    }

    /** Main thread, from the session tracker: forget every queued/in-flight predicted tile, and invalidate cached native contexts. */
    public void onSessionChanged(final SessionGuard.Session session) {
        synchronized (this) {
            dirty.clear();
            inFlight.clear();
        }
        CubiomesContexts.bumpEpoch();
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
            dirty.keySet().removeIf(key -> !rect.containsPadded(key));
        }
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
        markDirty(key, session.token());
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
                uploads.submitUpload(update);
            }
        } finally {
            synchronized (this) {
                inFlight.remove(key);
            }
            pump();
        }
    }

    private TileUpdate composeTile(final TileKey key, final long token) {
        if (!sessionGuard.isCurrent(token) || !state.predictable(key.dimension())) {
            return null;
        }
        final int nativeDim = PredictionDimensions.nativeDim(key.dimension());
        if (nativeDim < 0) {
            return null;
        }
        final MapLayer layer;
        try {
            layer = MapLayer.parse(PredictedTileKeys.realLayerId(key.layerId()));
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
        CanopyStylizer.apply(derived, grid, seed, lod, tileOriginX, tileOriginZ);

        final boolean applyDaylight = layer.type() == MapLayer.Type.SURFACE && config.dynamicLighting;
        final float daylightFactor = applyDaylight ? daylightModel.factor() : 1f;
        final int[] pixels = PredictedTileComposer.compose(derived, grid, state.palette(), applyDaylight, daylightFactor);
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
        boolean containsPadded(final TileKey key) {
            if (!dimension.equals(key.dimension()) || lod != key.lod()) {
                return false;
            }
            return key.tileX() >= minTileX - 1 && key.tileX() <= maxTileX + 1
                && key.tileZ() >= minTileZ - 1 && key.tileZ() <= maxTileZ + 1;
        }
    }
}
