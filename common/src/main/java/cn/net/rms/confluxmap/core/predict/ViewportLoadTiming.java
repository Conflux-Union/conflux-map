package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * Aggregation state behind the prediction debug-timing logs: one end-to-end load window per
 * fullscreen viewport, running from the first visible-tier dispatch to the last counted tile's
 * texture upload completing on the render thread. The first visible-tier dispatch opens the
 * window on the dispatching viewport; every finished composition inside the window's rect that
 * actually queued an upload accumulates its compose/sampler-stage costs and joins the upload
 * wait set; every reported upload completes one member; when the owning service sees no queued
 * and no in-flight tile left inside the rect and no upload still pending, the window closes and
 * reports a {@link Summary}. Pure bookkeeping - no logging, no queue access - so tests drive
 * the aggregation directly. All methods assume single-threaded use under the service's monitor.
 */
final class ViewportLoadTiming {
    /** One finished (or superseded) viewport load; the service turns it into a log line. */
    record Summary(
        DimensionId dimension,
        int lod,
        int minTileX,
        int maxTileX,
        int minTileZ,
        int maxTileZ,
        boolean complete,
        /** Tiles whose texture upload completed inside the window - the displayed count. */
        int tiles,
        /** Compositions that finished inside the rect, whether or not their upload landed. */
        int attempts,
        long points,
        /** Wall nanos per {@link SampleStage} bucket, indexed by ordinal; never null. */
        long[] stageNanos,
        /**
         * Wall nanos per {@link LodSampling.OverworldStage}, indexed by ordinal; never null.
         * Stage wall includes the Java work a stage does around its sampler calls; batched
         * stages distribute the batch's wall evenly across their members.
         */
        long[] overworldStageNanos,
        long composeNanos,
        long schedNanos,
        long uploadWaitNanos,
        long uploadNanos,
        /** Wall nanos from window open to the first completed upload, or -1. */
        long firstUploadWallNanos,
        long wallNanos
    ) {
    }

    /** The viewport rect a window is open on; mirrors {@code PredictionTileService.ViewportRect}. */
    record Rect(
        DimensionId dimension,
        int lod,
        int minTileX,
        int maxTileX,
        int minTileZ,
        int maxTileZ
    ) {
        boolean contains(final DimensionId dim, final int l, final int tileX, final int tileZ) {
            return dimension.equals(dim) && lod == l
                && tileX >= minTileX && tileX <= maxTileX
                && tileZ >= minTileZ && tileZ <= maxTileZ;
        }

        boolean contains(final TileKey key) {
            return contains(key.dimension(), key.lod(), key.tileX(), key.tileZ());
        }
    }

    private Rect window;
    private long startNanos;
    private int tiles;
    private int attempts;
    private long points;
    private long composeNanos;
    private long schedNanos;
    private long uploadNanos;
    private long uploadWaitNanos;
    private long firstUploadWallNanos = -1L;
    private TileKey firstUploadedTile;
    private final long[] stageNanos = new long[SampleStage.COUNT];
    private final long[] overworldStageNanos = new long[LodSampling.OVERWORLD_STAGE_COUNT];
    /** Uploads queued but not yet reported complete, each with its queue-entry nanos. */
    private final Map<TileKey, Long> pendingUploads = new HashMap<>();

    /** Rect the open window is tracking, or {@code null} when no window is open. */
    Rect windowRect() {
        return window;
    }

    boolean active() {
        return window != null;
    }

    /** Wall nanos from window open to the first completed upload, or -1 before any upload. */
    long firstUploadWallNanos() {
        return firstUploadWallNanos;
    }

    /** The tile whose upload completed first inside the window, or {@code null}. */
    TileKey firstUploadedTile() {
        return firstUploadedTile;
    }

    /**
     * A visible-tier tile was dispatched from viewport {@code rect}. Opens the window, or
     * re-targets it when the fullscreen viewport has moved; a superseded window still holding
     * finished tiles is closed as incomplete and returned so the caller can log it. {@code
     * schedNanos} is this tile's enqueue-to-dispatch delay, or 0 when its enqueue wasn't
     * observed (see the service's queued-at tracking).
     */
    Summary dispatched(final Rect rect, final TileKey key, final long schedNanos, final long nowNanos) {
        if (window != null && window.equals(rect)) {
            this.schedNanos += schedNanos;
            return null;
        }
        final Summary superseded = close(false, nowNanos);
        window = rect;
        startNanos = nowNanos;
        resetAccumulators();
        this.schedNanos += schedNanos;
        return superseded;
    }

