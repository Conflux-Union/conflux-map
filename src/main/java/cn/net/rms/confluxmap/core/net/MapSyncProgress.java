package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Tracks one user-visible server correction batch across one or more network requests. */
public final class MapSyncProgress {
    public enum State { IDLE, SYNCING, COMPLETED, FAILED }

    public record BatchTile(int tileX, int tileZ) {
    }

    public record Snapshot(
        State state,
        int completedTiles,
        int totalTiles,
        long durationNanos,
        long trafficBytes
    ) {
        public static final Snapshot IDLE = new Snapshot(State.IDLE, 0, 0, 0L, 0L);
    }

    private final Map<Integer, RequestProgress> inFlight = new HashMap<>();
    private final Set<Long> pendingBatchTiles = new HashSet<>();
    private final Set<RegionKey> pendingBatchRegions = new HashSet<>();
    private Snapshot snapshot = Snapshot.IDLE;
    private int batchDimIndex = -1;
    private int batchLod = -1;
    private int totalTiles;
    private long startedNanos;
    private long trafficBytes;
    private boolean started;

    /** Starts a viewport batch. Individual request boundaries do not reset its counters. */
    public synchronized void beginBatch(
        final int dimIndex, final int lod, final List<BatchTile> tiles
    ) {
        reset();
        batchDimIndex = dimIndex;
        batchLod = lod;
        for (final BatchTile tile : tiles) {
            pendingBatchTiles.add(tileKey(tile.tileX(), tile.tileZ()));
        }
        totalTiles = pendingBatchTiles.size();
    }

    public synchronized void beginRegionBatch(
        final int dimIndex, final int lod, final List<ChunkRegionSlice> regions
    ) {
        reset();
        batchDimIndex = dimIndex;
        batchLod = lod;
        for (final ChunkRegionSlice region : regions) {
            pendingBatchRegions.add(RegionKey.from(region));
        }
        totalTiles = pendingBatchRegions.size();
    }

    public synchronized void requestStarted(
        final MapViewReqC2S request, final int payloadBytes, final long nowNanos
    ) {
        if (!matchesBatch(request.dimIndex(), request.lod()) || pendingBatchTiles.isEmpty()) {
            return;
        }
        final RequestProgress requestProgress = RequestProgress.from(request, pendingBatchTiles);
        if (requestProgress.pendingTiles.isEmpty()) {
            return;
        }
        if (!started) {
            started = true;
            startedNanos = nowNanos;
        }
        inFlight.put(request.reqId(), requestProgress);
        trafficBytes += Math.max(0, payloadBytes);
        updateSyncing(nowNanos);
    }

    public synchronized void requestStarted(
        final MapRegionViewReqC2S request, final int payloadBytes, final long nowNanos
    ) {
        if (!matchesBatch(request.dimIndex(), request.lod()) || pendingBatchRegions.isEmpty()) {
            return;
        }
        final RequestProgress requestProgress = RequestProgress.from(request, pendingBatchRegions);
        if (requestProgress.pendingRegions.isEmpty()) {
            return;
        }
        if (!started) {
            started = true;
            startedNanos = nowNanos;
        }
        inFlight.put(request.reqId(), requestProgress);
        trafficBytes += Math.max(0, payloadBytes);
        updateSyncing(nowNanos);
    }

    public synchronized void patchReceived(
        final MapPatchS2C patch, final int payloadBytes, final long nowNanos
    ) {
        final long tileKey = tileKey(patch.tileX(), patch.tileZ());
        if (!matchesBatch(patch.dimIndex(), patch.lod()) || !pendingBatchTiles.contains(tileKey)) {
            return;
        }
        final RequestProgress request = inFlight.get(patch.reqId());
        if (request == null || request.dimIndex != patch.dimIndex() || request.lod != patch.lod()
            || !request.pendingTiles.remove(tileKey)) {
            return;
        }
        trafficBytes += Math.max(0, payloadBytes);
        if (request.pendingTiles.isEmpty()) {
            inFlight.remove(patch.reqId());
        }
        if (patch.mode() != Proto.PATCH_MODE_PARTIAL) {
            pendingBatchTiles.remove(tileKey);
        }
        updateAfterProgress(nowNanos);
    }

    public synchronized void regionPatchReceived(
        final MapRegionPatchS2C patch,
        final int payloadBytes,
        final boolean accepted,
        final long nowNanos
    ) {
        final RegionKey regionKey = RegionKey.from(patch.slice());
        if (!matchesBatch(patch.dimIndex(), patch.lod()) || !pendingBatchRegions.contains(regionKey)) {
            return;
        }
        final RequestProgress request = inFlight.get(patch.reqId());
        if (request == null || request.dimIndex != patch.dimIndex() || request.lod != patch.lod()
            || !request.pendingRegions.remove(regionKey)) {
            return;
        }
        trafficBytes += Math.max(0, payloadBytes);
        if (request.empty()) {
            inFlight.remove(patch.reqId());
        }
        if (accepted) {
            pendingBatchRegions.remove(regionKey);
        }
        updateAfterProgress(nowNanos);
    }

