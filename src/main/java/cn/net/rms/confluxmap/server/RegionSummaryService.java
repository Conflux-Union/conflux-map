package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.FlatBaseline;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
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
 * <p>Delivery is queued per player ({@link PatchDispatcher}): a request's tiles are enqueued,
 * as many as the byte budget allows are sent inline, and the remainder drains on subsequent
 * server ticks as the token bucket refills. Only queue overflow is answered with
 * {@code ERR_RATE_LIMITED}; a temporarily exhausted byte budget never drops tiles.
 */
public final class RegionSummaryService {
    private static final int PROGRESSIVE_MIN_LOD = 3;
    private static final int PROGRESSIVE_MAX_ACTIVE_TILES = 24;
    private static final int PROGRESSIVE_MAX_CHUNKS_OR_REGIONS_PER_TICK = 2_048;
    private static final long PROGRESSIVE_MAX_NANOS_PER_TICK = 4_000_000L;
    private static final long PROGRESSIVE_IDLE_TTL_NANOS = 30_000_000_000L;

    private final ServerConfig config;
    private final ChunkSummarizer summarizer = new ChunkSummarizer(new RegistryMapColors());
    private final PatchBuilder patchBuilder = new PatchBuilder();
    private final Map<UUID, PlayerChannel> channels = new ConcurrentHashMap<>();
    private final LiveChunkSummaryTracker liveChunks;
    private final ExecutorService progressiveWorker = Executors.newSingleThreadExecutor(runnable -> {
        final Thread thread = new Thread(runnable, "ConfluxMap-progressive-patches");
        thread.setDaemon(true);
        return thread;
    });
    /** Access-ordered global task cache; identical player requests reuse the same validated scan. */
    private final LinkedHashMap<ProgressiveKey, ProgressiveRegionPatch> progressiveTasks =
        new LinkedHashMap<>(16, 0.75f, true);
    private int progressiveCursor;
    private Path diskRoot;
    private SummaryDiskCache diskCache;

    private record ProgressiveKey(ServerWorld world, int lod, int tileX, int tileZ) {
    }

    private static final class PlayerChannel {
        final PatchDispatcher dispatcher;
        volatile Consumer<Message> sender;

        PlayerChannel(final PatchDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }
    }

    public RegionSummaryService(final ServerConfig config) {
        this.config = config;
        this.liveChunks = new LiveChunkSummaryTracker(config, summarizer);
    }

    /** Starts serving a loaded chunk from memory and enrolls it in bounded live refreshes. */
    public void onChunkLoad(final ServerWorld world, final WorldChunk chunk) {
        liveChunks.onChunkLoad(world, chunk);
    }

    /** Captures the final in-memory state and queues one batched level-0 cache write. */
    public void onChunkUnload(final ServerWorld world, final WorldChunk chunk) {
        liveChunks.onChunkUnload(world, chunk);
    }

