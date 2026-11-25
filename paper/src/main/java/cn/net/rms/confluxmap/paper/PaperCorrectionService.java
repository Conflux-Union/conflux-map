package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapInvalidationPublisher;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionInvalidationPublisher;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.server.ProgressivePolicy;
import cn.net.rms.confluxmap.server.ProgressiveTaskEviction;
import cn.net.rms.confluxmap.server.RequestBytes;
import cn.net.rms.confluxmap.server.LiveChunkSummaryCache;
import cn.net.rms.confluxmap.server.ChunkColumnSummarizer;
import cn.net.rms.confluxmap.server.CorrectionSlotQueue;
import cn.net.rms.confluxmap.server.EncodedResponse;
import cn.net.rms.confluxmap.server.PatchBuilder;
import cn.net.rms.confluxmap.server.PlayerBudget;
import cn.net.rms.confluxmap.server.RegionPatchBuilder;
import cn.net.rms.confluxmap.server.ServerConfig;
import cn.net.rms.confluxmap.server.SyncPerformanceMonitor;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import org.slf4j.Logger;

/** Paper transport-neutral correction engine with asynchronous, non-generating Anvil reads. */
final class PaperCorrectionService implements AutoCloseable {

    interface MessageSender {
        void send(Message message);

        void sendEncoded(Message message, byte[] payload);
    }

    private record ProgressiveKey(
        PaperWorldDirectory.Entry world,
        int lod,
        int tileX,
        int tileZ,
        boolean forceAbsolute
    ) {
    }

    private record TileQueueKey(
        int dimIndex,
        int lod,
        int tileX,
        int tileZ,
        boolean forceAbsolute
    ) {
    }

    private record RegionQueueKey(
        int dimIndex,
        int lod,
        ChunkRegionSlice slice
    ) {
    }

    private static final class PlayerChannel {
        final PlayerBudget budget;
        final CorrectionSlotQueue<TileQueueKey> tiles;
        final CorrectionSlotQueue<RegionQueueKey> regions;
        final SyncPerformanceMonitor performance = new SyncPerformanceMonitor();
        volatile MessageSender sender;

        PlayerChannel(final ServerConfig config, final ExecutorService workers) {
            budget = new PlayerBudget(
                config.maxBytesPerSecondPerPlayer,
                config.minRequestIntervalMs
            );
            tiles = new CorrectionSlotQueue<>(config.maxPendingTilesPerPlayer, workers);
            regions = new CorrectionSlotQueue<>(config.maxPendingTilesPerPlayer, workers);
        }
    }

