package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.net.MapViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapRegionViewReqC2S;
import cn.net.rms.confluxmap.core.net.MapRegionSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.MapSyncSubscribeC2S;
import cn.net.rms.confluxmap.core.net.SummaryCodec;
import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
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
import java.util.UUID;
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
    private static final int MAX_LIVE_INSPECTIONS_PER_TICK = 128;
    private static final long LIVE_DEMAND_TTL_NANOS = 2_000_000_000L;

    private final ServerConfig config;
    private final ChunkSummarizer summarizer;
    private final RegionChangeListener regionChanges;
    private final LiveChunkSummaryCache summaries = new LiveChunkSummaryCache();
    private final Map<LoadedKey, WorldChunk> loadedChunks = new HashMap<>();
    private final ArrayDeque<LoadedKey> refreshQueue = new ArrayDeque<>();
    private final Set<LoadedKey> queuedForRefresh = new HashSet<>();
    private final ArrayDeque<LoadedKey> dirtyQueue = new ArrayDeque<>();
    private final Set<LoadedKey> queuedDirty = new HashSet<>();
    private final Set<LiveKey> activeChunks = new HashSet<>();
    private final Map<PendingRegionKey, Map<Integer, PendingChunk>> pendingRegions = new LinkedHashMap<>();
    private final ConcurrentLinkedQueue<LiveDemand> incomingDemands = new ConcurrentLinkedQueue<>();
    private final List<LiveDemand> activeDemands = new ArrayList<>();
    private final Map<UUID, LiveDemand> watchedDemands = new HashMap<>();
    private final Map<ServerWorld, Integer> dimensionIndices = new HashMap<>();
    private volatile boolean acceptsDirtySignals;

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

    @FunctionalInterface
    interface RegionChangeListener {
        void onChanged(String dimension, int regionX, int regionZ);
    }

    LiveChunkSummaryTracker(
        final ServerConfig config,
        final ChunkSummarizer summarizer,
        final RegionChangeListener regionChanges
    ) {
        this.config = config;
        this.summarizer = summarizer;
        this.regionChanges = regionChanges;
    }

    void onChunkLoad(final ServerWorld world, final WorldChunk chunk) {
        final LoadedKey loaded = trackLoaded(world, chunk);
        if (queuedForRefresh.add(loaded)) {
            refreshQueue.addLast(loaded);
        }
    }

    void onChunkDirty(final ServerWorld world, final WorldChunk chunk) {
        if (!acceptsDirtySignals) {
            return;
        }
        final LoadedKey loaded = trackLoaded(world, chunk);
        if (queuedDirty.add(loaded)) {
            dirtyQueue.addFirst(loaded);
        }
    }

    void onChunkUnload(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final int chunkX = chunkX(pos);
        final int chunkZ = chunkZ(pos);
        capture(world, chunk);
        final LoadedKey loaded = new LoadedKey(world, chunkLong(pos));
        loadedChunks.remove(loaded);
        queuedDirty.remove(loaded);
        final String dimension = dimension(world);
        activeChunks.remove(new LiveKey(dimension, chunkX, chunkZ));
        final SummaryCodec.Chunk summary = summaries.get(dimension, chunkX, chunkZ);
        if (summary != null) {
            queuePersistence(world, dimension, chunkX, chunkZ, summary, chunk.needsSaving());
        }
    }

    void nominate(final MapViewReqC2S request, final long nowNanos) {
        acceptsDirtySignals = true;
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

    void nominate(final MapRegionViewReqC2S request, final long nowNanos) {
        acceptsDirtySignals = true;
        final long expiresAt = nowNanos + LIVE_DEMAND_TTL_NANOS;
        for (final MapRegionViewReqC2S.RegionReq region : request.regions()) {
            final cn.net.rms.confluxmap.core.util.ChunkRegionSlice slice = region.slice();
            incomingDemands.add(new LiveDemand(
                request.dimIndex(),
                slice.minChunkX(), slice.minChunkZ(),
                slice.minChunkX() + slice.width() - 1,
                slice.minChunkZ() + slice.height() - 1,
                expiresAt
            ));
        }
    }

    boolean watch(final UUID player, final MapSyncSubscribeC2S request) {
        if (!request.active()) {
            watchedDemands.remove(player);
            return true;
        }
        acceptsDirtySignals = true;
        final long chunksPerTile = 16L << request.lod();
        final long minX = (long) request.minTileX() * chunksPerTile;
        final long minZ = (long) request.minTileZ() * chunksPerTile;
        final long maxX = ((long) request.maxTileX() + 1L) * chunksPerTile - 1L;
        final long maxZ = ((long) request.maxTileZ() + 1L) * chunksPerTile - 1L;
        if (minX < Integer.MIN_VALUE || maxX > Integer.MAX_VALUE
            || minZ < Integer.MIN_VALUE || maxZ > Integer.MAX_VALUE) {
            return false;
        }
        watchedDemands.put(player, new LiveDemand(
            request.dimIndex(), (int) minX, (int) minZ, (int) maxX, (int) maxZ, Long.MAX_VALUE
        ));
        return true;
    }

    boolean watch(final UUID player, final MapRegionSyncSubscribeC2S request) {
        if (!request.active()) {
            watchedDemands.remove(player);
            return true;
        }
        acceptsDirtySignals = true;
        watchedDemands.put(player, new LiveDemand(
            request.dimIndex(),
            request.minChunkX(), request.minChunkZ(),
            request.maxChunkX(), request.maxChunkZ(),
            Long.MAX_VALUE
        ));
        return true;
    }

    void unwatch(final UUID player) {
        watchedDemands.remove(player);
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

    SummaryCodec.SampledRegion overlay(
        final String dimension, final SummaryCodec.SampledRegion region
    ) {
        return summaries.overlay(dimension, region);
    }

    SummaryCodec.SampledRegion overlay(
        final String dimension,
        final SummaryCodec.SampledRegion region,
        final ChunkRegionSlice slice
    ) {
        return summaries.overlay(dimension, region, slice);
    }

    long regionEpoch(final String dimension, final int regionX, final int regionZ) {
        return summaries.regionEpoch(dimension, regionX, regionZ);
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
        dirtyQueue.clear();
        queuedDirty.clear();
        activeChunks.clear();
        pendingRegions.clear();
        incomingDemands.clear();
        activeDemands.clear();
        watchedDemands.clear();
        dimensionIndices.clear();
        acceptsDirtySignals = false;
        summaries.clear();
    }

    private void refreshLoadedChunks(final long nowNanos) {
        LiveDemand incoming;
        while ((incoming = incomingDemands.poll()) != null) {
            activeDemands.add(incoming);
        }
        activeDemands.removeIf(demand -> nowNanos >= demand.expiresAtNanos());
        if (activeDemands.isEmpty() && watchedDemands.isEmpty()) {
            acceptsDirtySignals = false;
            return;
        }
        final int available = refreshQueue.size();
        final int configuredPerTick = Math.max(1, (config.maxChunkSummariesPerSecond + 19) / 20);
        final int budget = Math.min(MAX_LIVE_SUMMARIES_PER_TICK, configuredPerTick);
        int captured = refreshDirtyChunks(nowNanos, budget);
        final int inspectionBudget = Math.min(available, MAX_LIVE_INSPECTIONS_PER_TICK);
        for (int inspected = 0; inspected < inspectionBudget && captured < budget; inspected++) {
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
            if (summaries.get(dimension, chunkX, chunkZ) == null) {
                capture(key.world(), chunk);
                captured++;
            }
        }
    }

    private int refreshDirtyChunks(final long nowNanos, final int budget) {
        final int available = dirtyQueue.size();
        final int inspectionBudget = Math.min(available, MAX_LIVE_INSPECTIONS_PER_TICK);
        int captured = 0;
        for (int inspected = 0; inspected < inspectionBudget && captured < budget; inspected++) {
            final LoadedKey key = dirtyQueue.removeFirst();
            final WorldChunk chunk = loadedChunks.get(key);
            if (chunk == null || !queuedDirty.contains(key)) {
                queuedDirty.remove(key);
                continue;
            }
            final Integer dimensionIndex = dimensionIndices.get(key.world());
            if (dimensionIndex == null) {
                dirtyQueue.addLast(key);
                continue;
            }
            final ChunkPos pos = chunk.getPos();
            if (!isDemanded(dimensionIndex, chunkX(pos), chunkZ(pos), nowNanos)) {
                dirtyQueue.addLast(key);
                continue;
            }
            queuedDirty.remove(key);
            capture(key.world(), chunk);
            captured++;
        }
        return captured;
    }

    private LoadedKey trackLoaded(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final int chunkX = chunkX(pos);
        final int chunkZ = chunkZ(pos);
        final LoadedKey loaded = new LoadedKey(world, chunkLong(pos));
        loadedChunks.put(loaded, chunk);
        dimensionIndices.put(world, worldIndex(world.getServer(), world));
        activeChunks.add(new LiveKey(dimension(world), chunkX, chunkZ));
        return loaded;
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
        for (final LiveDemand demand : watchedDemands.values()) {
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
            final boolean changed = summaries.put(
                dimension,
                chunkX,
                chunkZ,
                summarizer.summarize(new WorldChunkColumnSource(world, chunk, world.getTime()))
            );
            if (changed) {
                regionChanges.onChanged(dimension, Math.floorDiv(chunkX, 16), Math.floorDiv(chunkZ, 16));
            }
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