    /**
     * A composition finished. Submissions (compositions that actually queued an upload) inside
     * the window's rect accumulate their costs and join the upload wait set; superseded
     * compositions only bump the attempt count, since nothing of theirs will ever display.
     * When {@code idleWithin} says the service holds no pending generation work for the
     * window's rect anymore and no counted upload is outstanding, the window closes and is
     * returned as complete. Returns {@code null} while the window stays open (or when nothing
     * was tracked).
     */
    Summary finished(
        final TileKey key,
        final long tileComposeNanos,
        final long[] tileStageNanos,
        final long[] tileOverworldStageNanos,
        final long tilePoints,
        final boolean submittedUpload,
        final boolean idleWithin,
        final long nowNanos
    ) {
        if (window != null && window.contains(key)) {
            attempts++;
            if (submittedUpload) {
                tilesAccumulate(tileComposeNanos, tileStageNanos, tileOverworldStageNanos, tilePoints);
                pendingUploads.put(key, nowNanos);
            }
        }
        return closeIfIdle(idleWithin, nowNanos);
    }

    /**
     * A texture upload for {@code key} completed on the render thread. Completes one queued
     * upload, stamps the first-visible marker on the first one, and closes the window as
     * complete when it was the last outstanding piece and generation is idle.
     */
    Summary uploaded(
        final TileKey key,
        final long tileUploadNanos,
        final boolean idleWithin,
        final long nowNanos
    ) {
        final Long queuedNanos = window == null ? null : pendingUploads.remove(key);
        if (queuedNanos == null) {
            return null;
        }
        tiles++;
        uploadNanos += tileUploadNanos;
        uploadWaitNanos += nowNanos - queuedNanos;
        if (firstUploadWallNanos < 0L) {
            firstUploadWallNanos = nowNanos - startNanos;
            firstUploadedTile = key;
        }
        return closeIfIdle(idleWithin, nowNanos);
    }

    /**
     * Closes the window when no pending generation work remains for its rect and no counted
     * upload is still outstanding; the periodic caller-side check.
     */
    Summary closeIfIdle(final boolean idleWithin, final long nowNanos) {
        return idleWithin && pendingUploads.isEmpty() ? close(true, nowNanos) : null;
    }

    /**
     * Closes the window and returns its summary, or {@code null} when nothing was tracked (a
     * window whose every composition went stale never produces a log line).
     */
    private Summary close(final boolean complete, final long nowNanos) {
        if (window == null) {
            return null;
        }
        final Rect rect = window;
        final long wallNanos = nowNanos - startNanos;
        window = null;
        startNanos = 0L;
        if (attempts == 0) {
            resetAccumulators();
            return null;
        }
        final Summary summary = new Summary(
            rect.dimension(), rect.lod(),
            rect.minTileX(), rect.maxTileX(), rect.minTileZ(), rect.maxTileZ(),
            complete, tiles, attempts, points, stageNanos.clone(), overworldStageNanos.clone(),
            composeNanos, schedNanos,
            uploadWaitNanos, uploadNanos, firstUploadWallNanos, wallNanos
        );
        resetAccumulators();
        return summary;
    }

    /** Pure state reset shared by open and close. */
    private void resetAccumulators() {
        tiles = 0;
        attempts = 0;
        points = 0L;
        composeNanos = 0L;
        schedNanos = 0L;
        uploadNanos = 0L;
        uploadWaitNanos = 0L;
        firstUploadWallNanos = -1L;
        firstUploadedTile = null;
        Arrays.fill(stageNanos, 0L);
        Arrays.fill(overworldStageNanos, 0L);
        pendingUploads.clear();
    }

    private void tilesAccumulate(
        final long tileComposeNanos,
        final long[] tileStageNanos,
        final long[] tileOverworldStageNanos,
        final long tilePoints
    ) {
        points += tilePoints;
        composeNanos += tileComposeNanos;
        if (tileStageNanos != null) {
            for (int stage = 0; stage < SampleStage.COUNT && stage < tileStageNanos.length; stage++) {
                this.stageNanos[stage] += tileStageNanos[stage];
            }
        }
        if (tileOverworldStageNanos != null) {
            for (int stage = 0;
                stage < overworldStageNanos.length && stage < tileOverworldStageNanos.length;
                stage++) {
                this.overworldStageNanos[stage] += tileOverworldStageNanos[stage];
            }
        }
    }
}
