package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.WorldSavePath;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/** Tracks loaded chunks, refreshes requested summaries, and persists final unload snapshots. */
final class LiveChunkSummaryTracker {
    private static final int MAX_REGION_FLUSHES_PER_TICK = 8;
    private static final int MAX_LIVE_SUMMARIES_PER_TICK = 2;
    private static final long LIVE_DEMAND_TTL_NANOS = 2_000_000_000L;

    private final ServerConfig config;
    private final ChunkSummarizer summarizer;
    private final LiveChunkSummaryCache summaries = new LiveChunkSummaryCache();
    private final Map<LoadedKey, WorldChunk> loadedChunks = new HashMap<>();
    private final ArrayDeque<LoadedKey> refreshQueue = new ArrayDeque<>();
    private final Set<LoadedKey> queuedForRefresh = new HashSet<>();
    private final Set<LiveKey> activeChunks = new HashSet<>();
    private final Map<PendingRegionKey, Map<Integer, PendingChunk>> pendingRegions = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<LiveDemand> incomingDemands = new ConcurrentLinkedQueue<>();
    private final List<LiveDemand> activeDemands = new ArrayList<>();
    private final Map<ServerWorld, Integer> dimensionIndices = new HashMap<>();

    private record LoadedKey(ServerWorld world, long chunkPos) {
    }

    private record LiveKey(String dimension, int chunkX, int chunkZ) {
    }

    private record PendingRegionKey(String dimension, int regionX, int regionZ) {
    }

    private record PendingChunk(
        int chunkX,
        int chunkZ,
        SummaryCodec.Chunk summary,
        long observedMcaMtimeMs,
        boolean awaitMcaWrite
    ) {
    }

    private record LiveDemand(
        int dimensionIndex,
        int minChunkX,
        int minChunkZ,
        int maxChunkX,
        int maxChunkZ,
        long expiresAtNanos
    ) {
        boolean contains(final int index, final int chunkX, final int chunkZ, final long nowNanos) {
            return dimensionIndex == index && nowNanos < expiresAtNanos
                && chunkX >= minChunkX && chunkX <= maxChunkX
                && chunkZ >= minChunkZ && chunkZ <= maxChunkZ;
        }
    }

    LiveChunkSummaryTracker(final ServerConfig config, final ChunkSummarizer summarizer) {
        this.config = config;
        this.summarizer = summarizer;
    }

    void onChunkLoad(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final int chunkX = chunkX(pos);
        final int chunkZ = chunkZ(pos);
        final LoadedKey loaded = new LoadedKey(world, chunkLong(pos));
        loadedChunks.put(loaded, chunk);
        if (queuedForRefresh.add(loaded)) {
            refreshQueue.addLast(loaded);
        }
        dimensionIndices.put(world, worldIndex(world.getServer(), world));
        activeChunks.add(new LiveKey(dimension(world), chunkX, chunkZ));
    }

    void onChunkUnload(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final int chunkX = chunkX(pos);
        final int chunkZ = chunkZ(pos);
        capture(world, chunk);
        loadedChunks.remove(new LoadedKey(world, chunkLong(pos)));
        final String dimension = dimension(world);
        activeChunks.remove(new LiveKey(dimension, chunkX, chunkZ));
        final SummaryCodec.Chunk summary = summaries.get(dimension, chunkX, chunkZ);
        if (summary != null) {
            queuePersistence(world, dimension, chunkX, chunkZ, summary, chunk.needsSaving());
        }
    }

