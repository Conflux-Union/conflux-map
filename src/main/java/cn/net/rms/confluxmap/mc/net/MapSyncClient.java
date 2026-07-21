package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.ViewRequestPlanner;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/** Client-side viewport debounce, request planning, and correction application. */
public final class MapSyncClient {
    /** Narrow send seam so the sync loop is testable without a live Fabric channel. */
    @FunctionalInterface
    interface Sender {
        int send(cn.net.rms.confluxmap.core.net.Message message);
    }

    /** Extra client-side spacing over the server's minimum, so arrival jitter cannot trip its rate limit. */
    private static final long REQUEST_INTERVAL_MARGIN_MS = 50L;
    /** After a server ERROR (rate limit, queue overflow), hold off so the byte budget can refill. */
    private static final long ERROR_BACKOFF_MS = 1_000L;
    /** A request silent for this long is considered dropped; its unanswered tiles become plannable again. */
    private static final long REQUEST_TIMEOUT_MS = 10_000L;
    /**
     * Outstanding-request window. Two full requests match the server's default 16-tile delivery
     * queue ({@code maxPendingTilesPerPlayer}), so the client never overflows it while still
     * hiding one round trip of latency.
     */
    private static final int MAX_INFLIGHT_REQUESTS = 2;

    private final CompanionSession companion;
    private final Sender sender;
    private final CorrectionStore corrections;
    private final PredictionTileService predictionTiles;
    private final ConfluxConfig config;
    private final LongSupplier millisClock;
    private final MapSyncProgress progress = new MapSyncProgress();
    private int nextReqId;
    private long stableSince = Long.MIN_VALUE;
    private long lastSent;
    private long suppressedUntil;
    private int lastLod = -1;
    private int lastMinX;
    private int lastMaxX;
    private int lastMinZ;
    private int lastMaxZ;
    /**
     * Request cooldown stamps, keyed by the same identity as the correction tiles they guard.
     * Tile coordinates alone are not unique across LODs (or dimensions): LOD-2 tile (1,1) and
     * LOD-1 tile (1,1) are different world areas, and a shared stamp let a zoom-2 browse push
     * numerically-colliding zoom-1 tiles into the long empty-tile cooldown, leaving them
     * predicted-only for minutes.
     */
    private final Map<TileStamp, Long> lastRequestNanos = new HashMap<>();
    /** Requests awaiting patches, keyed by reqId; tracks each tile's request stamp for rollback. */
    private final Map<Integer, InFlightRequest> inFlightRequests = new HashMap<>();

    private record TileStamp(String dimension, int lod, int tileX, int tileZ) {
    }

    private static final class InFlightRequest {
        final String dimension;
        final int lod;
        long lastActivityMs;
        final Map<TileStamp, Long> pendingStamps = new HashMap<>();

        InFlightRequest(final String dimension, final int lod, final long sentAtMs) {
            this.dimension = dimension;
            this.lod = lod;
            this.lastActivityMs = sentAtMs;
        }
    }

    public MapSyncClient(
        final CompanionSession companion,
        final ClientNetworking networking,
        final CorrectionStore corrections,
        final PredictionTileService predictionTiles,
        final ConfluxConfig config
    ) {
        this(companion, networking::sendMessage, corrections, predictionTiles, config, System::currentTimeMillis);
    }

    MapSyncClient(
        final CompanionSession companion,
        final Sender sender,
        final CorrectionStore corrections,
        final PredictionTileService predictionTiles,
        final ConfluxConfig config,
        final LongSupplier millisClock
    ) {
        this.companion = companion;
        this.sender = sender;
        this.corrections = corrections;
        this.predictionTiles = predictionTiles;
        this.config = config;
        this.millisClock = millisClock;
    }

