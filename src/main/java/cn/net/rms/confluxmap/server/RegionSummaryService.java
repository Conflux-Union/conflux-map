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
import java.util.Optional;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.compat.MinecraftVersion;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionException;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.WorldSavePath;

/**
 * Serves summary-backed corrections without asking the world chunk manager to generate chunks.
 *
 * <p>Delivery is queued per player ({@link PatchDispatcher}): a request's tiles are enqueued,
 * as many as the byte budget allows are sent inline, and the remainder drains on subsequent
 * server ticks as the token bucket refills. Only queue overflow is answered with
 * {@code ERR_RATE_LIMITED}; a temporarily exhausted byte budget never drops tiles.
 */
public final class RegionSummaryService {
    private final ServerConfig config;
    private final ChunkSummarizer summarizer = new ChunkSummarizer(new RegistryMapColors());
    private final PatchBuilder patchBuilder = new PatchBuilder();
    private final Map<UUID, PlayerChannel> channels = new ConcurrentHashMap<>();

    private static final class PlayerChannel {
        final PatchDispatcher dispatcher;
        volatile Consumer<Message> sender;

        PlayerChannel(final PatchDispatcher dispatcher) {
            this.dispatcher = dispatcher;
        }
    }

    public RegionSummaryService(final ServerConfig config) {
        this.config = config;
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
        if (request.lod() > config.maxPatchLod || request.tiles().size() > config.maxTilesPerRequest
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
        drain(server, channel, now);
    }

    /** Server tick: keep draining queued patches as each player's byte budget refills. */
    public void tick(final MinecraftServer server) {
        final long now = System.nanoTime();
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

    private void drain(final MinecraftServer server, final PlayerChannel channel, final long nowNanos) {
        final Consumer<Message> sender = channel.sender;
        if (sender == null) {
            return;
        }
        final SummaryDiskCache disk = new SummaryDiskCache(server.getSavePath(WorldSavePath.ROOT));
        channel.dispatcher.drain(nowNanos, job -> buildJob(server, disk, job), sender);
    }

    private MapPatchS2C buildJob(final MinecraftServer server, final SummaryDiskCache disk, final PatchDispatcher.TileJob job) {
        try {
            final ServerWorld world = worldAt(server, job.dimIndex());
            if (world == null || !config.shareCorrections || job.lod() > PatchBuilder.MAX_SUPPORTED_LOD) {
                return unavailable(job);
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
        final SummaryCodec.Region cached = disk.load(dimension, regionX, regionZ);
        // A zero source mtime is the conservative marker used by the live reader: without a
        // reliable region-file timestamp, never serve an older summary over a newly written chunk.
        if (cached != null && cached.sourceMcaMtimeMs() > 0L) {
            return cached;
        }
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final ChunkPos pos = new ChunkPos(regionX * 16 + x, regionZ * 16 + z);
                final NbtCompound nbt;
                try {
                    nbt = readChunkNbt(world, pos);
                } catch (IOException ignored) {
                    // A missing/corrupt chunk is represented by generated=false.
                    chunks[z * 16 + x] = SummaryCodec.Chunk.empty();
                    continue;
                }
                chunks[z * 16 + x] = nbt == null ? SummaryCodec.Chunk.empty() : summarizer.summarize(nbt);
            }
        }
        final SummaryCodec.Region region = new SummaryCodec.Region(regionX, regionZ, 0L, chunks);
        try {
            disk.save(dimension, region);
        } catch (IOException ignored) {
            // Memory results are still valid if the optional cache cannot be written.
        }
        return region;
    }

    private static NbtCompound readChunkNbt(final ServerWorld world, final ChunkPos pos) throws IOException {
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
}
