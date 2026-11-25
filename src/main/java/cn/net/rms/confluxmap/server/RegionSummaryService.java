package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.CorrectionProfile;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapInvalidationPublisher;
import cn.net.rms.confluxmap.core.net.MapInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionInvalidateS2C;
import cn.net.rms.confluxmap.core.net.MapRegionInvalidationPublisher;
import cn.net.rms.confluxmap.core.net.MapRegionPatchS2C;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.MsgCodec;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.ProtoException;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.QuadrantLayout;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.compat.Dimensions;
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Serves summary-backed corrections without asking the world chunk manager to generate chunks.
 *
 * <p>Delivery is queued per player ({@link CorrectionSlotQueue}): a request's tiles are enqueued
 * and built on the anvil worker pool — chunk NBT reads and patch encoding never touch the server
 * thread — and each server tick drains as many encoded results as the player's byte budget
 * allows. Only queue overflow is answered with {@code ERR_RATE_LIMITED}; a temporarily exhausted
 * byte budget never drops tiles.
 */
public final class RegionSummaryService {
    private static final int PROGRESSIVE_MAX_CHUNKS_OR_REGIONS_PER_TICK = 2_048;
    private static final long PROGRESSIVE_MAX_NANOS_PER_TICK = 4_000_000L;
    /** Hard global bound: a worst-case LOD0 page can approach the protocol's 576 KiB body cap. */
    private static final int REGION_PAGE_CACHE_LIMIT = 64;
    private static final int REGION_BASELINE_CACHE_LIMIT = 8;
    /** Bounds the live-chunk invalidation backlog between ticks; a CLQ would otherwise grow unbounded
     * while worldgen mutates far more regions per tick than the drain can republish. */
    private static final int CHANGED_REGIONS_LIMIT = 4_096;
    private static final AtomicLong NEXT_REGION_WORK_ID = new AtomicLong();