    public synchronized void reportViewport(
        final DimensionId dimension, final int lod, final int minX, final int maxX, final int minZ, final int maxZ
    ) {
        if (!config.predictionNetworkSync || !companion.isActive() || !companion.policy().flags().correctionsEnabled()
            || lod > companion.policy().budgets().maxPatchLod()) {
            return;
        }
        final long now = millisClock.getAsLong();
        corrections.flushIfDue(now);
        expireStalledRequests(now);
        final boolean changed = lod != lastLod || minX != lastMinX || maxX != lastMaxX || minZ != lastMinZ || maxZ != lastMaxZ;
        if (changed) {
            stableSince = now;
            lastLod = lod;
            lastMinX = minX;
            lastMaxX = maxX;
            lastMinZ = minZ;
            lastMaxZ = maxZ;
            return;
        }
        final long debounce = Math.max(100L, Math.min(2000L, config.predictionDebounceMs));
        final long minInterval = companion.policy().budgets().minReqIntervalMs() + REQUEST_INTERVAL_MARGIN_MS;
        if (stableSince == Long.MIN_VALUE || now - stableSince < debounce || now - lastSent < minInterval
            || now < suppressedUntil || inFlightRequests.size() >= MAX_INFLIGHT_REQUESTS) {
            return;
        }
        final String dimensionId = dimension.toString();
        final List<ViewRequestPlanner.Tile> tiles = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                final CorrectionStore.Key key = new CorrectionStore.Key(dimensionId, lod, x, z);
                final cn.net.rms.confluxmap.core.predict.CorrectionTile tile = corrections.get(key);
                final byte[] presence = tile.presence();
                boolean empty = true;
                for (final byte value : presence) {
                    empty &= value == 0;
                }
                final Long previous = lastRequestNanos.get(new TileStamp(dimensionId, lod, x, z));
                tiles.add(new ViewRequestPlanner.Tile(x, z, tile.revision(), previous == null ? Long.MIN_VALUE : previous, empty));
            }
        }
        final int centerX = (minX + maxX) / 2;
        final int centerZ = (minZ + maxZ) / 2;
        final List<MapViewReqC2S.TileReq> planned = ViewRequestPlanner.plan(
            new ViewRequestPlanner.Viewport(minX, maxX, minZ, maxZ, centerX, centerZ), tiles,
            Math.min(Proto.MAX_TILES_PER_REQ, companion.policy().budgets().maxTilesPerReq()), now * 1_000_000L,
            60_000_000_000L, 600_000_000_000L
        );
        if (planned.isEmpty()) {
            return;
        }
        final int dimIndex = dimensionIndex(dimension);
        if (dimIndex < 0) {
            return;
        }
        final MapViewReqC2S request = new MapViewReqC2S(nextReqId++ & 0x7FFF, dimIndex, lod, planned);
        final int payloadBytes = sender.send(request);
        if (payloadBytes >= 0) {
            lastSent = now;
            progress.requestStarted(request, payloadBytes, System.nanoTime());
            final long requestNanos = now * 1_000_000L;
            final InFlightRequest inFlight = new InFlightRequest(dimensionId, lod, now);
            for (final MapViewReqC2S.TileReq tile : planned) {
                final TileStamp stamp = new TileStamp(inFlight.dimension, lod, tile.tileX(), tile.tileZ());
                lastRequestNanos.put(stamp, requestNanos);
                inFlight.pendingStamps.put(stamp, requestNanos);
            }
            inFlightRequests.put(request.reqId(), inFlight);
        }
    }

    public synchronized void onPatch(final MapPatchS2C patch, final int payloadBytes) {
        completeTile(patch.reqId(), patch.tileX(), patch.tileZ());
        try {
            applyPatch(patch);
        } finally {
            progress.patchReceived(patch, payloadBytes, System.nanoTime());
        }
    }

    private void applyPatch(final MapPatchS2C patch) {
        if (patch.mode() == Proto.PATCH_MODE_UNAVAILABLE || patch.mode() == Proto.PATCH_MODE_UNCHANGED) {
            final CorrectionStore.Key key = keyFor(patch);
            if (key != null) {
                corrections.apply(key, patch.tileRevision(), patch.presence(), new PatchCodec.Patch(List.of()));
            }
            return;
        }
        try {
            final PatchCodec.Patch decoded = PatchCodec.decode(patch.body());
            final CorrectionStore.Key key = keyFor(patch);
            if (key != null && predictionTiles.applyCorrection(key, patch.tileRevision(), patch.presence(), decoded)) {
                corrections.flush();
            }
        } catch (final ProtoException e) {
            ConfluxMapMod.LOGGER.warn("companion: malformed MAP_PATCH body ({})", e.getMessage());
        }
    }

    public synchronized void reset() {
        lastRequestNanos.clear();
        inFlightRequests.clear();
        stableSince = Long.MIN_VALUE;
        lastSent = 0L;
        suppressedUntil = 0L;
        lastLod = -1;
        progress.reset();
    }

    public MapSyncProgress.Snapshot status() {
        return progress.snapshot();
    }

    /**
     * A server ERROR means at least the tail of an in-flight request was dropped (rate limit or
     * bandwidth break). Roll back the request stamps of every unanswered tile so the planner may
     * pick them again, and back off briefly so the server budget can refill. Without the rollback,
     * dropped unexplored tiles sat in the planner's long empty-tile cooldown and large viewports
     * never finished syncing.
     */
    public synchronized void onError(final int payloadBytes) {
        rollbackPendingTiles();
        suppressedUntil = millisClock.getAsLong() + ERROR_BACKOFF_MS;
        progress.requestFailed(payloadBytes, System.nanoTime());
    }

    private void completeTile(final int reqId, final int tileX, final int tileZ) {
        final InFlightRequest request = inFlightRequests.get(reqId);
        if (request == null) {
            return;
        }
        request.lastActivityMs = millisClock.getAsLong();
        request.pendingStamps.remove(new TileStamp(request.dimension, request.lod, tileX, tileZ));
        if (request.pendingStamps.isEmpty()) {
            inFlightRequests.remove(reqId);
        }
    }

    private void rollbackPendingTiles() {
        for (final InFlightRequest request : inFlightRequests.values()) {
            unstamp(request);
        }
        inFlightRequests.clear();
    }

    private void expireStalledRequests(final long now) {
        final java.util.Iterator<InFlightRequest> pending = inFlightRequests.values().iterator();
        while (pending.hasNext()) {
            final InFlightRequest request = pending.next();
            if (now - request.lastActivityMs >= REQUEST_TIMEOUT_MS) {
                unstamp(request);
                pending.remove();
            }
        }
    }

    /** Clears each pending tile's stamp unless a newer request has already re-stamped it. */
    private void unstamp(final InFlightRequest request) {
        for (final Map.Entry<TileStamp, Long> entry : request.pendingStamps.entrySet()) {
            lastRequestNanos.remove(entry.getKey(), entry.getValue());
        }
    }

    private CorrectionStore.Key keyFor(final MapPatchS2C patch) {
        final CompanionSession session = companion;
        if (!session.isActive() || patch.dimIndex() < 0 || patch.dimIndex() >= session.policy().dims().size()) {
            return null;
        }
        return new CorrectionStore.Key(session.policy().dims().get(patch.dimIndex()).dimId(), patch.lod(), patch.tileX(), patch.tileZ());
    }

    private int dimensionIndex(final DimensionId dimension) {
        for (int i = 0; i < companion.policy().dims().size(); i++) {
            if (dimension.toString().equals(companion.policy().dims().get(i).dimId())) {
                return i;
            }
        }
        return -1;
    }
}