    private final ServerConfig config;
    private final PaperWorldDirectory worlds;
    private final String worldgenVersion;
    private final long worldSeed;
    private final Function<PaperWorldDirectory.Entry, FlatBaseline> flatBaselines;
    private final Logger logger;
    private final ChunkColumnSummarizer summarizer;
    private final PaperAnvilReader anvil = new PaperAnvilReader();
    private final LiveChunkSummaryCache live = new LiveChunkSummaryCache();
    private final PatchBuilder patchBuilder = new PatchBuilder();
    private final RegionPatchBuilder regionPatchBuilder = new RegionPatchBuilder();
    private final MapInvalidationPublisher invalidations = new MapInvalidationPublisher();
    private final MapRegionInvalidationPublisher regionInvalidations =
        new MapRegionInvalidationPublisher();
    private final PaperLiveDemandTracker liveDemand = new PaperLiveDemandTracker();
    private final Map<UUID, PlayerChannel> channels = new ConcurrentHashMap<>();
    private final Map<ProgressiveKey, PaperProgressiveTile> progressiveTiles =
        new LinkedHashMap<>();
    private final PaperProgressiveScheduler<ProgressiveKey, PaperProgressiveTile>
        progressiveScheduler = new PaperProgressiveScheduler<>();
    private final ExecutorService workers = new ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1_024),
        runnable -> {
            final Thread thread = new Thread(runnable, "ConfluxMap-paper-anvil");
            thread.setDaemon(true);
            return thread;
        }
    );

    PaperCorrectionService(
        final ServerConfig config,
        final PaperWorldDirectory worlds,
        final String worldgenVersion,
        final long worldSeed,
        final Function<PaperWorldDirectory.Entry, FlatBaseline> flatBaselines,
        final PaperMapColors mapColors,
        final Logger logger
    ) {
        this.config = config;
        this.worlds = worlds;
        this.worldgenVersion = worldgenVersion;
        this.worldSeed = worldSeed;
        this.flatBaselines = flatBaselines;
        this.summarizer = new ChunkColumnSummarizer(mapColors::mapColorId);
        this.logger = logger;
    }

    SummaryCodec.Chunk summarizeLive(
        final org.bukkit.ChunkSnapshot snapshot,
        final long revision,
        final int minHeight,
        final int maxHeight
    ) {
        return summarizer.summarize(
            new PaperChunkColumnSource(snapshot, revision, minHeight, maxHeight)
        );
    }

    void putLive(
        final PaperWorldDirectory.Entry world,
        final int chunkX,
        final int chunkZ,
        final SummaryCodec.Chunk summary
    ) {
        if (world != null && live.put(world.dimensionId(), chunkX, chunkZ, summary)) {
            changed(world, chunkX, chunkZ);
        }
    }

    /**
     * Asynchronous captures can complete out of order, so a stale snapshot finishing after a
     * newer one must never overwrite it: a commit is dropped when the cache already holds a
     * strictly newer revision.
     */
    void putLiveIfNewer(
        final PaperWorldDirectory.Entry world,
        final int chunkX,
        final int chunkZ,
        final SummaryCodec.Chunk summary
    ) {
        if (world == null) {
            return;
        }
        final SummaryCodec.Chunk existing = live.get(world.dimensionId(), chunkX, chunkZ);
        if (existing != null && existing.revision() > summary.revision()) {
            return;
        }
        putLive(world, chunkX, chunkZ, summary);
    }

    SummaryCodec.Chunk liveSummary(
        final PaperWorldDirectory.Entry world,
        final int chunkX,
        final int chunkZ
    ) {
        return world == null ? null : live.get(world.dimensionId(), chunkX, chunkZ);
    }

    void removeLive(
        final PaperWorldDirectory.Entry world,
        final int chunkX,
        final int chunkZ
    ) {
        if (world != null && live.remove(world.dimensionId(), chunkX, chunkZ)) {
            changed(world, chunkX, chunkZ);
        }
    }

    void requestTiles(
        final UUID playerId,
        final MapViewReqC2S request,
        final int requestBytes,
        final boolean forceAbsolute,
        final MessageSender sender
    ) {
        final PlayerChannel channel = channel(playerId, sender);
        final long now = System.nanoTime();
        if (request == null) {
            sender.send(new ErrorS2C(
                ErrorS2C.ERR_MALFORMED_REQUEST, "invalid map correction request"
            ));
            return;
        }
        if (request.lod() > Proto.DEFAULT_MAX_PATCH_LOD
            || request.tiles().size() > config.maxTilesPerRequest
            || request.dimIndex() < 0
            || worlds.at(request.dimIndex()) == null
            || !channel.budget.beginRequest(now, config.minRequestIntervalMs)) {
            sender.send(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction request is rate limited"));
            return;
        }
        invalidations.acknowledge(playerId, request);
        regionInvalidations.acknowledge(playerId, request);
        liveDemand.nominate(request, now);
        final int[] itemBytes = RequestBytes.spread(requestBytes, request.tiles().size());
        int tileIndex = 0;
        int overflow = 0;
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            final int tileRequestBytes = itemBytes[tileIndex++];
            final TileQueueKey key = new TileQueueKey(
                request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(), forceAbsolute
            );
            if (request.lod() >= ProgressivePolicy.MIN_LOD) {
                if (!channel.tiles.enqueueCompleted(key, () -> {
                    try {
                        return progressiveTile(request, tile, now, tileRequestBytes, forceAbsolute);
                    } catch (final RuntimeException e) {
                        logger.warn(
                            "Paper progressive correction tile {},{} at LOD {} failed",
                            tile.tileX(), tile.tileZ(), request.lod(), e
                        );
                        return unavailableTile(request, tile, now, tileRequestBytes);
                    }
                })) {
                    overflow++;
                }
                continue;
            }
            if (!channel.tiles.enqueue(key, () -> {
                try {
                    return buildTile(request, tile, now, tileRequestBytes, forceAbsolute);
                } catch (final RuntimeException e) {
                    logger.warn(
                        "Paper correction tile {},{} at LOD {} failed",
                        tile.tileX(), tile.tileZ(), request.lod(), e
                    );
                    return unavailableTile(request, tile, now, tileRequestBytes);
                }
            })) {
                overflow++;
            }
        }
        if (overflow > 0) {
            sender.send(new ErrorS2C(
                ErrorS2C.ERR_RATE_LIMITED, "map correction queue is full"
            ));
        }
    }

    void requestRegions(
        final UUID playerId,
        final MapRegionViewReqC2S request,
        final int requestBytes,
        final boolean forceAbsolute,
        final MessageSender sender
    ) {
        requestRegions(
            playerId, request, requestBytes, forceAbsolute,
            CorrectionProfile.SOURCE_LIGHT_V2, sender
        );
    }

    void requestRegions(
        final UUID playerId,
        final MapRegionViewReqC2S request,
        final int requestBytes,
        final boolean forceAbsolute,
        final CorrectionProfile correctionProfile,
        final MessageSender sender
    ) {
        final PlayerChannel channel = channel(playerId, sender);
        final long now = System.nanoTime();
        if (request == null) {
            sender.send(new ErrorS2C(
                ErrorS2C.ERR_MALFORMED_REQUEST, "invalid region correction request"
            ));
            return;
        }
        if (worlds.at(request.dimIndex()) == null
            || request.lod() > Proto.DEFAULT_MAX_PATCH_LOD
            || request.regions().isEmpty()
            || request.regions().size() > Proto.MAX_REGION_PAGES_PER_REQ
            || request.regions().size() > config.maxTilesPerRequest
            || !channel.budget.beginRequest(now, config.minRequestIntervalMs)) {
            sender.send(new ErrorS2C(
                ErrorS2C.ERR_RATE_LIMITED, "map region correction request is rate limited"
            ));
            return;
        }
        regionInvalidations.acknowledge(playerId, request);
        liveDemand.nominate(request, now);
        final int[] itemBytes = RequestBytes.spread(requestBytes, request.regions().size());
        int regionIndex = 0;
        int overflow = 0;
        for (final MapRegionViewReqC2S.RegionReq region : request.regions()) {
            final int regionRequestBytes = itemBytes[regionIndex++];
            final RegionQueueKey key = new RegionQueueKey(
                request.dimIndex(), request.lod(), region.slice()
            );
            if (!channel.regions.enqueue(key, () -> {
                try {
                    return buildRegion(
                        request, region, now, regionRequestBytes, forceAbsolute, correctionProfile
                    );
                } catch (final RuntimeException e) {
                    logger.warn(
                        "Paper correction region {},{} at LOD {} failed",
                        region.regionX(), region.regionZ(), request.lod(), e
                    );
                    return unavailableRegion(request, region, now, regionRequestBytes);
                }
            })) {
                overflow++;
            }
        }
        if (overflow > 0) {
            sender.send(new ErrorS2C(
                ErrorS2C.ERR_RATE_LIMITED, "map region correction queue is full"
            ));
        }
    }

    boolean subscribe(
        final UUID playerId,
        final MapSyncSubscribeC2S request,
        final MessageSender sender
    ) {
        channel(playerId, sender);
        if ((request.active() && worlds.at(request.dimIndex()) == null)
            || !invalidations.subscribe(playerId, request)) {
            return false;
        }
        if (!liveDemand.watch(playerId, request)) {
            invalidations.remove(playerId);
            liveDemand.remove(playerId);
            return false;
        }
        return true;
    }

    boolean subscribeRegions(
        final UUID playerId,
        final MapRegionSyncSubscribeC2S request,
        final MessageSender sender
    ) {
        channel(playerId, sender);
        if ((request.active() && worlds.at(request.dimIndex()) == null)
            || !regionInvalidations.subscribe(playerId, request)) {
            return false;
        }
        if (!liveDemand.watch(playerId, request)) {
            regionInvalidations.remove(playerId);
            liveDemand.remove(playerId);
            return false;
        }
        return true;
    }

    void tick() {
        final long now = System.nanoTime();
        liveDemand.tick(now);
        // Each tickProgressive call advances exactly one incomplete tile by one worker
        // step; two calls per server tick match the two-thread anvil pool, and a tile
        // whose async step is still in flight is a no-op, so this never double-queues
        // a single tile.
        tickProgressive(now);
        tickProgressive(now);
        for (final Map.Entry<UUID, PlayerChannel> entry : channels.entrySet()) {
            final UUID playerId = entry.getKey();
            final PlayerChannel channel = entry.getValue();
            final MessageSender sender = channel.sender;
            if (sender == null) {
                continue;
            }
            final MapInvalidateS2C tileInvalidation = invalidations.poll(playerId);
            if (tileInvalidation != null) {
                sender.send(tileInvalidation);
            }
            final MapRegionInvalidateS2C regionInvalidation = regionInvalidations.poll(playerId);
            if (regionInvalidation != null) {
                sender.send(regionInvalidation);
            }
            channel.tiles.drain(channel.budget, sender::sendEncoded, channel.performance, now);
            channel.regions.drain(channel.budget, sender::sendEncoded, channel.performance, now);
        }
    }

    List<SyncPerformanceMonitor.LodSnapshot> performance(final UUID playerId) {
        final PlayerChannel channel = channels.get(playerId);
        return channel == null ? List.of() : channel.performance.snapshots();
    }

    void remove(final UUID playerId) {
        channels.remove(playerId);
        invalidations.remove(playerId);
        regionInvalidations.remove(playerId);
        liveDemand.remove(playerId);
    }

    boolean liveSummaryDemanded(
        final PaperWorldDirectory.Entry world,
        final int chunkX,
        final int chunkZ,
        final long nowNanos
    ) {
        return world != null
            && liveDemand.contains(world.index(), chunkX, chunkZ, nowNanos);
    }

    private EncodedResponse buildTile(
        final MapViewReqC2S request,
        final MapViewReqC2S.TileReq tile,
        final long receivedNanos,
        final int requestBytes,
        final boolean forceAbsolute
    ) {
        final PaperWorldDirectory.Entry world = worlds.at(request.dimIndex());
        final long buildStartedNanos = System.nanoTime();
        long started = buildStartedNanos;
        final List<SummaryCodec.SampledRegion> regions = new ArrayList<>();
        final int regionsPerSide = 1 << request.lod();
        final int baseRegionX = Math.multiplyExact(tile.tileX(), regionsPerSide);
        final int baseRegionZ = Math.multiplyExact(tile.tileZ(), regionsPerSide);
        for (int regionZ = 0; regionZ < regionsPerSide; regionZ++) {
            for (int regionX = 0; regionX < regionsPerSide; regionX++) {
                final int rx = Math.addExact(baseRegionX, regionX);
                final int rz = Math.addExact(baseRegionZ, regionZ);
                final ChunkRegionSlice slice = new ChunkRegionSlice(rx, rz, 0, 0, 15, 15);
                SummaryCodec.SampledRegion region = anvil.scanRegion(
                    world.regionDirectory(), request.lod(), slice, summarizer
                );
                if (region != null) {
                    region = live.overlay(world.dimensionId(), region);
                    regions.add(region);
                }
            }
        }
        final long ioNanos = Math.max(0L, System.nanoTime() - started);
        started = System.nanoTime();
        final PaperSampledSummaryTile summary = new PaperSampledSummaryTile(
            request.lod(), tile.tileX(), tile.tileZ(), regions
        );
        final PatchBuilder.Result result = patchBuilder.buildPrepared(
            summary,
            tile.sinceRevision(),
            baseline(world, summary, null, forceAbsolute)
        );
        final MapPatchS2C response = new MapPatchS2C(
            request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
            result.mode(), result.revision(), result.presence(), result.body()
        );
        final long computeNanos = Math.max(0L, System.nanoTime() - started);
        return EncodedResponse.of(
            response,
            request.dimIndex(),
            request.lod(),
            tile.tileX(),
            tile.tileZ(),
            result.mode(),
            receivedNanos,
            requestBytes,
            buildStartedNanos,
            ioNanos,
            computeNanos,
            SyncPerformanceMonitor.CumulativeWork.NONE
        );
    }

    private EncodedResponse progressiveTile(
        final MapViewReqC2S request,
        final MapViewReqC2S.TileReq tile,
        final long receivedNanos,
        final int requestBytes,
        final boolean forceAbsolute
    ) {
        final PaperWorldDirectory.Entry world = worlds.at(request.dimIndex());
        if (world == null) {
            return unavailableTile(request, tile, receivedNanos, requestBytes);
        }
        final ProgressiveKey key = new ProgressiveKey(
            world, request.lod(), tile.tileX(), tile.tileZ(), forceAbsolute
        );
        PaperProgressiveTile progressive = progressiveTiles.get(key);
        if (progressive == null) {
            evictProgressive(receivedNanos, true);
            if (progressiveTiles.size() >= ProgressivePolicy.MAX_ACTIVE_TILES) {
                return partialTile(request, tile, receivedNanos, requestBytes);
            }
            progressive = new PaperProgressiveTile(
                world.regionDirectory(),
                request.lod(),
                tile.tileX(),
                tile.tileZ(),
                (regionX, regionZ) -> {
                    final ChunkRegionSlice slice = new ChunkRegionSlice(
                        regionX, regionZ, 0, 0, 15, 15
                    );
                    SummaryCodec.SampledRegion region = anvil.scanRegion(
                        world.regionDirectory(), request.lod(), slice, summarizer
                    );
                    if (region != null) {
                        region = live.overlay(world.dimensionId(), region);
                    }
                    return region;
                },
                summary -> PaperCorrectionBaseline.prepare(
                    patchBuilder,
                    summary,
                    null,
                    forceAbsolute,
                    world.preset() == cn.net.rms.confluxmap.core.predict.WorldPreset.FLAT
                        ? flatBaselines.apply(world) : null,
                    world.preset(),
                    worldgenVersion,
                    worldSeed,
                    world.parsedDimensionId()
                ),
                patchBuilder,
                workers,
                receivedNanos
            );
            progressiveTiles.put(key, progressive);
        }
        final long started = System.nanoTime();
        final PaperProgressiveTile.Response result = progressive.response(
            tile.sinceRevision(), receivedNanos
        );
        final MapPatchS2C response = new MapPatchS2C(
            request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
            result.mode(), result.revision(), result.presence(), result.body()
        );
        return EncodedResponse.of(
            response,
            request.dimIndex(),
            request.lod(),
            tile.tileX(),
            tile.tileZ(),
            result.mode(),
            receivedNanos,
            requestBytes,
            receivedNanos,
            0L,
            Math.max(0L, System.nanoTime() - started),
            result.work()
        );
    }

    private EncodedResponse partialTile(
        final MapViewReqC2S request,
        final MapViewReqC2S.TileReq tile,
        final long receivedNanos,
        final int requestBytes
    ) {
        final MapPatchS2C response = new MapPatchS2C(
            request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
            Proto.PATCH_MODE_PARTIAL, 0L,
            new byte[Proto.PATCH_PRESENCE_BYTES],
            cn.net.rms.confluxmap.core.net.PatchCodec.encode(List.of())
        );
        return EncodedResponse.of(
            response, request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
            Proto.PATCH_MODE_PARTIAL, receivedNanos, requestBytes, 0L, 0L
        );
    }

    private EncodedResponse unavailableTile(
        final MapViewReqC2S request,
        final MapViewReqC2S.TileReq tile,
        final long receivedNanos,
        final int requestBytes
    ) {
        final MapPatchS2C response = new MapPatchS2C(
            request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
            Proto.PATCH_MODE_UNAVAILABLE, 0L,
            new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]
        );
        return EncodedResponse.of(
            response, request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
            Proto.PATCH_MODE_UNAVAILABLE, receivedNanos, requestBytes, 0L, 0L
        );
    }

    private EncodedResponse buildRegion(
        final MapRegionViewReqC2S request,
        final MapRegionViewReqC2S.RegionReq regionRequest,
        final long receivedNanos,
        final int requestBytes,
        final boolean forceAbsolute,
        final CorrectionProfile correctionProfile
    ) {
        final PaperWorldDirectory.Entry world = worlds.at(request.dimIndex());
        final ChunkRegionSlice slice = regionRequest.slice();
        final long buildStartedNanos = System.nanoTime();
        long started = buildStartedNanos;
        SummaryCodec.SampledRegion summary = anvil.scanRegion(
            world.regionDirectory(), request.lod(), slice, summarizer
        );
        if (summary != null) {
            summary = live.overlay(world.dimensionId(), summary, slice);
        }
        final long ioNanos = Math.max(0L, System.nanoTime() - started);
        started = System.nanoTime();
        final int regionsPerTile = 1 << request.lod();
        final PaperSampledSummaryTile tileSummary = summary == null ? null
            : new PaperSampledSummaryTile(
                request.lod(),
                Math.floorDiv(slice.regionX(), regionsPerTile),
                Math.floorDiv(slice.regionZ(), regionsPerTile),
                List.of(summary)
            );
        final RegionPatchBuilder.Result result = tileSummary == null
            ? RegionPatchBuilder.unavailable()
            : regionPatchBuilder.build(
                request.lod(),
                slice,
                summary,
                regionRequest.sinceRevision(),
                baseline(world, tileSummary, slice, forceAbsolute),
                correctionProfile
            );
        final MapRegionPatchS2C response = new MapRegionPatchS2C(
            request.reqId(), request.dimIndex(), request.lod(),
            slice.regionX(), slice.regionZ(),
            slice.minLocalChunkX(), slice.minLocalChunkZ(),
            slice.maxLocalChunkX(), slice.maxLocalChunkZ(),
            result.mode(), result.revision(), result.body()
        );
        final long computeNanos = Math.max(0L, System.nanoTime() - started);
        return EncodedResponse.of(
            response,
            request.dimIndex(),
            request.lod(),
            Math.floorDiv(slice.regionX(), regionsPerTile),
            Math.floorDiv(slice.regionZ(), regionsPerTile),
            result.mode(),
            receivedNanos,
            requestBytes,
            buildStartedNanos,
            ioNanos,
            computeNanos,
            SyncPerformanceMonitor.CumulativeWork.NONE
        );
    }

    private PatchBuilder.PreparedBaseline baseline(
        final PaperWorldDirectory.Entry world,
        final PaperSampledSummaryTile summary,
        final ChunkRegionSlice slice,
        final boolean forceAbsolute
    ) {
        final FlatBaseline flat = world.preset() == cn.net.rms.confluxmap.core.predict.WorldPreset.FLAT
            ? flatBaselines.apply(world) : null;
        return PaperCorrectionBaseline.prepare(
            patchBuilder,
            summary,
            slice,
            forceAbsolute,
            flat,
            world.preset(),
            worldgenVersion,
            worldSeed,
            world.parsedDimensionId()
        );
    }

    private EncodedResponse unavailableRegion(
        final MapRegionViewReqC2S request,
        final MapRegionViewReqC2S.RegionReq region,
        final long receivedNanos,
        final int requestBytes
    ) {
        final ChunkRegionSlice slice = region.slice();
        final MapRegionPatchS2C response = new MapRegionPatchS2C(
            request.reqId(), request.dimIndex(), request.lod(),
            slice.regionX(), slice.regionZ(),
            slice.minLocalChunkX(), slice.minLocalChunkZ(),
            slice.maxLocalChunkX(), slice.maxLocalChunkZ(),
            Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[0]
        );
        final int regionsPerTile = 1 << request.lod();
        return EncodedResponse.of(
            response,
            request.dimIndex(),
            request.lod(),
            Math.floorDiv(slice.regionX(), regionsPerTile),
            Math.floorDiv(slice.regionZ(), regionsPerTile),
            Proto.PATCH_MODE_UNAVAILABLE,
            receivedNanos,
            requestBytes,
            0L,
            0L
        );
    }

    private void changed(
        final PaperWorldDirectory.Entry world,
        final int chunkX,
        final int chunkZ
    ) {
        final int regionX = Math.floorDiv(chunkX, 16);
        final int regionZ = Math.floorDiv(chunkZ, 16);
        invalidations.invalidateRegion(world.index(), regionX, regionZ);
        regionInvalidations.invalidateRegion(world.index(), regionX, regionZ);
        for (final Map.Entry<ProgressiveKey, PaperProgressiveTile> entry
            : progressiveTiles.entrySet()) {
            if (entry.getKey().world() == world
                && entry.getValue().covers(regionX, regionZ)) {
                entry.getValue().invalidate();
            }
        }
        for (final PlayerChannel channel : channels.values()) {
            channel.tiles.restartMatching(key -> {
                final int regionsPerTile = 1 << key.lod();
                return key.dimIndex() == world.index()
                    && Math.floorDiv(regionX, regionsPerTile) == key.tileX()
                    && Math.floorDiv(regionZ, regionsPerTile) == key.tileZ();
            });
            channel.regions.restartMatching(key -> key.dimIndex() == world.index()
                && key.slice().regionX() == regionX
                && key.slice().regionZ() == regionZ);
        }
    }

    private void tickProgressive(final long nowNanos) {
        evictProgressive(nowNanos, false);
        if (progressiveTiles.isEmpty()) {
            progressiveScheduler.clear();
            return;
        }
        progressiveScheduler.tick(
            progressiveTiles,
            this::watched,
            PaperProgressiveTile::complete,
            PaperProgressiveTile::tick
        );
    }

    private boolean watched(final ProgressiveKey key) {
        return invalidations.watches(
            key.world().index(), key.lod(), key.tileX(), key.tileZ()
        );
    }

    private void evictProgressive(final long nowNanos, final boolean forCapacity) {
        ProgressiveTaskEviction.evict(progressiveTiles, nowNanos, forCapacity, this::watched);
    }

    private PlayerChannel channel(final UUID playerId, final MessageSender sender) {
        final PlayerChannel channel = channels.computeIfAbsent(
            playerId, ignored -> new PlayerChannel(config, workers)
        );
        channel.sender = sender;
        return channel;
    }

    @Override
    public void close() {
        progressiveTiles.values().forEach(PaperProgressiveTile::close);
        progressiveTiles.clear();
        workers.shutdownNow();
        try {
            workers.awaitTermination(5, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        channels.clear();
        invalidations.clear();
        regionInvalidations.clear();
        liveDemand.clear();
        live.clear();
    }
}