    public void request(
        final MinecraftServer server,
        final ServerPlayerEntity player,
        final MapViewReqC2S request,
        final Consumer<Message> sender
    ) {
        final long now = System.nanoTime();
        final PlayerChannel channel = channels.computeIfAbsent(player.getUuid(), ignored -> new PlayerChannel(
            new PatchDispatcher(
                new PlayerBudget(config.maxBytesPerSecondPerPlayer, config.minRequestIntervalMs),
                config.maxPendingTilesPerPlayer
            )
        ));
        channel.sender = sender;
        if (request.lod() > lodCeiling() || request.tiles().size() > config.maxTilesPerRequest
            || request.dimIndex() < 0 || !channel.dispatcher.budget().beginRequest(now)) {
            sender.accept(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction request is rate limited"));
            return;
        }
        final List<PatchDispatcher.TileJob> jobs = new ArrayList<>(request.tiles().size());
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            jobs.add(new PatchDispatcher.TileJob(
                request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(), tile.sinceRevision()
            ));
        }
        final int overflow = channel.dispatcher.submit(jobs);
        if (overflow > 0) {
            sender.accept(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction queue is full"));
        }
        liveChunks.nominate(request, now);
        drain(server, channel, now);
    }

    /** Server tick: keep draining queued patches as each player's byte budget refills. */
    public void tick(final MinecraftServer server) {
        final SummaryDiskCache disk = diskFor(server);
        liveChunks.tick(server, disk);
        final long now = System.nanoTime();
        tickProgressive(now);
        for (final PlayerChannel channel : channels.values()) {
            if (channel.dispatcher.queued() > 0 && channel.sender != null) {
                drain(server, channel, now);
            }
        }
    }

    public void remove(final UUID player) {
        final PlayerChannel channel = channels.remove(player);
        if (channel != null) {
            channel.dispatcher.clear();
        }
    }

    /** Captures chunks still loaded when vanilla begins its final save/unload sequence. */
    public void prepareStop() {
        liveChunks.prepareStop();
    }

    /** Flushes captured unloads after vanilla has saved every dimension, then drops session state. */
    public void close(final MinecraftServer server) {
        liveChunks.close(server, diskFor(server));
        channels.clear();
        synchronized (progressiveTasks) {
            for (final ProgressiveRegionPatch task : progressiveTasks.values()) {
                task.close();
            }
            progressiveTasks.clear();
        }
        progressiveWorker.shutdownNow();
        try {
            progressiveWorker.awaitTermination(2L, TimeUnit.SECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void drain(final MinecraftServer server, final PlayerChannel channel, final long nowNanos) {
        final Consumer<Message> sender = channel.sender;
        if (sender == null) {
            return;
        }
        final SummaryDiskCache disk = diskFor(server);
        channel.dispatcher.drain(nowNanos, job -> buildJob(server, disk, job), sender);
    }

    private synchronized SummaryDiskCache diskFor(final MinecraftServer server) {
        final Path root = server.getSavePath(WorldSavePath.ROOT);
        if (diskCache == null || !root.equals(diskRoot)) {
            diskRoot = root;
            diskCache = new SummaryDiskCache(root);
        }
        return diskCache;
    }

    private int lodCeiling() {
        return cn.net.rms.confluxmap.core.util.TileMath.MAX_LOD;
    }

    private MapPatchS2C buildJob(
        final MinecraftServer server,
        final SummaryDiskCache disk,
        final PatchDispatcher.TileJob job
    ) {
        try {
            final ServerWorld world = worldAt(server, job.dimIndex());
            if (world == null || !config.shareCorrections) {
                return unavailable(job);
            }
            if (job.lod() >= PROGRESSIVE_MIN_LOD) {
                return progressiveJob(world, disk, job);
            }
            final SummaryTile summary = readTile(world, job.tileX(), job.tileZ(), job.lod(), disk);
            final PatchBuilder.Result result = buildPatch(world, summary, job.sinceRevision());
            return new MapPatchS2C(job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                result.mode(), result.revision(), result.presence(), result.body());
        } catch (final Exception e) {
            ConfluxMapMod.LOGGER.warn(
                "companion: patch build failed for tile {},{} lod {} ({})",
                job.tileX(), job.tileZ(), job.lod(), e.getMessage()
            );
            return unavailable(job);
        }
    }

    private MapPatchS2C progressiveJob(
        final ServerWorld world,
        final SummaryDiskCache disk,
        final PatchDispatcher.TileJob job
    ) {
        final long now = System.nanoTime();
        final ProgressiveKey key = new ProgressiveKey(world, job.lod(), job.tileX(), job.tileZ());
        final ProgressiveRegionPatch task;
        synchronized (progressiveTasks) {
            ProgressiveRegionPatch existing = progressiveTasks.get(key);
            if (existing == null) {
                evictProgressiveTasks(now, true);
                if (progressiveTasks.size() >= PROGRESSIVE_MAX_ACTIVE_TILES) {
                    return new MapPatchS2C(
                        job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
                        Proto.PATCH_MODE_PARTIAL, 0L, new byte[Proto.PATCH_PRESENCE_BYTES],
                        ProgressiveRegionPatch.emptyPatchBody()
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
                    job.lod(),
                    job.tileX(),
                    job.tileZ(),
                    baselineFactory(world),
                    pos -> readChunkNbt(world, pos),
                    now
                );
                progressiveTasks.put(key, existing);
            }
            task = existing;
        }
        final ProgressiveRegionPatch.Response response = task.response(job.sinceRevision(), now);
        return new MapPatchS2C(
            job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
            response.mode(), response.revision(), response.presence(), response.body()
        );
    }

    private ProgressiveRegionPatch.BaselineFactory baselineFactory(final ServerWorld world) {
        final WorldPreset preset = WorldPresetDetector.detect(world);
        if (preset == WorldPreset.FLAT) {
            final Optional<FlatBaseline> flat = FlatWorldBaseline.of(world);
            if (flat.isPresent()) {
                return summary -> patchBuilder.prepareFromUniform(summary, flat.get(), false);
            }
        }
        if (NativeLib.available() && preset.predictable()) {
            final int nativeDim = PredictionDimensions.isEnd(
                cn.net.rms.confluxmap.core.model.DimensionId.of(
                    world.getRegistryKey().getValue().getNamespace(),
                    world.getRegistryKey().getValue().getPath()
                )
            ) ? 1 : 0;
            final java.util.OptionalInt version = McVersions.toCubiomes(MinecraftVersion.current());
            if (version.isPresent()) {
                final long seed = world.getSeed();
                final NativeBaselineSampler sampler = new NativeBaselineSampler(
                    version.getAsInt(), seed, nativeDim, preset.cubiomesFlags()
                );
                return summary -> patchBuilder.prepareFromSampler(
                    summary, sampler, nativeDim == 1, seed, false
                );
            }
        }
        return ignored -> PatchBuilder.PreparedBaseline.absoluteOnly();
    }

    /** Gives one active coarse tile a bounded main-thread slice, rotating fairly across players. */
    private void tickProgressive(final long nowNanos) {
        final ProgressiveRegionPatch next;
        synchronized (progressiveTasks) {
            evictProgressiveTasks(nowNanos, false);
            if (progressiveTasks.isEmpty()) {
                return;
            }
            final List<ProgressiveRegionPatch> tasks = new ArrayList<>(progressiveTasks.values());
            progressiveCursor = Math.floorMod(progressiveCursor, tasks.size());
            next = tasks.get(progressiveCursor);
            progressiveCursor = (progressiveCursor + 1) % tasks.size();
        }
        // The task retains the world/disk it was created for. A server-session change constructs a
        // new RegionSummaryService, and close() invalidates every old task before those are reused.
        next.tick(
            PROGRESSIVE_MAX_CHUNKS_OR_REGIONS_PER_TICK,
            PROGRESSIVE_MAX_NANOS_PER_TICK,
            nowNanos,
            System::nanoTime
        );
    }

    /** Removes idle tasks; under capacity pressure, completed entries are safe to recreate. */
    private void evictProgressiveTasks(final long nowNanos, final boolean forCapacity) {
        final Iterator<Map.Entry<ProgressiveKey, ProgressiveRegionPatch>> iterator =
            progressiveTasks.entrySet().iterator();
        while (iterator.hasNext()) {
            final ProgressiveRegionPatch task = iterator.next().getValue();
            final boolean expired = nowNanos - task.lastRequestedAtNanos() > PROGRESSIVE_IDLE_TTL_NANOS;
            final boolean capacityVictim = forCapacity
                && progressiveTasks.size() >= PROGRESSIVE_MAX_ACTIVE_TILES
                && task.complete();
            if (expired || capacityVictim) {
                task.close();
                iterator.remove();
            }
            if (forCapacity && progressiveTasks.size() < PROGRESSIVE_MAX_ACTIVE_TILES) {
                return;
            }
        }
    }

    private static MapPatchS2C unavailable(final PatchDispatcher.TileJob job) {
        return new MapPatchS2C(job.reqId(), job.dimIndex(), job.lod(), job.tileX(), job.tileZ(),
            Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]);
    }

    private PatchBuilder.Result buildPatch(
        final ServerWorld world, final SummaryTile summary, final long sinceRevision
    ) {
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
            final int nativeDim = PredictionDimensions.isEnd(
                cn.net.rms.confluxmap.core.model.DimensionId.of(
                    world.getRegistryKey().getValue().getNamespace(), world.getRegistryKey().getValue().getPath()
                )
            ) ? 1 : 0;
            final java.util.OptionalInt version = McVersions.toCubiomes(MinecraftVersion.current());
            if (version.isPresent()) {
                final PatchBuilder.Result residual = patchBuilder.buildFromSampler(
                    summary, sinceRevision,
                    new NativeBaselineSampler(version.getAsInt(), world.getSeed(), nativeDim, preset.cubiomesFlags()),
                    nativeDim == 1,
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
