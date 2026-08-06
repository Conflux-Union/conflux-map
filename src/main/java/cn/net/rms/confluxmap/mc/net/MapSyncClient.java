package cn.net.rms.confluxmap.mc.net;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.net.ChunkPatchCodec;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.MapSyncCompatibility;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.PatchCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.predict.CorrectionStore;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.ViewRequestPlanner;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.util.TileMath;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
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
    /** Coarse scans publish lightweight progress; retry them well below the normal request rate. */
    private static final long PARTIAL_RETRY_INTERVAL_MS = 2_000L;
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
    private final Consumer<Iterable<PatchCodec.Sample>> materialRegistrar;
    private final MapSyncProgress progress = new MapSyncProgress();
    private int nextReqId;
    private long stableSince = Long.MIN_VALUE;
    private boolean batchPrepared;
    private long lastSent;
    private long suppressedUntil;
    private int lastLod = -1;
    private int lastMinX;
    private int lastMaxX;
    private int lastMinZ;
    private int lastMaxZ;
    private String lastDimension;
    private int lastDimIndex = -1;
    /**
     * Request cooldown stamps, keyed by the same identity as the correction tiles they guard.
     * Tile coordinates alone are not unique across LODs (or dimensions): LOD-2 tile (1,1) and
     * LOD-1 tile (1,1) are different world areas, and a shared stamp let a zoom-2 browse push
     * numerically-colliding zoom-1 tiles into the long empty-tile cooldown, leaving them
     * predicted-only for minutes.
     */
    private final Map<TileStamp, Long> lastRequestNanos = new HashMap<>();
    private final Map<TileStamp, Long> partialRetryAfterMillis = new HashMap<>();
    /** Final answers stay reusable until the viewport re-enters stale data or the server invalidates them. */
    private final Set<TileStamp> settledTiles = new HashSet<>();
    /** Server-invalidated tiles bypass cross-LOD reuse until their replacement patch is final. */
    private final Set<TileStamp> invalidatedTiles = new HashSet<>();
    /** Requests awaiting patches, keyed by reqId; tracks each tile's request stamp for rollback. */
    private final Map<Integer, InFlightRequest> inFlightRequests = new HashMap<>();
    private final Map<Integer, RegionInFlightRequest> regionInFlightRequests = new HashMap<>();
    private final Set<RegionStamp> regionInFlightStamps = new HashSet<>();
    private final Set<RegionStamp> settledRegions = new HashSet<>();
    private final Set<RegionStamp> invalidatedRegions = new HashSet<>();
    private ChunkViewport lastChunkViewport;
    private ChunkViewport lastExcludedChunkViewport;

    private record TileStamp(String dimension, int lod, int tileX, int tileZ) {
    }

    private record RegionStamp(String dimension, int lod, ChunkRegionSlice slice) {
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

    private static final class RegionInFlightRequest {
        final String dimension;
        final int dimIndex;
        final int lod;
        long lastActivityMs;
        final Set<RegionStamp> pending = new HashSet<>();

        RegionInFlightRequest(
            final String dimension, final int dimIndex, final int lod, final long sentAtMs
        ) {
            this.dimension = dimension;
            this.dimIndex = dimIndex;
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
        this(
            companion, networking::sendMessage, corrections, predictionTiles, config,
            System::currentTimeMillis, ignored -> { }
        );
    }

    public MapSyncClient(
        final CompanionSession companion,
        final ClientNetworking networking,
        final CorrectionStore corrections,
        final PredictionTileService predictionTiles,
        final ConfluxConfig config,
        final Consumer<Iterable<PatchCodec.Sample>> materialRegistrar
    ) {
        this(
            companion, networking::sendMessage, corrections, predictionTiles, config,
            System::currentTimeMillis, materialRegistrar
        );
    }

    MapSyncClient(
        final CompanionSession companion,
        final Sender sender,
        final CorrectionStore corrections,
        final PredictionTileService predictionTiles,
        final ConfluxConfig config,
        final LongSupplier millisClock
    ) {
        this(
            companion, sender, corrections, predictionTiles, config, millisClock,
            ignored -> { }
        );
    }

    MapSyncClient(
        final CompanionSession companion,
        final Sender sender,
        final CorrectionStore corrections,
        final PredictionTileService predictionTiles,
        final ConfluxConfig config,
        final LongSupplier millisClock,
        final Consumer<Iterable<PatchCodec.Sample>> materialRegistrar
    ) {
        this.companion = companion;
        this.sender = sender;
        this.corrections = corrections;
        this.predictionTiles = predictionTiles;
        this.config = config;
        this.millisClock = millisClock;
        this.materialRegistrar = materialRegistrar == null ? ignored -> { } : materialRegistrar;
    }

    /** Requests every map LOD; high LOD corrections arrive as bounded progressive patches. */
    public synchronized void reportViewport(
        final DimensionId dimension, final int lod, final int minX, final int maxX, final int minZ, final int maxZ
    ) {
        if (!config.predictionNetworkSync || !companion.isActive() || !companion.policy().flags().correctionsEnabled()
            || lod < 0 || lod > TileMath.MAX_LOD) {
            return;
        }
        final long now = millisClock.getAsLong();
        corrections.flushIfDue(now);
        expireStalledRequests(now);
        final String dimensionId = dimension.toString();
        final boolean changed = !dimensionId.equals(lastDimension)
            || lod != lastLod || minX != lastMinX || maxX != lastMaxX || minZ != lastMinZ || maxZ != lastMaxZ;
        if (changed) {
            batchPrepared = false;
            progress.reset();
            prepareNewlyVisibleTiles(dimension, dimensionId, lod, minX, maxX, minZ, maxZ, now);
            retainViewportState(dimensionId, lod, minX, maxX, minZ, maxZ);
            stableSince = now;
            lastDimIndex = dimensionIndex(dimension);
            lastDimension = dimensionId;
            lastLod = lod;
            lastMinX = minX;
            lastMaxX = maxX;
            lastMinZ = minZ;
            lastMaxZ = maxZ;
            subscribeViewport(lod, minX, maxX, minZ, maxZ);
            return;
        }
        final long debounce = Math.max(100L, Math.min(2000L, config.predictionDebounceMs));
        final long minInterval = companion.policy().budgets().minReqIntervalMs() + REQUEST_INTERVAL_MARGIN_MS;
        if (stableSince == Long.MIN_VALUE || now - stableSince < debounce || now - lastSent < minInterval
            || now < suppressedUntil || inFlightRequests.size() >= MAX_INFLIGHT_REQUESTS) {
            return;
        }
        final int dimIndex = dimensionIndex(dimension);
        if (dimIndex < 0) {
            return;
        }
        final List<ViewRequestPlanner.Tile> tiles = new ArrayList<>();
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                final TileStamp stamp = new TileStamp(dimensionId, lod, x, z);
                if (settledTiles.contains(stamp)) {
                    continue;
                }
                final Long partialRetryAfter = partialRetryAfterMillis.get(stamp);
                if (partialRetryAfter != null) {
                    if (now < partialRetryAfter) {
                        continue;
                    }
                    partialRetryAfterMillis.remove(stamp);
                }
                if (!invalidatedTiles.contains(stamp)) {
                    final PredictionTileService.LowerCoverageState coverage =
                        predictionTiles.prepareFreshLowerCoverage(dimension, lod, x, z, now);
                    if (coverage == PredictionTileService.LowerCoverageState.READY) {
                        settledTiles.add(stamp);
                        progress.tileSettled(dimIndex, lod, x, z, System.nanoTime());
                        continue;
                    }
                    if (coverage == PredictionTileService.LowerCoverageState.PENDING) {
                        continue;
                    }
                }
                final CorrectionStore.Key key = new CorrectionStore.Key(dimensionId, lod, x, z);
                final cn.net.rms.confluxmap.core.predict.CorrectionTile tile = corrections.get(key);
                materialRegistrar.accept(tile.copyPatch().samples());
                final byte[] presence = tile.presence();
                boolean empty = true;
                for (final byte value : presence) {
                    empty &= value == 0;
                }
                final Long previous = lastRequestNanos.get(stamp);
                final long snapshotRevision = tile.hasTileSnapshot()
                    && tile.matchesSource(
                        activePatchMode(), activeBaselineProfile(), activeCorrectionProfile()
                    )
                        ? tile.revision() : Long.MIN_VALUE;
                tiles.add(new ViewRequestPlanner.Tile(
                    x, z, snapshotRevision, previous == null ? Long.MIN_VALUE : previous, empty
                ));
            }
        }
        if (!batchPrepared) {
            final List<MapSyncProgress.BatchTile> batchTiles = new ArrayList<>(tiles.size());
            for (final ViewRequestPlanner.Tile tile : tiles) {
                batchTiles.add(new MapSyncProgress.BatchTile(tile.tileX(), tile.tileZ()));
            }
            progress.beginBatch(dimIndex, lod, batchTiles);
            batchPrepared = true;
        }
        final int centerX = (minX + maxX) / 2;
        final int centerZ = (minZ + maxZ) / 2;
        final List<MapViewReqC2S.TileReq> planned = ViewRequestPlanner.plan(
            new ViewRequestPlanner.Viewport(minX, maxX, minZ, maxZ, centerX, centerZ), tiles,
            Math.min(Proto.MAX_TILES_PER_REQ, companion.policy().budgets().maxTilesPerReq()), now * 1_000_000L,
            60_000_000_000L,
            600_000_000_000L
        );
        if (planned.isEmpty()) {
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

    /** Exact chunk-aware companion path; tile bounds remain only the renderer's texture viewport. */
    public synchronized void reportViewport(
        final DimensionId dimension,
        final int lod,
        final int minTileX,
        final int maxTileX,
        final int minTileZ,
        final int maxTileZ,
        final ChunkViewport chunks
    ) {
        reportViewport(
            dimension, lod, minTileX, maxTileX, minTileZ, maxTileZ, chunks, null
        );
    }

    /** Chunk-aware path with a player-view rectangle that stays locally authoritative. */
    public synchronized void reportViewport(
        final DimensionId dimension,
        final int lod,
        final int minTileX,
        final int maxTileX,
        final int minTileZ,
        final int maxTileZ,
        final ChunkViewport chunks,
        final ChunkViewport excludedChunks
    ) {
        final HelloPolicyS2C policy = companion.policy();
        if (chunks == null || policy == null || !policy.flags().chunkRangeCorrectionEnabled()) {
            reportViewport(dimension, lod, minTileX, maxTileX, minTileZ, maxTileZ);
            return;
        }
        reportRegionViewport(dimension, lod, chunks, excludedChunks);
    }

    private void reportRegionViewport(
        final DimensionId dimension,
        final int lod,
        final ChunkViewport chunks,
        final ChunkViewport excludedChunks
    ) {
        if (!config.predictionNetworkSync || !companion.isActive()
            || !companion.policy().flags().correctionsEnabled()
            || lod < 0 || lod > TileMath.MAX_LOD) {
            return;
        }
        final long now = millisClock.getAsLong();
        corrections.flushIfDue(now);
        expireStalledRegionRequests(now);
        final String dimensionId = dimension.toString();
        final boolean changed = !dimensionId.equals(lastDimension)
            || lod != lastLod || !chunks.equals(lastChunkViewport)
            || !java.util.Objects.equals(excludedChunks, lastExcludedChunkViewport);
        final int dimIndex = dimensionIndex(dimension);
        if (dimIndex < 0) {
            return;
        }
        if (changed) {
            batchPrepared = false;
            progress.reset();
            stableSince = now;
            lastDimIndex = dimIndex;
            lastDimension = dimensionId;
            lastLod = lod;
            lastChunkViewport = chunks;
            lastExcludedChunkViewport = excludedChunks;
            final Set<ChunkRegionSlice> visible = new HashSet<>(visibleRegionSlices(
                chunks, excludedChunks
            ));
            settledRegions.removeIf(stamp -> !stamp.dimension().equals(dimensionId)
                || stamp.lod() != lod || !visible.contains(stamp.slice()));
            invalidatedRegions.removeIf(stamp -> !stamp.dimension().equals(dimensionId)
                || stamp.lod() != lod || !visible.contains(stamp.slice()));
            sender.send(new MapRegionSyncSubscribeC2S(
                dimIndex, lod, true,
                chunks.minChunkX(), chunks.maxChunkX(), chunks.minChunkZ(), chunks.maxChunkZ()
            ));
            return;
        }
        final long debounce = Math.max(100L, Math.min(2000L, config.predictionDebounceMs));
        final long minInterval = companion.policy().budgets().minReqIntervalMs() + REQUEST_INTERVAL_MARGIN_MS;
        if (stableSince == Long.MIN_VALUE || now - stableSince < debounce || now - lastSent < minInterval
            || now < suppressedUntil || regionInFlightRequests.size() >= MAX_INFLIGHT_REQUESTS) {
            return;
        }
        final List<ChunkRegionSlice> candidates = new ArrayList<>();
        for (final ChunkRegionSlice slice : visibleRegionSlices(chunks, excludedChunks)) {
            final RegionStamp stamp = new RegionStamp(dimensionId, lod, slice);
            if (settledRegions.contains(stamp) || regionInFlightStamps.contains(stamp)) {
                continue;
            }
            if (!invalidatedRegions.contains(stamp) && corrections.regionSliceFreshAt(
                dimensionId,
                lod,
                slice,
                now,
                PredictionTileService.CORRECTION_REUSE_TTL_MS,
                activePatchMode(),
                activeBaselineProfile(),
                activeCorrectionProfile()
            )) {
                settledRegions.add(stamp);
                continue;
            }
            candidates.add(slice);
        }
        if (candidates.isEmpty()) {
            return;
        }
        if (!batchPrepared) {
            progress.beginRegionBatch(dimIndex, lod, candidates);
            batchPrepared = true;
        }
        final long centerChunkX = ((long) chunks.minChunkX() + chunks.maxChunkX()) / 2L;
        final long centerChunkZ = ((long) chunks.minChunkZ() + chunks.maxChunkZ()) / 2L;
        candidates.sort(java.util.Comparator.comparingLong(slice -> {
            final long dx = (long) slice.minChunkX() + slice.width() / 2L - centerChunkX;
            final long dz = (long) slice.minChunkZ() + slice.height() / 2L - centerChunkZ;
            return dx * dx + dz * dz;
        }));
        final int count = Math.min(
            Math.min(
                Proto.MAX_REGION_PAGES_PER_REQ,
                companion.policy().budgets().maxTilesPerReq()
            ),
            candidates.size()
        );
        final List<MapRegionViewReqC2S.RegionReq> regions = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            final ChunkRegionSlice slice = candidates.get(i);
            regions.add(new MapRegionViewReqC2S.RegionReq(
                slice,
                corrections.regionSliceRevision(
                    dimensionId, lod, slice, activePatchMode(), activeBaselineProfile(),
                    activeCorrectionProfile()
                )
            ));
        }
        final MapRegionViewReqC2S request = new MapRegionViewReqC2S(
            nextReqId++ & 0x7FFF, dimIndex, lod, regions
        );
        final int payloadBytes = sender.send(request);
        if (payloadBytes < 0) {
            return;
        }
        lastSent = now;
        progress.requestStarted(request, payloadBytes, System.nanoTime());
        final RegionInFlightRequest inFlight = new RegionInFlightRequest(
            dimensionId, dimIndex, lod, now
        );
        for (final MapRegionViewReqC2S.RegionReq region : regions) {
            final RegionStamp stamp = new RegionStamp(dimensionId, lod, region.slice());
            inFlight.pending.add(stamp);
            regionInFlightStamps.add(stamp);
        }
        regionInFlightRequests.put(request.reqId(), inFlight);
    }

    public synchronized void onPatch(final MapPatchS2C patch, final int payloadBytes) {
        if (!completeTile(patch)) {
            return;
        }
        boolean accepted = false;
        try {
            accepted = applyPatch(patch);
            if (patch.mode() == Proto.PATCH_MODE_PARTIAL) {
                allowPartialRetry(patch);
            } else if (accepted) {
                settle(patch);
            }
        } finally {
            final long nowNanos = System.nanoTime();
            progress.patchReceived(patch, payloadBytes, nowNanos);
            if (accepted && patch.mode() != Proto.PATCH_MODE_PARTIAL) {
                progress.tileSettled(patch.dimIndex(), patch.lod(), patch.tileX(), patch.tileZ(), nowNanos);
            }
        }
    }

    public synchronized void onRegionPatch(
        final MapRegionPatchS2C patch, final int payloadBytes
    ) {
        final RegionInFlightRequest request = regionInFlightRequests.get(patch.reqId());
        if (request == null || request.dimIndex != patch.dimIndex() || request.lod != patch.lod()) {
            return;
        }
        final RegionStamp stamp = new RegionStamp(request.dimension, request.lod, patch.slice());
        if (!request.pending.remove(stamp)) {
            return;
        }
        regionInFlightStamps.remove(stamp);
        request.lastActivityMs = millisClock.getAsLong();
        if (request.pending.isEmpty()) {
            regionInFlightRequests.remove(patch.reqId());
        }
        boolean accepted = false;
        if (patch.mode() == Proto.PATCH_MODE_UNAVAILABLE) {
            accepted = true;
        } else if (patch.mode() == Proto.PATCH_MODE_UNCHANGED) {
            accepted = predictionTiles.validateRegionCorrection(
                request.dimension, patch.lod(), patch.slice(),
                patch.regionRevision(), millisClock.getAsLong(), activeCorrectionProfile()
            );
        } else if (patch.mode() == Proto.PATCH_MODE_RESIDUAL
            || patch.mode() == Proto.PATCH_MODE_ABSOLUTE) {
            try {
                final ChunkPatchCodec.Patch decoded = ChunkPatchCodec.decode(patch.body());
                materialRegistrar.accept(decoded.samples());
                if (ChunkPatchCodec.regionRevision(
                    patch.lod(), patch.slice(), decoded, activeCorrectionProfile()
                )
                    == patch.regionRevision()) {
                    accepted = predictionTiles.applyRegionCorrection(
                        request.dimension, patch.lod(), patch.slice(), decoded,
                        patch.mode(), baselineProfile(patch.mode()), millisClock.getAsLong(),
                        activeCorrectionProfile()
                    );
                }
            } catch (final ProtoException | IllegalArgumentException e) {
                ConfluxMapMod.LOGGER.warn(
                    "companion: malformed MAP_REGION_PATCH body ({})", e.getMessage()
                );
            }
        }
        if (accepted) {
            invalidatedRegions.remove(stamp);
            settledRegions.add(stamp);
        }
        progress.regionPatchReceived(patch, payloadBytes, accepted, System.nanoTime());
    }

    private boolean applyPatch(final MapPatchS2C patch) {
        if (patch.mode() == Proto.PATCH_MODE_UNAVAILABLE) {
            return keyFor(patch) != null;
        }
        if (patch.mode() == Proto.PATCH_MODE_UNCHANGED) {
            final CorrectionStore.Key key = keyFor(patch);
            if (key == null || !predictionTiles.validateCorrection(
                key, patch.tileRevision(), patch.presence(), millisClock.getAsLong(),
                activeCorrectionProfile()
            )) {
                return false;
            }
            corrections.flush();
            return true;
        }
        try {
            final PatchCodec.Patch decoded = PatchCodec.decode(patch.body());
            materialRegistrar.accept(decoded.samples());
            final CorrectionStore.Key key = keyFor(patch);
            if (key == null) {
                return false;
            }
            if (patch.mode() == Proto.PATCH_MODE_PARTIAL) {
                predictionTiles.applyPartialCorrection(key, patch.presence(), decoded);
                return true;
            }
            if (!predictionTiles.applyCorrection(
                key, patch.tileRevision(), patch.presence(), decoded,
                patch.mode(), baselineProfile(patch.mode()), millisClock.getAsLong(),
                activeCorrectionProfile()
            )) {
                return false;
            }
            corrections.flush();
            return true;
        } catch (final ProtoException | IllegalArgumentException e) {
            ConfluxMapMod.LOGGER.warn("companion: malformed MAP_PATCH body ({})", e.getMessage());
            return false;
        }
    }

    private String baselineProfile(final int patchMode) {
        return patchMode == Proto.PATCH_MODE_RESIDUAL
            ? companion.mapSyncBaselineProfile() : "";
    }

    private int activePatchMode() {
        return companion.mapSyncMode() == MapSyncCompatibility.ClientMode.COMPATIBLE_ABSOLUTE
            ? Proto.PATCH_MODE_ABSOLUTE : Proto.PATCH_MODE_RESIDUAL;
    }

    private String activeBaselineProfile() {
        return activePatchMode() == Proto.PATCH_MODE_RESIDUAL
            ? companion.mapSyncBaselineProfile() : "";
    }

    private CorrectionProfile activeCorrectionProfile() {
        return companion.mapSyncCorrectionProfile();
    }

    /** Progressive revision-0 patches must remain plannable on the next normal request interval. */
    private void allowPartialRetry(final MapPatchS2C patch) {
        final CorrectionStore.Key key = keyFor(patch);
        if (key != null) {
            final TileStamp stamp = new TileStamp(key.dimension(), key.lod(), key.tileX(), key.tileZ());
            lastRequestNanos.remove(stamp);
            partialRetryAfterMillis.put(
                stamp,
                millisClock.getAsLong() + PARTIAL_RETRY_INTERVAL_MS
            );
        }
    }

    private void settle(final MapPatchS2C patch) {
        final CorrectionStore.Key key = keyFor(patch);
        if (key != null) {
            final TileStamp stamp = new TileStamp(key.dimension(), key.lod(), key.tileX(), key.tileZ());
            invalidatedTiles.remove(stamp);
            partialRetryAfterMillis.remove(stamp);
            settledTiles.add(stamp);
        }
    }

    /** Marks subscribed tiles stale for refresh without restarting the visible viewport batch. */
    public synchronized void onInvalidation(final MapInvalidateS2C invalidation) {
        final HelloPolicyS2C policy = companion.policy();
        if (!companion.isActive() || policy == null
            || invalidation.dimIndex() < 0 || invalidation.dimIndex() >= policy.dims().size()
            || invalidation.dimIndex() != lastDimIndex || invalidation.lod() != lastLod) {
            return;
        }
        final String dimension = policy.dims().get(invalidation.dimIndex()).dimId();
        final List<CorrectionStore.Key> invalidatedKeys = new ArrayList<>();
        for (final MapInvalidateS2C.Tile tile : invalidation.tiles()) {
            if (tile.tileX() < lastMinX || tile.tileX() > lastMaxX
                || tile.tileZ() < lastMinZ || tile.tileZ() > lastMaxZ) {
                continue;
            }
            final TileStamp stamp = new TileStamp(dimension, invalidation.lod(), tile.tileX(), tile.tileZ());
            invalidatedKeys.add(new CorrectionStore.Key(
                dimension, invalidation.lod(), tile.tileX(), tile.tileZ()
            ));
            settledTiles.remove(stamp);
            lastRequestNanos.remove(stamp);
            partialRetryAfterMillis.remove(stamp);
            invalidatedTiles.add(stamp);
        }
        predictionTiles.invalidateCorrectionValidations(invalidatedKeys);
    }

    public synchronized void onRegionInvalidation(final MapRegionInvalidateS2C invalidation) {
        final HelloPolicyS2C policy = companion.policy();
        if (!companion.isActive() || policy == null || lastChunkViewport == null
            || invalidation.dimIndex() < 0 || invalidation.dimIndex() >= policy.dims().size()
            || invalidation.dimIndex() != lastDimIndex || invalidation.lod() != lastLod) {
            return;
        }
        final String dimension = policy.dims().get(invalidation.dimIndex()).dimId();
        final Map<Long, List<ChunkRegionSlice>> visible = new HashMap<>();
        for (final ChunkRegionSlice slice : visibleRegionSlices(
            lastChunkViewport, lastExcludedChunkViewport
        )) {
            visible.computeIfAbsent(
                regionKey(slice.regionX(), slice.regionZ()), ignored -> new ArrayList<>()
            ).add(slice);
        }
        for (final MapRegionInvalidateS2C.Region region : invalidation.regions()) {
            final List<ChunkRegionSlice> slices = visible.get(
                regionKey(region.regionX(), region.regionZ())
            );
            if (slices == null) {
                continue;
            }
            for (final ChunkRegionSlice slice : slices) {
                final RegionStamp stamp = new RegionStamp(dimension, invalidation.lod(), slice);
                settledRegions.remove(stamp);
                invalidatedRegions.add(stamp);
                predictionTiles.invalidateRegionCorrection(dimension, invalidation.lod(), slice);
            }
        }
    }

    /** Stops server-side source watching when the predicted map viewport is no longer active. */
    public synchronized void clearViewport() {
        final HelloPolicyS2C policy = companion.policy();
        if (lastDimIndex >= 0 && lastLod >= 0 && companion.isActive() && policy != null
            && lastChunkViewport != null && policy.flags().chunkRangeCorrectionEnabled()) {
            sender.send(new MapRegionSyncSubscribeC2S(
                lastDimIndex, lastLod, false, 0, 0, 0, 0
            ));
        } else if (lastDimIndex >= 0 && lastLod >= 0 && companion.isActive() && policy != null
            && policy.flags().correctionInvalidationEnabled()) {
            sender.send(new MapSyncSubscribeC2S(lastDimIndex, lastLod, false, 0, 0, 0, 0));
        }
        stableSince = Long.MIN_VALUE;
        batchPrepared = false;
        progress.reset();
        settledTiles.clear();
        invalidatedTiles.clear();
        partialRetryAfterMillis.clear();
        settledRegions.clear();
        invalidatedRegions.clear();
        regionInFlightStamps.clear();
        regionInFlightRequests.clear();
        lastChunkViewport = null;
        lastExcludedChunkViewport = null;
        lastDimension = null;
        lastDimIndex = -1;
        lastLod = -1;
    }

    private void subscribeViewport(
        final int lod, final int minX, final int maxX, final int minZ, final int maxZ
    ) {
        final HelloPolicyS2C policy = companion.policy();
        if (lastDimIndex >= 0 && policy != null && policy.flags().correctionInvalidationEnabled()) {
            sender.send(new MapSyncSubscribeC2S(lastDimIndex, lod, true, minX, maxX, minZ, maxZ));
        }
    }

    private void prepareNewlyVisibleTiles(
        final DimensionId dimension,
        final String dimensionId,
        final int lod,
        final int minX,
        final int maxX,
        final int minZ,
        final int maxZ,
        final long now
    ) {
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                if (dimensionId.equals(lastDimension) && lod == lastLod
                    && x >= lastMinX && x <= lastMaxX && z >= lastMinZ && z <= lastMaxZ) {
                    continue;
                }
                final TileStamp stamp = new TileStamp(dimensionId, lod, x, z);
                final cn.net.rms.confluxmap.core.predict.CorrectionTile tile = corrections.get(
                    new CorrectionStore.Key(dimensionId, lod, x, z)
                );
                materialRegistrar.accept(tile.copyPatch().samples());
                final boolean directFresh = tile.matchesSource(
                    activePatchMode(), activeBaselineProfile(), activeCorrectionProfile()
                ) && tile.isFreshAt(now, PredictionTileService.CORRECTION_REUSE_TTL_MS);
                final PredictionTileService.LowerCoverageState lowerCoverage =
                    invalidatedTiles.contains(stamp) || directFresh
                        ? PredictionTileService.LowerCoverageState.MISSING_OR_STALE
                        : predictionTiles.prepareFreshLowerCoverage(dimension, lod, x, z, now);
                if (!invalidatedTiles.contains(stamp)
                    && (directFresh
                    || lowerCoverage == PredictionTileService.LowerCoverageState.READY)) {
                    settledTiles.add(stamp);
                } else {
                    settledTiles.remove(stamp);
                    if (lowerCoverage != PredictionTileService.LowerCoverageState.PENDING) {
                        lastRequestNanos.remove(stamp);
                    }
                }
            }
        }
    }

    private void retainViewportState(
        final String dimension,
        final int lod,
        final int minX,
        final int maxX,
        final int minZ,
        final int maxZ
    ) {
        settledTiles.removeIf(stamp -> !stamp.dimension().equals(dimension) || stamp.lod() != lod
            || stamp.tileX() < minX || stamp.tileX() > maxX
            || stamp.tileZ() < minZ || stamp.tileZ() > maxZ);
        invalidatedTiles.removeIf(stamp -> !stamp.dimension().equals(dimension) || stamp.lod() != lod
            || stamp.tileX() < minX || stamp.tileX() > maxX
            || stamp.tileZ() < minZ || stamp.tileZ() > maxZ);
        partialRetryAfterMillis.keySet().removeIf(stamp -> !stamp.dimension().equals(dimension) || stamp.lod() != lod
            || stamp.tileX() < minX || stamp.tileX() > maxX
            || stamp.tileZ() < minZ || stamp.tileZ() > maxZ);
    }

    public synchronized void reset() {
        lastRequestNanos.clear();
        settledTiles.clear();
        invalidatedTiles.clear();
        partialRetryAfterMillis.clear();
        inFlightRequests.clear();
        regionInFlightRequests.clear();
        regionInFlightStamps.clear();
        settledRegions.clear();
        invalidatedRegions.clear();
        lastChunkViewport = null;
        lastExcludedChunkViewport = null;
        stableSince = Long.MIN_VALUE;
        batchPrepared = false;
        lastSent = 0L;
        suppressedUntil = 0L;
        lastLod = -1;
        lastDimension = null;
        lastDimIndex = -1;
        progress.reset();
    }

    private static List<ChunkRegionSlice> visibleRegionSlices(
        final ChunkViewport chunks, final ChunkViewport excludedChunks
    ) {
        return chunks.regionSlicesExcluding(excludedChunks);
    }

    public MapSyncProgress.Snapshot status() {
        return progress.snapshot(System.nanoTime());
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
        rollbackPendingRegions();
        suppressedUntil = millisClock.getAsLong() + ERROR_BACKOFF_MS;
        progress.requestFailed(payloadBytes, System.nanoTime());
    }

    private boolean completeTile(final MapPatchS2C patch) {
        final InFlightRequest request = inFlightRequests.get(patch.reqId());
        if (request == null) {
            return false;
        }
        final CorrectionStore.Key key = keyFor(patch);
        if (key == null || !request.dimension.equals(key.dimension()) || request.lod != patch.lod()) {
            return false;
        }
        final TileStamp stamp = new TileStamp(
            request.dimension, request.lod, patch.tileX(), patch.tileZ()
        );
        if (request.pendingStamps.remove(stamp) == null) {
            return false;
        }
        request.lastActivityMs = millisClock.getAsLong();
        if (request.pendingStamps.isEmpty()) {
            inFlightRequests.remove(patch.reqId());
        }
        return true;
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

    private void expireStalledRegionRequests(final long now) {
        final java.util.Iterator<RegionInFlightRequest> pending = regionInFlightRequests.values().iterator();
        while (pending.hasNext()) {
            final RegionInFlightRequest request = pending.next();
            if (now - request.lastActivityMs >= REQUEST_TIMEOUT_MS) {
                regionInFlightStamps.removeAll(request.pending);
                pending.remove();
            }
        }
    }

    private void rollbackPendingRegions() {
        for (final RegionInFlightRequest request : regionInFlightRequests.values()) {
            regionInFlightStamps.removeAll(request.pending);
        }
        regionInFlightRequests.clear();
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

    private static long regionKey(final int regionX, final int regionZ) {
        return ((long) regionX << 32) ^ (regionZ & 0xFFFFFFFFL);
    }
}