    /** Marks a batch tile complete when equivalent fresh local coverage made a request unnecessary. */
    public synchronized void tileSettled(
        final int dimIndex, final int lod, final int tileX, final int tileZ, final long nowNanos
    ) {
        if (!matchesBatch(dimIndex, lod) || !pendingBatchTiles.remove(tileKey(tileX, tileZ))) {
            return;
        }
        updateAfterProgress(nowNanos);
    }

    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    public synchronized Snapshot snapshot(final long nowNanos) {
        if (snapshot.state() != State.SYNCING) {
            return snapshot;
        }
        return new Snapshot(
            State.SYNCING,
            completedTiles(),
            totalTiles,
            elapsedNanos(nowNanos),
            trafficBytes
        );
    }

    public synchronized void requestFailed(final int payloadBytes, final long nowNanos) {
        if (!started || snapshot.state() != State.SYNCING) {
            return;
        }
        inFlight.clear();
        trafficBytes += Math.max(0, payloadBytes);
        snapshot = new Snapshot(
            State.FAILED,
            completedTiles(),
            totalTiles,
            elapsedNanos(nowNanos),
            trafficBytes
        );
    }

    public synchronized void reset() {
        inFlight.clear();
        pendingBatchTiles.clear();
        pendingBatchRegions.clear();
        snapshot = Snapshot.IDLE;
        batchDimIndex = -1;
        batchLod = -1;
        totalTiles = 0;
        startedNanos = 0L;
        trafficBytes = 0L;
        started = false;
    }

    private void updateAfterProgress(final long nowNanos) {
        if (pendingBatchTiles.isEmpty() && pendingBatchRegions.isEmpty()) {
            inFlight.clear();
            snapshot = new Snapshot(
                State.COMPLETED,
                totalTiles,
                totalTiles,
                elapsedNanos(nowNanos),
                trafficBytes
            );
        } else {
            updateSyncing(nowNanos);
        }
    }

    private void updateSyncing(final long nowNanos) {
        snapshot = new Snapshot(
            State.SYNCING,
            completedTiles(),
            totalTiles,
            elapsedNanos(nowNanos),
            trafficBytes
        );
    }

    private boolean matchesBatch(final int dimIndex, final int lod) {
        return dimIndex == batchDimIndex && lod == batchLod;
    }

    private int completedTiles() {
        return totalTiles - pendingBatchTiles.size() - pendingBatchRegions.size();
    }

    private long elapsedNanos(final long nowNanos) {
        return started ? Math.max(0L, nowNanos - startedNanos) : 0L;
    }

    private static long tileKey(final int tileX, final int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }

    private record RegionKey(
        int regionX,
        int regionZ,
        int minLocalChunkX,
        int minLocalChunkZ,
        int maxLocalChunkX,
        int maxLocalChunkZ
    ) {
        private static RegionKey from(final ChunkRegionSlice slice) {
            return new RegionKey(
                slice.regionX(), slice.regionZ(),
                slice.minLocalChunkX(), slice.minLocalChunkZ(),
                slice.maxLocalChunkX(), slice.maxLocalChunkZ()
            );
        }
    }

    private static final class RequestProgress {
        private final int dimIndex;
        private final int lod;
        private final Set<Long> pendingTiles;
        private final Set<RegionKey> pendingRegions;

        private RequestProgress(
            final int dimIndex,
            final int lod,
            final Set<Long> pendingTiles,
            final Set<RegionKey> pendingRegions
        ) {
            this.dimIndex = dimIndex;
            this.lod = lod;
            this.pendingTiles = pendingTiles;
            this.pendingRegions = pendingRegions;
        }

        private static RequestProgress from(
            final MapViewReqC2S request, final Set<Long> pendingBatchTiles
        ) {
            final Set<Long> pendingTiles = new HashSet<>();
            for (final MapViewReqC2S.TileReq tile : request.tiles()) {
                final long key = tileKey(tile.tileX(), tile.tileZ());
                if (pendingBatchTiles.contains(key)) {
                    pendingTiles.add(key);
                }
            }
            return new RequestProgress(request.dimIndex(), request.lod(), pendingTiles, new HashSet<>());
        }

        private static RequestProgress from(
            final MapRegionViewReqC2S request, final Set<RegionKey> pendingBatchRegions
        ) {
            final Set<RegionKey> pendingRegions = new HashSet<>();
            for (final MapRegionViewReqC2S.RegionReq region : request.regions()) {
                final RegionKey key = RegionKey.from(region.slice());
                if (pendingBatchRegions.contains(key)) {
                    pendingRegions.add(key);
                }
            }
            return new RequestProgress(request.dimIndex(), request.lod(), new HashSet<>(), pendingRegions);
        }

        private boolean empty() {
            return pendingTiles.isEmpty() && pendingRegions.isEmpty();
        }
    }
}
