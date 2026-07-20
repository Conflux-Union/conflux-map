package cn.net.rms.confluxmap.core.net;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** Tracks one user-visible server correction sync across one or more in-flight requests. */
public final class MapSyncProgress {
    public enum State { IDLE, SYNCING, COMPLETED, FAILED }

    public record Snapshot(State state, long durationNanos, long trafficBytes) {
        public static final Snapshot IDLE = new Snapshot(State.IDLE, 0L, 0L);
    }

    private final Map<Integer, RequestProgress> inFlight = new HashMap<>();
    private Snapshot snapshot = Snapshot.IDLE;
    private long startedNanos;
    private long trafficBytes;

    public synchronized void requestStarted(
        final MapViewReqC2S request, final int payloadBytes, final long nowNanos
    ) {
        final RequestProgress requestProgress = RequestProgress.from(request);
        if (requestProgress.pendingTiles.isEmpty()) {
            return;
        }
        if (inFlight.isEmpty()) {
            startedNanos = nowNanos;
            trafficBytes = 0L;
        }
        inFlight.put(request.reqId(), requestProgress);
        trafficBytes += Math.max(0, payloadBytes);
        snapshot = new Snapshot(State.SYNCING, 0L, trafficBytes);
    }

    public synchronized void patchReceived(
        final MapPatchS2C patch, final int payloadBytes, final long nowNanos
    ) {
        final RequestProgress request = inFlight.get(patch.reqId());
        if (request == null || request.dimIndex != patch.dimIndex() || request.lod != patch.lod()
            || !request.pendingTiles.remove(tileKey(patch.tileX(), patch.tileZ()))) {
            return;
        }
        trafficBytes += Math.max(0, payloadBytes);
        if (request.pendingTiles.isEmpty()) {
            inFlight.remove(patch.reqId());
        }
        if (inFlight.isEmpty()) {
            snapshot = new Snapshot(State.COMPLETED, Math.max(0L, nowNanos - startedNanos), trafficBytes);
        } else {
            snapshot = new Snapshot(State.SYNCING, 0L, trafficBytes);
        }
    }

    public synchronized Snapshot snapshot() {
        return snapshot;
    }

    public synchronized void requestFailed(final int payloadBytes, final long nowNanos) {
        if (inFlight.isEmpty()) {
            return;
        }
        inFlight.clear();
        trafficBytes += Math.max(0, payloadBytes);
        snapshot = new Snapshot(State.FAILED, Math.max(0L, nowNanos - startedNanos), trafficBytes);
    }

    public synchronized void reset() {
        inFlight.clear();
        snapshot = Snapshot.IDLE;
        startedNanos = 0L;
        trafficBytes = 0L;
    }

    private static long tileKey(final int tileX, final int tileZ) {
        return ((long) tileX << 32) ^ (tileZ & 0xFFFFFFFFL);
    }

    private static final class RequestProgress {
        private final int dimIndex;
        private final int lod;
        private final Set<Long> pendingTiles;

        private RequestProgress(final int dimIndex, final int lod, final Set<Long> pendingTiles) {
            this.dimIndex = dimIndex;
            this.lod = lod;
            this.pendingTiles = pendingTiles;
        }

        private static RequestProgress from(final MapViewReqC2S request) {
            final Set<Long> pendingTiles = new HashSet<>();
            for (final MapViewReqC2S.TileReq tile : request.tiles()) {
                pendingTiles.add(tileKey(tile.tileX(), tile.tileZ()));
            }
            return new RequestProgress(request.dimIndex(), request.lod(), pendingTiles);
        }
    }
}
