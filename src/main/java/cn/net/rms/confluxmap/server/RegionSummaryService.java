package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ErrorS2C;
import cn.net.rms.confluxmap.core.net.MapPatchS2C;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.Message;
import cn.net.rms.confluxmap.core.net.Proto;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.WorldSavePath;

/** Serves summary-backed corrections without asking the world chunk manager to generate chunks. */
public final class RegionSummaryService {
    private final ServerConfig config;
    private final ChunkSummarizer summarizer = new ChunkSummarizer();
    private final PatchBuilder patchBuilder = new PatchBuilder();
    private final Map<UUID, PlayerBudget> budgets = new ConcurrentHashMap<>();

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
        final PlayerBudget budget = budgets.computeIfAbsent(player.getUuid(), ignored -> new PlayerBudget(
            config.maxBytesPerSecondPerPlayer, config.maxPendingTilesPerPlayer, config.minRequestIntervalMs
        ));
        if (request.lod() > config.maxPatchLod || request.tiles().size() > config.maxTilesPerRequest
            || request.dimIndex() < 0 || !budget.beginRequest(now)) {
            sender.accept(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction request is rate limited"));
            return;
        }
        try {
            final ServerWorld world = worldAt(server, request.dimIndex());
            if (world == null || !config.shareCorrections) {
                for (final MapViewReqC2S.TileReq tile : request.tiles()) {
                    sender.accept(new MapPatchS2C(request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
                        Proto.PATCH_MODE_UNAVAILABLE, 0L, new byte[Proto.PATCH_PRESENCE_BYTES], new byte[0]));
                }
                return;
            }
            for (final MapViewReqC2S.TileReq tile : request.tiles()) {
                final SummaryDiskCache disk = new SummaryDiskCache(server.getSavePath(WorldSavePath.ROOT));
                final SummaryCodec.Region summary = readTile(world, tile.tileX(), tile.tileZ(), disk);
                final PatchBuilder.Result result = buildPatch(world, summary, tile, request.lod());
                final MapPatchS2C patch = new MapPatchS2C(request.reqId(), request.dimIndex(), request.lod(), tile.tileX(), tile.tileZ(),
                    result.mode(), result.revision(), result.presence(), result.body());
                final byte[] estimated = cn.net.rms.confluxmap.core.net.MsgCodec.encode(patch);
                if (!budget.allowBytes(estimated.length, System.nanoTime())) {
                    sender.accept(new ErrorS2C(ErrorS2C.ERR_RATE_LIMITED, "map correction bandwidth budget exhausted"));
                    break;
                }
                sender.accept(patch);
            }
        } catch (final Exception e) {
            sender.accept(new ErrorS2C(ErrorS2C.ERR_INTERNAL, "map correction summary failed"));
        } finally {
            budget.finishRequest();
        }
    }

    public void remove(final UUID player) {
        budgets.remove(player);
    }

    private PatchBuilder.Result buildPatch(
        final ServerWorld world, final SummaryCodec.Region summary, final MapViewReqC2S.TileReq tile, final int lod
    ) {
        if (NativeLib.available()) {
            final int nativeDim = PredictionDimensions.isEnd(
                cn.net.rms.confluxmap.core.model.DimensionId.of(
                    world.getRegistryKey().getValue().getNamespace(), world.getRegistryKey().getValue().getPath()
                )
            ) ? 1 : 0;
            final java.util.OptionalInt version = McVersions.toCubiomes("1.17.1");
            if (version.isPresent()) {
                final PatchBuilder.Result residual = patchBuilder.buildFromSampler(
                    summary, lod, tile.tileX(), tile.tileZ(), tile.sinceRevision(),
                    new NativeBaselineSampler(version.getAsInt(), world.getSeed(), nativeDim), nativeDim == 1,
                    world.getSeed(), false
                );
                if (residual.mode() != Proto.PATCH_MODE_UNAVAILABLE) {
                    return residual;
                }
            }
        }
        return patchBuilder.buildAbsolute(summary, lod, tile.sinceRevision());
    }

    private SummaryCodec.Region readTile(
        final ServerWorld world, final int tileX, final int tileZ, final SummaryDiskCache disk
    ) {
        final String dimension = world.getRegistryKey().getValue().toString();
        final SummaryCodec.Region cached = disk.load(dimension, tileX, tileZ);
        // A zero source mtime is the conservative marker used by the live reader: without a
        // reliable region-file timestamp, never serve an older summary over a newly written chunk.
        if (cached != null && cached.sourceMcaMtimeMs() > 0L) {
            return cached;
        }
        final SummaryCodec.Chunk[] chunks = new SummaryCodec.Chunk[SummaryCodec.CHUNKS];
        for (int z = 0; z < 16; z++) {
            for (int x = 0; x < 16; x++) {
                final ChunkPos pos = new ChunkPos(tileX * 16 + x, tileZ * 16 + z);
                NbtCompound nbt = null;
                try {
                    nbt = world.getChunkManager().threadedAnvilChunkStorage.getNbt(pos);
                } catch (IOException ignored) {
                    // A missing/corrupt chunk is represented by generated=false.
                }
                chunks[z * 16 + x] = nbt == null ? SummaryCodec.Chunk.empty() : summarizer.summarize(nbt);
            }
        }
        final SummaryCodec.Region region = new SummaryCodec.Region(tileX, tileZ, 0L, chunks);
        try {
            disk.save(dimension, region);
        } catch (IOException ignored) {
            // Memory results are still valid if the optional cache cannot be written.
        }
        return region;
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