    private final ServerConfig config;
    private final ChunkSummarizer summarizer = new ChunkSummarizer(new RegistryMapColors());
    private final PatchBuilder patchBuilder = new PatchBuilder();
    private final Map<UUID, PlayerChannel> channels = new ConcurrentHashMap<>();
    private final MapInvalidationPublisher invalidations = new MapInvalidationPublisher();
    private final MapRegionInvalidationPublisher regionInvalidations =
        new MapRegionInvalidationPublisher();
    private final RegionPatchBuilder regionPatchBuilder = new RegionPatchBuilder();
    private final ConcurrentLinkedQueue<ChangedRegion> changedRegions = new ConcurrentLinkedQueue<>();
    /** Mirrors changedRegions.size(); ConcurrentLinkedQueue.size() is O(n) on the chunk hot path. */
    private final AtomicInteger changedRegionCount = new AtomicInteger();
    private final AtomicBoolean changedRegionOverflowLogged = new AtomicBoolean();
    private final LiveChunkSummaryTracker liveChunks;
    private final ExecutorService progressiveWorker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ConfluxMap-progressive-patches");
        thread.setDaemon(true);
        return thread;
    });
    private final ExecutorService progressiveScanWorker = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "ConfluxMap-anvil-scans");
        thread.setDaemon(true);
        return thread;
    });
    /** Builds and encodes LOD&lt;3 tile patches off the server thread, mirroring the Paper companion. */
    private final ExecutorService anvilWorkers = new ThreadPoolExecutor(
        2,
        2,
        0L,
        TimeUnit.MILLISECONDS,
        new ArrayBlockingQueue<>(1_024),
        runnable -> {
            final Thread thread = new Thread(runnable, "ConfluxMap-fabric-anvil");
            thread.setDaemon(true);
            return thread;
        }
    );
    /** Access-ordered global task cache; identical player requests reuse the same validated scan. */
    private final LinkedHashMap<ProgressiveKey, ProgressiveRegionPatch> progressiveTasks =
        new LinkedHashMap<>(16, 0.75f, true);
    private final LinkedHashMap<RegionPageKey, RegionPageTask> regionPageTasks =
        new LinkedHashMap<>(64, 0.75f, true);
    private final LinkedHashMap<RegionBaselineKey, RegionBaselineTask> regionBaselineTasks =
        new LinkedHashMap<>(8, 0.75f, true);
    private final AnvilMcaScanner regionMcaScanner = new AnvilMcaScanner();
    private ProgressiveKey activeProgressiveKey;
    private Path diskRoot;
    private SummaryDiskCache diskCache;

    private record ProgressiveKey(
        ServerWorld world, int lod, int tileX, int tileZ, boolean forceAbsolute
    ) {
    }

    private record RegionPageKey(
        ServerWorld world,
        int lod,
        ChunkRegionSlice slice,
        boolean forceAbsolute,
        CorrectionProfile correctionProfile
    ) {
    }

    private record RegionBaselineKey(
        ServerWorld world, int lod, int tileX, int tileZ, PixelWindow window
    ) {
    }

    private record PixelWindow(int minX, int minZ, int maxX, int maxZ) {
    }

    private record RegionPageResult(
        int mode,
        long revision,
        byte[] body,
        long sourceMcaMtimeMs,
        long liveEpoch,
        SyncPerformanceMonitor.CumulativeWork work,
        SyncPerformanceMonitor.CumulativeWork baselineWork
    ) {
    }

    private static final class RegionPageWork {
        private final long workId = NEXT_REGION_WORK_ID.incrementAndGet();
        private long startedNanos;
        private long ioNanos;
        private long computeNanos;

        void start() {
            startedNanos = System.nanoTime();
        }

        void addIo(final long nanos) {
            ioNanos += Math.max(0L, nanos);
        }

        void addCompute(final long nanos) {
            computeNanos += Math.max(0L, nanos);
        }

        SyncPerformanceMonitor.CumulativeWork snapshot() {
            if (startedNanos <= 0L) {
                return SyncPerformanceMonitor.CumulativeWork.NONE;
            }
            return new SyncPerformanceMonitor.CumulativeWork(
                workId, startedNanos, ioNanos, computeNanos
            );
        }
    }

    private static final class RegionPageTask {
        final CompletableFuture<RegionPageResult> future;
        long lastRequestedAtNanos;

        RegionPageTask(
            final CompletableFuture<RegionPageResult> future, final long lastRequestedAtNanos
        ) {
            this.future = future;
            this.lastRequestedAtNanos = lastRequestedAtNanos;
        }
    }

    private static final class RegionBaselineTask {
        final CompletableFuture<PatchBuilder.PreparedBaseline> future;
        final RegionPageWork work;
        long lastRequestedAtNanos;

        RegionBaselineTask(
            final CompletableFuture<PatchBuilder.PreparedBaseline> future,
            final RegionPageWork work,
            final long lastRequestedAtNanos
        ) {
            this.future = future;
            this.work = work;
            this.lastRequestedAtNanos = lastRequestedAtNanos;
        }

        SyncPerformanceMonitor.CumulativeWork workSnapshot() {
            return work == null
                ? SyncPerformanceMonitor.CumulativeWork.NONE : work.snapshot();
        }
    }

    private record RegionJob(
        int reqId,
        int dimIndex,
        int lod,
        MapRegionViewReqC2S.RegionReq request,
        long receivedAtNanos,
        int requestBytes,
        boolean forceAbsolute,
        CorrectionProfile correctionProfile
    ) {
    }

    private record RegionJobKey(int dimIndex, int lod, ChunkRegionSlice slice) {
    }

    private record TileQueueKey(
        int dimIndex, int lod, int tileX, int tileZ, boolean forceAbsolute
    ) {
    }

    private record ChangedRegion(String dimension, int regionX, int regionZ) {
    }

    private static final class PlayerChannel {
        final PlayerBudget budget;
        final CorrectionSlotQueue<TileQueueKey> tiles;
        final LinkedHashMap<RegionJobKey, RegionJob> regionQueue = new LinkedHashMap<>();
        final SyncPerformanceMonitor performance;
        volatile MessageSender sender;

        PlayerChannel(final ServerConfig config, final ExecutorService tileWorkers) {
            budget = new PlayerBudget(
                config.maxBytesPerSecondPerPlayer, config.minRequestIntervalMs
            );
            tiles = new CorrectionSlotQueue<>(config.maxPendingTilesPerPlayer, tileWorkers);
            performance = new SyncPerformanceMonitor();
        }
    }

    public RegionSummaryService(final ServerConfig config) {
        this.config = config;
        this.liveChunks = new LiveChunkSummaryTracker(config, summarizer, this::onRegionChanged);
    }

    /**
     * Quadra-gen quadrant layouts for this server's lifetime; masked into every residual baseline
     * so the companion diffs against exactly what each quadrant-aware client predicts.
     */
    public void setQuadrantLayouts(final Map<DimensionId, QuadrantLayout> layouts) {
        patchBuilder.setQuadrantLayouts(layouts);
    }

    /** Starts serving a loaded chunk from memory and enrolls it in bounded live refreshes. */
    public void onChunkLoad(final ServerWorld world, final WorldChunk chunk) {
        liveChunks.onChunkLoad(world, chunk);
    }

    /** Prioritizes a loaded chunk whose block columns changed while a map viewer is watching it. */
    public void onChunkDirty(final ServerWorld world, final WorldChunk chunk) {
        liveChunks.onChunkDirty(world, chunk);
    }

    /** Captures the final in-memory state and queues one batched level-0 cache write. */
    public void onChunkUnload(final ServerWorld world, final WorldChunk chunk) {
        liveChunks.onChunkUnload(world, chunk);
    }

    public void request(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapViewReqC2S request,
        final MessageSender sender
    ) {
        int requestPayloadBytes = 0;
        try {
            requestPayloadBytes = MsgCodec.encode(request).length;
        } catch (final ProtoException ignored) {
            // The normal network boundary already rejects unencodable requests.
        }
        request(server, player, request, requestPayloadBytes, sender);
    }

    public void request(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapViewReqC2S request,
        final int requestPayloadBytes,
        final MessageSender sender
    ) {
        request(server, player, request, requestPayloadBytes, false, sender);
    }

    public void request(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapViewReqC2S request,
        final int requestPayloadBytes,
        final boolean forceAbsolute,
        final MessageSender sender
    ) {
        request(
            server, player.getUuid(), request, requestPayloadBytes, forceAbsolute, sender
        );
    }

    /** Platform-neutral client identity seam used by the HTTP map transport. */
    public void request(
        final MinecraftServer server,
        final UUID clientId,
        final MapViewReqC2S request,
        final int requestPayloadBytes,
        final boolean forceAbsolute,
        final MessageSender sender
    ) {
        final long now = System.nanoTime();
        final PlayerChannel channel = channels.computeIfAbsent(clientId, ignored -> newPlayerChannel());
        channel.sender = sender;
        if (request.lod() > lodCeiling() || request.tiles().size() > config.maxTilesPerRequest
            || request.dimIndex() < 0 || !channel.budget.beginRequest(now, config.minRequestIntervalMs)) {
            sender.send(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction request is rate limited"));
            return;
        }
        final SummaryDiskCache disk = diskFor(server);
        final int[] tileRequestBytes = RequestBytes.spread(requestPayloadBytes, request.tiles().size());
        int tileIndex = 0;
        int overflow = 0;
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            final int requestBytes = tileRequestBytes[tileIndex++];
            final TileQueueKey key = new TileQueueKey(
                request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(), forceAbsolute
            );
            final TileJob job = new TileJob(
                request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
                tile.sinceRevision(), now, requestBytes, forceAbsolute
            );
            final boolean queued;
            if (request.lod() >= ProgressivePolicy.MIN_LOD) {
                queued = channel.tiles.enqueueCompleted(
                    key, () -> progressiveResponse(server, disk, job)
                );
            } else {
                queued = channel.tiles.enqueue(key, () -> buildResponse(server, disk, job));
            }
            if (!queued) {
                overflow++;
            }
        }
        if (overflow > 0) {
            sender.send(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction queue is full"));
        }
        invalidations.acknowledge(clientId, request);
        regionInvalidations.acknowledge(clientId, request);
        liveChunks.nominate(request, now);
        drain(channel, now);
    }

    public void requestRegions(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapRegionViewReqC2S request,
        final MessageSender sender
    ) {
        int requestPayloadBytes = 0;
        try {
            requestPayloadBytes = MsgCodec.encode(request).length;
        } catch (final ProtoException ignored) {
            // The normal network boundary already rejects unencodable requests.
        }
        requestRegions(server, player, request, requestPayloadBytes, sender);
    }

    public void requestRegions(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapRegionViewReqC2S request,
        final int requestPayloadBytes,
        final MessageSender sender
    ) {
        requestRegions(server, player, request, requestPayloadBytes, false, sender);
    }

    public void requestRegions(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapRegionViewReqC2S request,
        final int requestPayloadBytes,
        final boolean forceAbsolute,
        final MessageSender sender
    ) {
        requestRegions(
            server, player, request, requestPayloadBytes, forceAbsolute,
            CorrectionProfile.SOURCE_LIGHT_V2, sender
        );
    }

    public void requestRegions(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapRegionViewReqC2S request,
        final int requestPayloadBytes,
        final boolean forceAbsolute,
        final CorrectionProfile correctionProfile,
        final MessageSender sender
    ) {
        requestRegions(
            server, player.getUuid(), request, requestPayloadBytes, forceAbsolute,
            correctionProfile, sender
        );
    }

    /** Platform-neutral client identity seam used by the HTTP map transport. */
    public void requestRegions(
        final MinecraftServer server,
        final UUID clientId,
        final MapRegionViewReqC2S request,
        final int requestPayloadBytes,
        final boolean forceAbsolute,
        final CorrectionProfile correctionProfile,
        final MessageSender sender
    ) {
        final long now = System.nanoTime();
        final PlayerChannel channel = channels.computeIfAbsent(
            clientId, ignored -> newPlayerChannel()
        );
        channel.sender = sender;
        if (request.lod() > lodCeiling() || request.regions().isEmpty()
            || request.regions().size() > Proto.MAX_REGION_PAGES_PER_REQ
            || request.regions().size() > config.maxTilesPerRequest
            || request.dimIndex() < 0 || worldAt(server, request.dimIndex()) == null
            || !channel.budget.beginRequest(now, config.minRequestIntervalMs)) {
            sender.send(new ErrorS2C(
                ErrorS2C.ERR_RATE_LIMITED, "map region correction request is rate limited"
            ));
            return;
        }
        final int[] regionRequestBytes = RequestBytes.spread(
            requestPayloadBytes, request.regions().size()
        );
        int overflow = 0;
        int regionIndex = 0;
        synchronized (channel.regionQueue) {
            for (final MapRegionViewReqC2S.RegionReq region : request.regions()) {
                final int requestBytes = regionRequestBytes[regionIndex++];
                final RegionJob job = new RegionJob(
                    request.reqId(), request.dimIndex(), request.lod(), region,
                    now, requestBytes, forceAbsolute, correctionProfile
                );
                final RegionJobKey key = new RegionJobKey(
                    request.dimIndex(), request.lod(), region.slice()
                );
                if (channel.regionQueue.containsKey(key)) {
                    channel.regionQueue.put(key, job);
                } else if (channel.regionQueue.size() >= config.maxPendingTilesPerPlayer) {
                    overflow++;
                } else {
                    channel.regionQueue.put(key, job);
                }
            }
        }
        if (overflow > 0) {
            sender.send(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map region correction queue is full"));
        }
        regionInvalidations.acknowledge(clientId, request);
        liveChunks.nominate(request, now);
        drainRegions(server, channel, now);
    }

    public boolean subscribe(
        final MinecraftServer server,
        final UUID player,
        final MapSyncSubscribeC2S request,
        final MessageSender sender
    ) {
        if (request.active() && (request.lod() > lodCeiling() || worldAt(server, request.dimIndex()) == null)) {
            return false;
        }
        final PlayerChannel channel = channels.computeIfAbsent(player, ignored -> newPlayerChannel());
        channel.sender = sender;
        if (!invalidations.subscribe(player, request)) {
            return false;
        }
        if (!liveChunks.watch(player, request)) {
            invalidations.remove(player);
            liveChunks.unwatch(player);
            return false;
        }
        return true;
    }

    public boolean subscribeRegions(
        final MinecraftServer server,
        final UUID player,
        final MapRegionSyncSubscribeC2S request,
        final MessageSender sender
    ) {
        if (request.active() && (request.lod() > lodCeiling()
            || worldAt(server, request.dimIndex()) == null)) {
            return false;
        }
        final PlayerChannel channel = channels.computeIfAbsent(player, ignored -> newPlayerChannel());
        channel.sender = sender;
        if (!regionInvalidations.subscribe(player, request)) {
            return false;
        }
        if (!liveChunks.watch(player, request)) {
            regionInvalidations.remove(player);
            liveChunks.unwatch(player);
            return false;
        }
        return true;
    }

    /** Server tick: keep draining queued patches as each player's byte budget refills. */
    public void tick(final MinecraftServer server) {
        final SummaryDiskCache disk = diskFor(server);
        liveChunks.tick(server, disk);
        final long now = System.nanoTime();
        drainChangedRegions(server);
        tickProgressive(server, now);
        evictRegionPageTasks(now);
        evictRegionBaselineTasks(now);
        for (final Map.Entry<UUID, PlayerChannel> entry : channels.entrySet()) {
            final PlayerChannel channel = entry.getValue();
            final MapInvalidateS2C invalidation = invalidations.poll(entry.getKey());
            if (invalidation != null && channel.sender != null) {
                channel.sender.send(invalidation);
            }
            final MapRegionInvalidateS2C regionInvalidation = regionInvalidations.poll(entry.getKey());
            if (regionInvalidation != null && channel.sender != null) {
                channel.sender.send(regionInvalidation);
            }
            if (channel.sender != null) {
                drain(channel, now);
            }
            if (!channel.regionQueue.isEmpty() && channel.sender != null) {
                drainRegions(server, channel, now);
            }
        }
    }

    public void remove(final UUID player) {
        invalidations.remove(player);
        regionInvalidations.remove(player);
        liveChunks.unwatch(player);
        final PlayerChannel channel = channels.remove(player);
        if (channel != null) {
            channel.tiles.clear();
            synchronized (channel.regionQueue) {
                channel.regionQueue.clear();
            }
        }
    }

    /** Completed sync-item averages for one player's current server connection. */
    public List<SyncPerformanceMonitor.LodSnapshot> performance(final UUID player) {
        final PlayerChannel channel = channels.get(player);
        return channel == null ? List.of() : channel.performance.snapshots();
    }

    /** Captures chunks still loaded when vanilla begins its final save/unload sequence. */
    public void prepareStop() {
        liveChunks.prepareStop();
    }

    /** Flushes captured unloads after vanilla has saved every dimension, then drops session state. */
    public void close(final MinecraftServer server) {
        liveChunks.close(server, diskFor(server));
        invalidations.clear();
        regionInvalidations.clear();
        changedRegions.clear();
        changedRegionCount.set(0);
        channels.clear();
        synchronized (progressiveTasks) {
            for (final ProgressiveRegionPatch task : progressiveTasks.values()) {
                task.close();
            }
            progressiveTasks.clear();
        }
        synchronized (regionPageTasks) {
            for (final RegionPageTask task : regionPageTasks.values()) {
                task.future.cancel(false);
            }
            regionPageTasks.clear();
        }
        synchronized (regionBaselineTasks) {
            for (final RegionBaselineTask task : regionBaselineTasks.values()) {
                task.future.cancel(false);
            }
            regionBaselineTasks.clear();
        }
        progressiveScanWorker.shutdownNow();
        progressiveWorker.shutdownNow();
        anvilWorkers.shutdownNow();
        try {
            progressiveScanWorker.awaitTermination(2L, TimeUnit.SECONDS);
            progressiveWorker.awaitTermination(2L, TimeUnit.SECONDS);
            anvilWorkers.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain(final PlayerChannel channel, final long nowNanos) {
        final MessageSender sender = channel.sender;
        if (sender == null || channel.tiles.isEmpty()) {
            return;
        }
        channel.tiles.drain(channel.budget, sender::sendEncoded, channel.performance, nowNanos);
    }

    private PlayerChannel newPlayerChannel() {
        return new PlayerChannel(config, anvilWorkers);
    }

    private void drainRegions(
        final MinecraftServer server, final PlayerChannel channel, final long nowNanos
    ) {
        final MessageSender sender = channel.sender;
        if (sender == null) {
            return;
        }
        final SummaryDiskCache disk = diskFor(server);
        synchronized (channel.regionQueue) {
            if (config.shareCorrections) {
                for (final RegionJob queued : channel.regionQueue.values()) {
                    readyRegionPage(
                        worldAt(server, queued.dimIndex()), disk, queued, nowNanos
                    );
                }
            }
            final Iterator<Map.Entry<RegionJobKey, RegionJob>> jobs =
                channel.regionQueue.entrySet().iterator();
            while (jobs.hasNext()) {
                final RegionJob job = jobs.next().getValue();
                final ServerWorld world = worldAt(server, job.dimIndex());
                final boolean budgeted = world != null && config.shareCorrections;
                final RegionPageResult result;
                if (budgeted) {
                    result = readyRegionPage(world, disk, job, nowNanos);
                    if (result == null) {
                        return;
                    }
                } else {
                    result = unavailableRegionResult();
                }
                final boolean unchanged = result.mode() != Proto.PATCH_MODE_UNAVAILABLE
                    && job.request().sinceRevision() != Long.MIN_VALUE
                    && job.request().sinceRevision() == result.revision();
                final MapRegionPatchS2C response = new MapRegionPatchS2C(
                    job.reqId(), job.dimIndex(), job.lod(),
                    job.request().regionX(), job.request().regionZ(),
                    job.request().minLocalChunkX(), job.request().minLocalChunkZ(),
                    job.request().maxLocalChunkX(), job.request().maxLocalChunkZ(),
                    unchanged ? Proto.PATCH_MODE_UNCHANGED : result.mode(),
                    result.revision(), unchanged ? new byte[0] : result.body()
                );
                final byte[] encoded;
                final long encodeStartedNanos = System.nanoTime();
                try {
                    encoded = MsgCodec.encode(response);
                } catch (final ProtoException e) {
                    ConfluxMapMod.LOGGER.warn(
                        "[ConfluxMap] companion: dropped unencodable region patch for dim {} region {},{} lod {} ({})",
                        job.dimIndex(), job.request().regionX(), job.request().regionZ(),
                        job.lod(), e.getMessage()
                    );
                    jobs.remove();
                    continue;
                }
                final long encodeNanos = Math.max(
                    0L, System.nanoTime() - encodeStartedNanos
                );
                if (budgeted
                    && !channel.budget.allowBytes(encoded.length, nowNanos)) {
                    return;
                }
                sender.sendEncoded(response, encoded);
                final long deliveredNanos = System.nanoTime();
                final SyncPerformanceMonitor.CumulativeWork work = result.work();
                final long queueNanos = work.present()
                    ? Math.max(0L, work.startedNanos() - job.receivedAtNanos()) : 0L;
                final SyncPerformanceMonitor.CumulativeWork baselineWork =
                    result.baselineWork();
                final SyncPerformanceMonitor.DirectWork directWork =
                    baselineWork.present()
                        && baselineWork.startedNanos() >= job.receivedAtNanos()
                    ? new SyncPerformanceMonitor.DirectWork(
                        baselineWork.ioNanos(), baselineWork.computeNanos()
                    )
                    : SyncPerformanceMonitor.DirectWork.NONE;
                channel.performance.record(new SyncPerformanceMonitor.Delivery(
                    job.dimIndex(), job.lod(),
                    job.request().regionX(), job.request().regionZ(),
                    job.receivedAtNanos(), job.requestBytes(), response.mode(), encoded.length,
                    queueNanos, encodeNanos, deliveredNanos,
                    directWork, work
                ));
                jobs.remove();
            }
        }
    }

    private synchronized SummaryDiskCache diskFor(final MinecraftServer server) {
        final Path root = server.getSavePath(WorldSavePath.ROOT);
        if (diskCache == null || !root.equals(diskRoot)) {
            diskRoot = root;
            diskCache = new SummaryDiskCache(root);
        }
        return diskCache;
    }

    private RegionPageResult readyRegionPage(
        final ServerWorld world,
        final SummaryDiskCache disk,
        final RegionJob job,
        final long nowNanos
    ) {
        if (world == null) {
            return unavailableRegionResult();
        }
        final RegionPageKey key = new RegionPageKey(
            world, job.lod(), job.request().slice(), job.forceAbsolute(),
            job.correctionProfile()
        );
        final RegionPageTask task;
        synchronized (regionPageTasks) {
            RegionPageTask existing = regionPageTasks.get(key);
            if (existing == null) {
                makeRegionPageCapacity(nowNanos);
                if (regionPageTasks.size() >= REGION_PAGE_CACHE_LIMIT) {
                    return null;
                }
                final RegionBaselineTask baseline = regionBaseline(
                    world, job.lod(), job.request().slice(), job.forceAbsolute(), nowNanos
                );
                final RegionPageWork work = new RegionPageWork();
                final CompletableFuture<RegionPageResult> future = CompletableFuture.supplyAsync(
                    () -> {
                        work.start();
                        return buildRegionPage(
                            world, disk, job.lod(), job.request().slice(), baseline,
                            job.correctionProfile(), work
                        );
                    },
                    progressiveScanWorker
                );
                existing = new RegionPageTask(future, nowNanos);
                regionPageTasks.put(key, existing);
            } else {
                existing.lastRequestedAtNanos = nowNanos;
            }
            task = existing;
        }
        if (!task.future.isDone()) {
            return null;
        }
        try {
            final RegionPageResult result = task.future.join();
            if (result.mode() != Proto.PATCH_MODE_UNAVAILABLE
                && !regionPageCurrent(world, key.slice(), result)) {
                synchronized (regionPageTasks) {
                    regionPageTasks.remove(key, task);
                }
                return null;
            }
            return result;
        } catch (final CompletionException | java.util.concurrent.CancellationException e) {
            synchronized (regionPageTasks) {
                regionPageTasks.remove(key, task);
            }
            return null;
        }
    }

    private RegionPageResult buildRegionPage(
        final ServerWorld world,
        final SummaryDiskCache disk,
        final int lod,
        final ChunkRegionSlice slice,
        final RegionBaselineTask baselineTask,
        final CorrectionProfile correctionProfile,
        final RegionPageWork work
    ) {
        final String dimension = world.getRegistryKey().getValue().toString();
        final Path worldRoot = world.getServer().getSavePath(WorldSavePath.ROOT);
        final long mtimeBefore;
        long started = System.nanoTime();
        try {
            mtimeBefore = RegionStoragePaths.mcaMtimeMs(
                worldRoot, dimension, slice.regionX(), slice.regionZ()
            );
        } finally {
            work.addIo(System.nanoTime() - started);
        }
        final long liveEpochBefore = liveChunks.regionEpoch(
            dimension, slice.regionX(), slice.regionZ()
        );
        SummaryCodec.SampledRegion region;
        started = System.nanoTime();
        try {
            region = disk.loadCurrentCompleteSampled(
                dimension, slice.regionX(), slice.regionZ(), mtimeBefore, lod
            );
            if (region == null) {
                if (mtimeBefore <= 0L) {
                    region = emptySampledRegion(slice.regionX(), slice.regionZ(), lod);
                } else {
                    final int mcaX = Math.floorDiv(slice.regionX(), 2);
                    final int mcaZ = Math.floorDiv(slice.regionZ(), 2);
                    final Path path = RegionStoragePaths.mcaFile(
                        worldRoot, dimension, slice.regionX(), slice.regionZ()
                    );
                    final SummaryCodec.SampledRegion scanned = regionMcaScanner.scanRegion(
                        path, mcaX, mcaZ, mtimeBefore, lod, slice, summarizer
                    );
                    if (scanned == null || scanned.sourceMcaMtimeMs() != mtimeBefore) {
                        return unavailableRegionResult(work.snapshot());
                    }
                    region = scanned;
                }
            }
        } finally {
            work.addIo(System.nanoTime() - started);
        }
        started = System.nanoTime();
        try {
            region = liveChunks.overlay(dimension, region, slice);
        } finally {
            work.addCompute(System.nanoTime() - started);
        }
        final PatchBuilder.PreparedBaseline prepared;
        try {
            final PatchBuilder.PreparedBaseline completed = baselineTask.future.join();
            prepared = completed == null
                ? PatchBuilder.PreparedBaseline.absoluteOnly() : completed;
        } catch (final CompletionException | java.util.concurrent.CancellationException e) {
            return unavailableRegionResult(work.snapshot(), baselineTask.workSnapshot());
        }
        final RegionPatchBuilder.Result built;
        started = System.nanoTime();
        try {
            built = regionPatchBuilder.build(
                lod, slice, region, Long.MIN_VALUE, prepared, correctionProfile
            );
        } finally {
            work.addCompute(System.nanoTime() - started);
        }
        final long mtimeAfter;
        started = System.nanoTime();
        try {
            mtimeAfter = RegionStoragePaths.mcaMtimeMs(
                worldRoot, dimension, slice.regionX(), slice.regionZ()
            );
        } finally {
            work.addIo(System.nanoTime() - started);
        }
        final long liveEpochAfter = liveChunks.regionEpoch(
            dimension, slice.regionX(), slice.regionZ()
        );
        if (mtimeBefore != mtimeAfter || liveEpochBefore != liveEpochAfter) {
            throw new IllegalStateException("region source changed during page build");
        }
        return new RegionPageResult(
            built.mode(), built.revision(), built.body(), mtimeAfter, liveEpochAfter,
            work.snapshot(), baselineTask.workSnapshot()
        );
    }

    private boolean regionPageCurrent(
        final ServerWorld world,
        final ChunkRegionSlice slice,
        final RegionPageResult result
    ) {
        final String dimension = world.getRegistryKey().getValue().toString();
        final Path worldRoot = world.getServer().getSavePath(WorldSavePath.ROOT);
        return result.sourceMcaMtimeMs() == RegionStoragePaths.mcaMtimeMs(
            worldRoot, dimension, slice.regionX(), slice.regionZ()
        ) && result.liveEpoch() == liveChunks.regionEpoch(
            dimension, slice.regionX(), slice.regionZ()
        );
    }

    private static RegionPageResult unavailableRegionResult() {
        return unavailableRegionResult(
            SyncPerformanceMonitor.CumulativeWork.NONE,
            SyncPerformanceMonitor.CumulativeWork.NONE
        );
    }

    private static RegionPageResult unavailableRegionResult(
        final SyncPerformanceMonitor.CumulativeWork work
    ) {
        return unavailableRegionResult(work, SyncPerformanceMonitor.CumulativeWork.NONE);
    }

    private static RegionPageResult unavailableRegionResult(
        final SyncPerformanceMonitor.CumulativeWork work,
        final SyncPerformanceMonitor.CumulativeWork baselineWork
    ) {
        return new RegionPageResult(
            Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[0], Long.MIN_VALUE, Long.MIN_VALUE,
            work, baselineWork
        );
    }

    private RegionBaselineTask regionBaseline(
        final ServerWorld world,
        final int lod,
        final ChunkRegionSlice slice,
        final boolean forceAbsolute,
        final long nowNanos
    ) {
        if (forceAbsolute) {
            return new RegionBaselineTask(
                CompletableFuture.completedFuture(PatchBuilder.PreparedBaseline.absoluteOnly()),
                null,
                nowNanos
            );
        }
        final int chunksPerTile = 16 << lod;
        final int tileX = Math.floorDiv(slice.minChunkX(), chunksPerTile);
        final int tileZ = Math.floorDiv(slice.minChunkZ(), chunksPerTile);
        final RegionBaselineKey key = new RegionBaselineKey(
            world, lod, tileX, tileZ, pixelWindow(lod, slice)
        );
        synchronized (regionBaselineTasks) {
            RegionBaselineTask existing = regionBaselineTasks.get(key);
            if (existing == null) {
                makeRegionBaselineCapacity(nowNanos);
                if (regionBaselineTasks.size() >= REGION_BASELINE_CACHE_LIMIT) {
                    return new RegionBaselineTask(
                        CompletableFuture.completedFuture(
                            PatchBuilder.PreparedBaseline.absoluteOnly()
                        ),
                        null,
                        nowNanos
                    );
                }
                final ProgressiveRegionPatch.BaselineFactory factory = regionBaselineFactory(
                    world, lod, slice
                );
                final SummaryView view = baselineView(lod, slice);
                final RegionPageWork work = new RegionPageWork();
                final CompletableFuture<PatchBuilder.PreparedBaseline> future =
                    CompletableFuture.supplyAsync(() -> {
                        work.start();
                        final long started = System.nanoTime();
                        try {
                            final PatchBuilder.PreparedBaseline prepared = factory.prepare(view);
                            return prepared == null
                                ? PatchBuilder.PreparedBaseline.absoluteOnly() : prepared;
                        } catch (final RuntimeException e) {
                            return PatchBuilder.PreparedBaseline.absoluteOnly();
                        } finally {
                            work.addCompute(System.nanoTime() - started);
                        }
                    }, progressiveWorker);
                existing = new RegionBaselineTask(future, work, nowNanos);
                regionBaselineTasks.put(key, existing);
            } else {
                existing.lastRequestedAtNanos = nowNanos;
            }
            return existing;
        }
    }

    private static SummaryCodec.SampledRegion emptySampledRegion(
        final int regionX, final int regionZ, final int lod
    ) {
        final int stride = 1 << lod;
        final SummaryCodec.SampledChunk[] chunks = new SummaryCodec.SampledChunk[SummaryCodec.CHUNKS];
        java.util.Arrays.fill(chunks, SummaryCodec.SampledChunk.empty(stride));
        return new SummaryCodec.SampledRegion(regionX, regionZ, 0L, stride, chunks);
    }

    private static SummaryView baselineView(final int lod, final ChunkRegionSlice slice) {
        final int chunksPerTile = 16 << lod;
        final int tileX = Math.floorDiv(slice.minChunkX(), chunksPerTile);
        final int tileZ = Math.floorDiv(slice.minChunkZ(), chunksPerTile);
        return new SummaryView() {
            @Override
            public int lod() {
                return lod;
            }

            @Override
            public long originBlockX() {
                return (long) tileX * TileMath.blocksPerTile(lod);
            }

            @Override
            public long originBlockZ() {
                return (long) tileZ * TileMath.blocksPerTile(lod);
            }

            @Override
            public long revision() {
                return 0L;
            }

            @Override
            public byte[] presence() {
                return new byte[Proto.PATCH_PRESENCE_BYTES];
            }

            @Override
            public Pixel pixel(final int pixelX, final int pixelZ) {
                return null;
            }
        };
    }

    private int lodCeiling() {
        return cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD;
    }

    /** Worker-thread tile build: reads Anvil NBT, builds, and encodes, never touching the server thread. */
    private EncodedResponse buildResponse(
        final MinecraftServer server,
        final SummaryDiskCache disk,
        final TileJob job
    ) {
        long ioNanos = 0L;
        long computeNanos = 0L;
        final long buildStartedNanos = System.nanoTime();
        try {
            final ServerWorld world = worldAt(server, job.dimIndex());
            if (world == null || !config.shareCorrections) {
                return unavailable(job, ioNanos, computeNanos, buildStartedNanos);
            }
            if (job.lod() >= ProgressivePolicy.MIN_LOD) {
                return progressiveJob(world, disk, job, buildStartedNanos);
            }
            final SummaryTile summary;
            long started = System.nanoTime();
            try {
                summary = readTile(world, job.tileX(), job.tileZ(), job.lod(), disk);
            } finally {
                ioNanos += Math.max(0L, System.nanoTime() - started);
            }
            final PatchBuilder.Result result;
            started = System.nanoTime();
            try {
                result = buildPatch(
                    world, summary, job.sinceRevision(), job.forceAbsolute()
                );
            } finally {
                computeNanos += Math.max(0L, System.nanoTime() - started);
            }
            final MapPatchS2C patch = new MapPatchS2C(
                job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                result.mode(), result.revision(), result.presence(), result.body()
            );
            return EncodedResponse.of(
                patch, job.dimIndex(), job.lod(), job.tileX(), job.tileZ(), result.mode(),
                job.receivedAtNanos(), job.requestBytes(), buildStartedNanos,
                ioNanos, computeNanos, SyncPerformanceMonitor.CumulativeWork.NONE
            );
        } catch (final Exception e) {
            ConfluxMapMod.LOGGER.warn(
                "[ConfluxMap] companion: patch build failed for tile {},{} lod {} ({})",
                job.tileX(), job.tileZ(), job.lod(), e.getMessage()
            );
            return unavailable(job, ioNanos, computeNanos, buildStartedNanos);
        }
    }

    /** Immediate progressive response, answered from the shared scan cache on the calling thread. */
    private EncodedResponse progressiveResponse(
        final MinecraftServer server, final SummaryDiskCache disk, final TileJob job
    ) {
        final long responseStartedNanos = System.nanoTime();
        final ServerWorld world = worldAt(server, job.dimIndex());
        if (world == null) {
            return unavailable(job, 0L, 0L, responseStartedNanos);
        }
        return progressiveJob(world, disk, job, responseStartedNanos);
    }

    private EncodedResponse progressiveJob(
        final ServerWorld world,
        final SummaryDiskCache disk,
        final TileJob job,
        final long responseStartedNanos
    ) {
        final long now = System.nanoTime();
        final ProgressiveKey key = new ProgressiveKey(
            world, job.lod(), job.tileX(), job.tileZ(), job.forceAbsolute()
        );
        final ProgressiveRegionPatch task;
        synchronized (progressiveTasks) {
            ProgressiveRegionPatch existing = progressiveTasks.get(key);
            if (existing == null) {
                evictProgressiveTasks(now, true);
                if (progressiveTasks.size() >= ProgressivePolicy.MAX_ACTIVE_TILES) {
                    return EncodedResponse.of(
                        new MapPatchS2C(
                            job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                            Proto.PATCH_MODE_PARTIAL, 0L, new byte[Proto.PATCH_PRESENCE_BYTES],
                            ProgressiveRegionPatch.emptyPatchBody()
                        ),
                        job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                        Proto.PATCH_MODE_PARTIAL, job.receivedAtNanos(), job.requestBytes(),
                        responseStartedNanos, 0L,
                        Math.max(0L, System.nanoTime() - responseStartedNanos),
                        SyncPerformanceMonitor.CumulativeWork.NONE
                    );
                }
                final String dimension = world.getRegistryKey().getValue().toString();
                final Path worldRoot = world.getServer().getSavePath(WorldSavePath.ROOT);
                existing = new ProgressiveRegionPatch(
                    dimension,
                    worldRoot,
                    disk,
                    liveChunks,
                    summarizer,
                    patchBuilder,
                    progressiveWorker,
                    progressiveScanWorker,
                    job.lod(),
                    job.tileX(),
                    job.tileZ(),
                    job.forceAbsolute()
                        ? ignored -> PatchBuilder.PreparedBaseline.absoluteOnly()
                        : baselineFactory(world),
                    pos -> readChunkNbt(world, pos),
                    now
                );
                progressiveTasks.put(key, existing);
            }
            task = existing;
        }
        final ProgressiveRegionPatch.Response response = task.response(job.sinceRevision(), now);
        final MapPatchS2C patch = new MapPatchS2C(
            job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
            response.mode(), response.revision(), response.presence(), response.body()
        );
        return EncodedResponse.of(
            patch, job.dimIndex(), job.lod(), job.tileX(), job.tileZ(), response.mode(),
            job.receivedAtNanos(), job.requestBytes(), responseStartedNanos,
            0L, Math.max(0L, System.nanoTime() - responseStartedNanos), task.workSnapshot()
        );
    }

    private ProgressiveRegionPatch.BaselineFactory baselineFactory(final ServerWorld world) {
        return baselineFactory(world, null);
    }

    private ProgressiveRegionPatch.BaselineFactory regionBaselineFactory(
        final ServerWorld world, final int lod, final ChunkRegionSlice slice
    ) {
        return baselineFactory(world, pixelWindow(lod, slice));
    }

    private static PixelWindow pixelWindow(final int lod, final ChunkRegionSlice slice) {
        final int chunksPerTile = 16 << lod;
        final int samplesPerChunk = 16 >> lod;
        final int minX = Math.floorMod(slice.minChunkX(), chunksPerTile) * samplesPerChunk;
        final int minZ = Math.floorMod(slice.minChunkZ(), chunksPerTile) * samplesPerChunk;
        return new PixelWindow(
            minX,
            minZ,
            minX + slice.width() * samplesPerChunk - 1,
            minZ + slice.height() * samplesPerChunk - 1
        );
    }

    private ProgressiveRegionPatch.BaselineFactory baselineFactory(
        final ServerWorld world, final PixelWindow window
    ) {
        final WorldPreset preset = WorldPresetDetector.detect(world);
        if (preset == WorldPreset.FLAT) {
            final Optional<FlatBaseline> flat = FlatWorldBaseline.of(world);
            if (flat.isPresent()) {
                return summary -> patchBuilder.prepareFromUniform(summary, flat.get(), false);
            }
        }
        if (NativeLib.available() && preset.predictable()) {
            final DimensionId dimension = Dimensions.of(world);
            final int nativeDim = PredictionDimensions.nativeDim(dimension);
            final java.util.OptionalInt version = McVersions.toCubiomes(MinecraftVersion.current());
            if (nativeDim != Integer.MIN_VALUE && version.isPresent()) {
                final long seed = world.getSeed();
                final NativeBaselineSampler sampler = new NativeBaselineSampler(
                    version.getAsInt(), seed, nativeDim, preset.cubiomesFlags()
                );
                if (window != null) {
                    return summary -> patchBuilder.prepareFromSamplerWindow(
                        summary, sampler, dimension, seed, false,
                        window.minX(), window.minZ(), window.maxX(), window.maxZ()
                    );
                }
                return summary -> patchBuilder.prepareFromSampler(
                    summary, sampler, dimension, seed, false
                );
            }
        }
        return ignored -> PatchBuilder.PreparedBaseline.absoluteOnly();
    }

    /** Gives one watched center-priority coarse tile a bounded main-thread slice until completion. */
    private void tickProgressive(final MinecraftServer server, final long nowNanos) {
        final ProgressiveKey selectedKey;
        final ProgressiveRegionPatch next;
        synchronized (progressiveTasks) {
            evictProgressiveTasks(nowNanos, false);
            if (progressiveTasks.isEmpty()) {
                activeProgressiveKey = null;
                return;
            }
            ProgressiveRegionPatch active = activeProgressiveKey == null
                ? null : progressiveTasks.get(activeProgressiveKey);
            final boolean activeWatched = activeProgressiveKey != null
                && watched(server, activeProgressiveKey);
            if (active == null || active.complete()
                || (!activeWatched && hasWatchedIncomplete(server))) {
                activeProgressiveKey = firstIncomplete(server, true);
                if (activeProgressiveKey == null) {
                    activeProgressiveKey = firstIncomplete(server, false);
                }
                active = activeProgressiveKey == null ? null : progressiveTasks.get(activeProgressiveKey);
            }
            if (active == null) {
                return;
            }
            selectedKey = activeProgressiveKey;
            next = active;
        }
        // The task retains the world/disk it was created for. A server-session change constructs a
        // new RegionSummaryService, and close() invalidates every old task before those are reused.
        next.tick(
            PROGRESSIVE_MAX_CHUNKS_OR_REGIONS_PER_TICK,
            PROGRESSIVE_MAX_NANOS_PER_TICK,
            System::nanoTime
        );
        if (next.complete()) {
            synchronized (progressiveTasks) {
                if (selectedKey.equals(activeProgressiveKey)) {
                    activeProgressiveKey = null;
                }
            }
        }
    }

    private boolean hasWatchedIncomplete(final MinecraftServer server) {
        return firstIncomplete(server, true) != null;
    }

    /** Request insertion order is center-first, so the first watched task is the visual priority. */
    private ProgressiveKey firstIncomplete(final MinecraftServer server, final boolean watchedOnly) {
        for (final Map.Entry<ProgressiveKey, ProgressiveRegionPatch> entry : progressiveTasks.entrySet()) {
            if (!entry.getValue().complete() && (!watchedOnly || watched(server, entry.getKey()))) {
                return entry.getKey();
            }
        }
        return null;
    }

    private boolean watched(final MinecraftServer server, final ProgressiveKey key) {
        final int dimIndex = Dimensions.indexOf(server, key.world());
        return dimIndex >= 0
            && invalidations.watches(dimIndex, key.lod(), key.tileX(), key.tileZ());
    }

    /** Removes idle tasks; under capacity pressure, completed entries are safe to recreate. */
    private void evictProgressiveTasks(final long nowNanos, final boolean forCapacity) {
        ProgressiveTaskEviction.evict(progressiveTasks, nowNanos, forCapacity, this::watchedForEviction);
    }

    private boolean watchedForEviction(final ProgressiveKey key) {
        // A world unloaded mid-session reports a null server; nobody can still watch it,
        // so it must count as unwatched instead of NPE-ing the eviction scan.
        final MinecraftServer taskServer = key.world().getServer();
        return taskServer != null && watched(taskServer, key);
    }

    private static EncodedResponse unavailable(
        final TileJob job,
        final long ioNanos,
        final long computeNanos,
        final long buildStartedNanos
    ) {
        return EncodedResponse.of(
            new MapPatchS2C(job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]),
            job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
            Proto.PATCH_MODE_UNAVAILABLE, job.receivedAtNanos(), job.requestBytes(),
            buildStartedNanos, ioNanos, computeNanos,
            SyncPerformanceMonitor.CumulativeWork.NONE
        );
    }

    private PatchBuilder.Result buildPatch(
        final ServerWorld world,
        final SummaryTile summary,
        final long sinceRevision,
        final boolean forceAbsolute
    ) {
        if (forceAbsolute) {
            return patchBuilder.buildAbsolute(summary, sinceRevision);
        }
        // Residual patches assume the client predicts the identical baseline, so the sampler must
        // mirror the client's preset-derived generator flags. A superflat dim diffs against its
        // uniform surface instead; debug/custom presets have no shared baseline and ship absolute.
        final WorldPreset preset = WorldPresetDetector.detect(world);
        if (preset == WorldPreset.FLAT) {
            final Optional<FlatBaseline> flat = FlatWorldBaseline.of(world);
            if (flat.isPresent()) {
                final PatchBuilder.Result residual = patchBuilder.buildFromUniform(
                    summary, sinceRevision, flat.get(), false
                );
                if (residual.mode() != Proto.PATCH_MODE_UNAVAILABLE) {
                    return residual;
                }
            }
        }
        if (NativeLib.available() && preset.predictable()) {
            final DimensionId dimension = Dimensions.of(world);
            final int nativeDim = PredictionDimensions.nativeDim(dimension);
            final java.util.OptionalInt version = McVersions.toCubiomes(MinecraftVersion.current());
            if (nativeDim != Integer.MIN_VALUE && version.isPresent()) {
                final PatchBuilder.Result residual = patchBuilder.buildFromSampler(
                    summary, sinceRevision,
                    new NativeBaselineSampler(version.getAsInt(), world.getSeed(), nativeDim, preset.cubiomesFlags()),
                    dimension,
                    world.getSeed(), false
                );
                if (residual.mode() != Proto.PATCH_MODE_UNAVAILABLE) {
                    return residual;
                }
            }
        }
        return patchBuilder.buildAbsolute(summary, sinceRevision);
    }

    /** Reads every LOD-0 region covered by one coarse prediction tile. */
    private SummaryTile readTile(
        final ServerWorld world, final int tileX, final int tileZ, final int lod, final SummaryDiskCache disk
    ) {
        final int regionsPerSide = 1 << Math.max(0, lod);
        final long baseRegionX = (long) tileX * regionsPerSide;
        final long baseRegionZ = (long) tileZ * regionsPerSide;
        if (baseRegionX < Integer.MIN_VALUE || baseRegionX > Integer.MAX_VALUE
            || baseRegionZ < Integer.MIN_VALUE || baseRegionZ > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("requested tile is outside the region coordinate range");
        }
        final String dimension = world.getRegistryKey().getValue().toString();
        final List<SummaryCodec.Region> regions = new ArrayList<>(regionsPerSide * regionsPerSide);
        for (int dz = 0; dz < regionsPerSide; dz++) {
            for (int dx = 0; dx < regionsPerSide; dx++) {
                final int regionX = (int) baseRegionX + dx;
                final int regionZ = (int) baseRegionZ + dz;
                regions.add(readRegion(world, dimension, regionX, regionZ, disk));
            }
        }
        return new SummaryTile(lod, tileX, tileZ, regions);
    }

    private SummaryCodec.Region readRegion(
        final ServerWorld world, final String dimension, final int regionX, final int regionZ, final SummaryDiskCache disk
    ) {
        final Path worldRoot = world.getServer().getSavePath(WorldSavePath.ROOT);
        final long mtimeBefore = RegionStoragePaths.mcaMtimeMs(worldRoot, dimension, regionX, regionZ);
        final SummaryCodec.Region cached = disk.loadCurrent(dimension, regionX, regionZ, mtimeBefore);
        if (cached != null && cached.sourceMcaMtimeMs() > 0L) {
            return liveChunks.overlay(dimension, cached);
        }
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final ChunkPos pos = new ChunkPos(regionX * 16 + x, regionZ * 16 + z);
                final int index = z * 16 + x;
                final SummaryCodec.Chunk live = liveChunks.get(dimension, chunkX(pos), chunkZ(pos));
                if (live != null) {
                    chunks[index] = live;
                    continue;
                }
                if (mtimeBefore <= 0L) {
                    chunks[index] = SummaryCodec.Chunk.empty();
                    continue;
                }
                if (cached != null && cached.chunks()[index].generated()) {
                    chunks[index] = cached.chunks()[index];
                    continue;
                }
                final NbtCompound nbt;
                try {
                    nbt = readChunkNbt(world, pos);
                } catch (IOException ignored) {
                    // A missing/corrupt chunk is represented by generated=false.
                    chunks[index] = SummaryCodec.Chunk.empty();
                    continue;
                }
                chunks[index] = nbt == null ? SummaryCodec.Chunk.empty() : summarizer.summarize(nbt);
            }
        }
        final long mtimeAfter = RegionStoragePaths.mcaMtimeMs(worldRoot, dimension, regionX, regionZ);
        final long sourceMtime = mtimeBefore > 0L && mtimeBefore == mtimeAfter ? mtimeAfter : 0L;
        final SummaryCodec.Region region = new SummaryCodec.Region(regionX, regionZ, sourceMtime, chunks);
        if (sourceMtime > 0L) {
            try {
                disk.save(dimension, region);
            } catch (IOException ignored) {
                // Memory results are still valid if the optional cache cannot be written.
            }
        }
        return liveChunks.overlay(dimension, region);
    }

    static NbtCompound readChunkNbt(final ServerWorld world, final ChunkPos pos) throws IOException {
        //#if MC>=12100
        //$$ try {
        //$$     return world.getChunkManager().chunkLoadingManager.getNbt(pos).join().orElse(null);
        //$$ } catch (final CompletionException e) {
        //$$     throw new IOException("failed to read chunk " + pos, e.getCause());
        //$$ }
        //#elseif MC>=12000
        //$$ try {
        //$$     return world.getChunkManager().threadedAnvilChunkStorage.getNbt(pos).join().orElse(null);
        //$$ } catch (final CompletionException e) {
        //$$     throw new IOException("failed to read chunk " + pos, e.getCause());
        //$$ }
        //#else
        return world.getChunkManager().threadedAnvilChunkStorage.getNbt(pos);
        //#endif
    }

    private static ServerWorld worldAt(final MinecraftServer server, final int index) {
        int i = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (i++ == index) {
                return world;
            }
        }
        return null;
    }

    private void onRegionChanged(final String dimension, final int regionX, final int regionZ) {
        changedRegions.add(new ChangedRegion(dimension, regionX, regionZ));
        if (changedRegionCount.incrementAndGet() > CHANGED_REGIONS_LIMIT) {
            // Invalidate signals coalesce per region, so dropping the oldest entry keeps the
            // freshest mutations represented; dropping the newest would strand regions that
            // keep changing under sustained worldgen load.
            if (changedRegions.poll() != null) {
                changedRegionCount.decrementAndGet();
            }
            if (changedRegionOverflowLogged.compareAndSet(false, true)) {
                ConfluxMapMod.LOGGER.warn(
                    "[ConfluxMap] companion: live-chunk change backlog exceeded {} regions; dropping oldest changes until the tick drain catches up",
                    CHANGED_REGIONS_LIMIT
                );
            }
        }
    }

    private void drainChangedRegions(final MinecraftServer server) {
        ChangedRegion changed;
        while ((changed = changedRegions.poll()) != null) {
            changedRegionCount.decrementAndGet();
            final int dimIndex = Dimensions.indexOf(server, DimensionId.parse(changed.dimension()));
            if (dimIndex >= 0) {
                invalidations.invalidateRegion(dimIndex, changed.regionX(), changed.regionZ());
                regionInvalidations.invalidateRegion(dimIndex, changed.regionX(), changed.regionZ());
                synchronized (regionPageTasks) {
                    final Iterator<Map.Entry<RegionPageKey, RegionPageTask>> pages =
                        regionPageTasks.entrySet().iterator();
                    while (pages.hasNext()) {
                        final Map.Entry<RegionPageKey, RegionPageTask> page = pages.next();
                        if (Dimensions.indexOf(server, page.getKey().world()) == dimIndex
                            && page.getKey().slice().regionX() == changed.regionX()
                            && page.getKey().slice().regionZ() == changed.regionZ()) {
                            page.getValue().future.cancel(false);
                            pages.remove();
                        }
                    }
                }
                synchronized (progressiveTasks) {
                    for (final Map.Entry<ProgressiveKey, ProgressiveRegionPatch> entry
                        : progressiveTasks.entrySet()) {
                        if (Dimensions.indexOf(server, entry.getKey().world()) == dimIndex) {
                            entry.getValue().invalidateRegion(changed.regionX(), changed.regionZ());
                        }
                    }
                }
                for (final PlayerChannel channel : channels.values()) {
                    final int changedRegionX = changed.regionX();
                    final int changedRegionZ = changed.regionZ();
                    channel.tiles.restartMatching(key -> key.dimIndex() == dimIndex
                        && Math.floorDiv(changedRegionX, 1 << key.lod()) == key.tileX()
                        && Math.floorDiv(changedRegionZ, 1 << key.lod()) == key.tileZ());
                }
            }
        }
    }

    private void evictRegionPageTasks(final long nowNanos) {
        synchronized (regionPageTasks) {
            final Iterator<Map.Entry<RegionPageKey, RegionPageTask>> iterator =
                regionPageTasks.entrySet().iterator();
            while (iterator.hasNext()) {
                final RegionPageTask task = iterator.next().getValue();
                final boolean expired = nowNanos - task.lastRequestedAtNanos
                    > ProgressivePolicy.IDLE_TTL_NANOS;
                final boolean overLimit = regionPageTasks.size() > REGION_PAGE_CACHE_LIMIT
                    && task.future.isDone();
                if (expired || overLimit) {
                    task.future.cancel(false);
                    iterator.remove();
                }
                if (regionPageTasks.size() <= REGION_PAGE_CACHE_LIMIT && !expired) {
                    break;
                }
            }
        }
    }

    private void makeRegionPageCapacity(final long nowNanos) {
        final Iterator<Map.Entry<RegionPageKey, RegionPageTask>> iterator =
            regionPageTasks.entrySet().iterator();
        while (regionPageTasks.size() >= REGION_PAGE_CACHE_LIMIT && iterator.hasNext()) {
            final RegionPageTask task = iterator.next().getValue();
            final boolean expired = nowNanos - task.lastRequestedAtNanos
                > ProgressivePolicy.IDLE_TTL_NANOS;
            if (expired || task.future.isDone()) {
                task.future.cancel(false);
                iterator.remove();
            }
        }
    }

    private void evictRegionBaselineTasks(final long nowNanos) {
        synchronized (regionBaselineTasks) {
            final Iterator<Map.Entry<RegionBaselineKey, RegionBaselineTask>> iterator =
                regionBaselineTasks.entrySet().iterator();
            while (iterator.hasNext()) {
                final RegionBaselineTask task = iterator.next().getValue();
                final boolean expired = nowNanos - task.lastRequestedAtNanos
                    > ProgressivePolicy.IDLE_TTL_NANOS;
                final boolean overLimit = regionBaselineTasks.size() > REGION_BASELINE_CACHE_LIMIT
                    && task.future.isDone();
                if (expired || overLimit) {
                    task.future.cancel(false);
                    iterator.remove();
                }
                if (regionBaselineTasks.size() <= REGION_BASELINE_CACHE_LIMIT && !expired) {
                    break;
                }
            }
        }
    }

    private void makeRegionBaselineCapacity(final long nowNanos) {
        final Iterator<Map.Entry<RegionBaselineKey, RegionBaselineTask>> iterator =
            regionBaselineTasks.entrySet().iterator();
        while (regionBaselineTasks.size() >= REGION_BASELINE_CACHE_LIMIT && iterator.hasNext()) {
            final RegionBaselineTask task = iterator.next().getValue();
            final boolean expired = nowNanos - task.lastRequestedAtNanos
                > ProgressivePolicy.IDLE_TTL_NANOS;
            if (expired || task.future.isDone()) {
                task.future.cancel(false);
                iterator.remove();
            }
        }
    }

    private static int chunkX(final ChunkPos pos) {
        //#if MC>=260100
        //$$ return pos.x();
        //#else
        return pos.x;
        //#endif
    }

    private static int chunkZ(final ChunkPos pos) {
        //#if MC>=260100
        //$$ return pos.z();
        //#else
        return pos.z;
        //#endif
    }

}