    void nominate(final MapViewReqC2S request, final long nowNanos) {
        final long chunksPerTile = 16L << request.lod();
        final long expiresAt = nowNanos + LIVE_DEMAND_TTL_NANOS;
        for (final MapViewReqC2S.TileReq tile : request.tiles()) {
            final long minX = (long) tile.tileX() * chunksPerTile;
            final long minZ = (long) tile.tileZ() * chunksPerTile;
            final long maxX = minX + chunksPerTile - 1L;
            final long maxZ = minZ + chunksPerTile - 1L;
            if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
                || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
                continue;
            }
            incomingDemands.add(new LiveDemand(
                request.dimIndex(), (int) minX, (int) minZ, (int) maxX, (int) maxZ, expiresAt
            ));
        }
    }

    void tick(final MinecraftServer server, final SummaryDiskCache disk) {
        refreshLoadedChunks(System.nanoTime());
        flushPendingRegions(server, disk, MAX_REGION_FLUSHES_PER_TICK, false);
    }

    SummaryCodec.Chunk get(final String dimension, final int chunkX, final int chunkZ) {
        return summaries.get(dimension, chunkX, chunkZ);
    }

    SummaryCodec.Region overlay(final String dimension, final SummaryCodec.Region region) {
        return summaries.overlay(dimension, region);
    }

    void prepareStop() {
        final List<Map.Entry<LoadedKey, WorldChunk>> remaining = new ArrayList<>(loadedChunks.entrySet());
        for (final Map.Entry<LoadedKey, WorldChunk> entry : remaining) {
            onChunkUnload(entry.getKey().world(), entry.getValue());
        }
    }

    void close(final MinecraftServer server, final SummaryDiskCache disk) {
        flushPendingRegions(server, disk, Integer.MAX_VALUE, true);
        loadedChunks.clear();
        refreshQueue.clear();
        queuedForRefresh.clear();
        activeChunks.clear();
        pendingRegions.clear();
        incomingDemands.clear();
        activeDemands.clear();
        dimensionIndices.clear();
        summaries.clear();
    }

    private void refreshLoadedChunks(final long nowNanos) {
        LiveDemand incoming;
        while ((incoming = incomingDemands.poll()) != null) {
            activeDemands.add(incoming);
        }
        activeDemands.removeIf(demand -> nowNanos >= demand.expiresAtNanos());
        if (activeDemands.isEmpty()) {
            return;
        }
        final int available = refreshQueue.size();
        final int configuredPerTick = Math.max(1, (config.maxChunkSummariesPerSecond + 19) / 20);
        final int budget = Math.min(MAX_LIVE_SUMMARIES_PER_TICK, configuredPerTick);
        int captured = 0;
        for (int inspected = 0; inspected < available && captured < budget; inspected++) {
            final LoadedKey key = refreshQueue.removeFirst();
            final WorldChunk chunk = loadedChunks.get(key);
            if (chunk == null) {
                queuedForRefresh.remove(key);
                continue;
            }
            refreshQueue.addLast(key);
            final Integer dimensionIndex = dimensionIndices.get(key.world());
            if (dimensionIndex == null) {
                continue;
            }
            final ChunkPos pos = chunk.getPos();
            final int chunkX = chunkX(pos);
            final int chunkZ = chunkZ(pos);
            if (!isDemanded(dimensionIndex, chunkX, chunkZ, nowNanos)) {
                continue;
            }
            final String dimension = dimension(key.world());
            if (summaries.get(dimension, chunkX, chunkZ) == null || chunk.needsSaving()) {
                capture(key.world(), chunk);
                captured++;
            }
        }
    }

    private boolean isDemanded(
        final int dimensionIndex,
        final int chunkX,
        final int chunkZ,
        final long nowNanos
    ) {
        for (final LiveDemand demand : activeDemands) {
            if (demand.contains(dimensionIndex, chunkX, chunkZ, nowNanos)) {
                return true;
            }
        }
        return false;
    }

    private void capture(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final int chunkX = chunkX(pos);
        final int chunkZ = chunkZ(pos);
        final String dimension = dimension(world);
        try {
            summaries.put(
                dimension,
                chunkX,
                chunkZ,
                summarizer.summarize(new WorldChunkColumnSource(world, chunk, world.getTime()))
            );
        } catch (final RuntimeException e) {
            ConfluxMapMod.LOGGER.warn(
                "companion: failed to summarize live chunk {},{} in {} ({})",
                chunkX, chunkZ, dimension, e.getMessage()
            );
        }
    }

    private void queuePersistence(
        final ServerWorld world,
        final String dimension,
        final int chunkX,
        final int chunkZ,
        final SummaryCodec.Chunk summary,
        final boolean awaitMcaWrite
    ) {
        final int regionX = Math.floorDiv(chunkX, 16);
        final int regionZ = Math.floorDiv(chunkZ, 16);
        final int localX = Math.floorMod(chunkX, 16);
        final int localZ = Math.floorMod(chunkZ, 16);
        final long observedMtime = RegionStoragePaths.mcaMtimeMs(
            world.getServer().getSavePath(WorldSavePath.ROOT), dimension, regionX, regionZ
        );
        pendingRegions.computeIfAbsent(
            new PendingRegionKey(dimension, regionX, regionZ),
            ignored -> new LinkedHashMap<>()
        ).put(
            localZ * 16 + localX,
            new PendingChunk(chunkX, chunkZ, summary, observedMtime, awaitMcaWrite)
        );
    }

    private void flushPendingRegions(
        final MinecraftServer server,
        final SummaryDiskCache disk,
        final int maxRegions,
        final boolean force
    ) {
        if (pendingRegions.isEmpty() || maxRegions <= 0) {
            return;
        }
        final Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        int flushed = 0;
        final Iterator<Map.Entry<PendingRegionKey, Map<Integer, PendingChunk>>> iterator =
            pendingRegions.entrySet().iterator();
        while (iterator.hasNext() && flushed < maxRegions) {
            final Map.Entry<PendingRegionKey, Map<Integer, PendingChunk>> entry = iterator.next();
            final PendingRegionKey key = entry.getKey();
            final long mtime = RegionStoragePaths.mcaMtimeMs(
                worldRoot, key.dimension(), key.regionX(), key.regionZ()
            );
            if (mtime <= 0L || (!force && awaitingMcaWrite(entry.getValue().values(), mtime))) {
                continue;
            }
            final Map<Integer, SummaryCodec.Chunk> updates = new HashMap<>();
            for (final Map.Entry<Integer, PendingChunk> pending : entry.getValue().entrySet()) {
                updates.put(pending.getKey(), pending.getValue().summary());
            }
            try {
                disk.saveLiveChunks(key.dimension(), key.regionX(), key.regionZ(), mtime, updates);
            } catch (final IOException e) {
                ConfluxMapMod.LOGGER.warn(
                    "companion: failed to persist live summaries for region {},{} in {} ({})",
                    key.regionX(), key.regionZ(), key.dimension(), e.getMessage()
                );
                continue;
            }
            for (final PendingChunk pending : entry.getValue().values()) {
                if (!activeChunks.contains(new LiveKey(key.dimension(), pending.chunkX(), pending.chunkZ()))) {
                    summaries.remove(key.dimension(), pending.chunkX(), pending.chunkZ());
                }
            }
            iterator.remove();
            flushed++;
        }
    }

    private static boolean awaitingMcaWrite(
        final Iterable<PendingChunk> chunks,
        final long currentMtime
    ) {
        for (final PendingChunk chunk : chunks) {
            if (chunk.awaitMcaWrite() && chunk.observedMcaMtimeMs() == currentMtime) {
                return true;
            }
        }
        return false;
    }

    private static int worldIndex(final MinecraftServer server, final ServerWorld target) {
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (world == target) {
                return index;
            }
            index++;
        }
        return -1;
    }

    private static String dimension(final ServerWorld world) {
        return world.getRegistryKey().getValue().toString();
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

    private static long chunkLong(final ChunkPos pos) {
        //#if MC>=260100
        //$$ return pos.pack();
        //#else
        return pos.toLong();
        //#endif
    }
}
