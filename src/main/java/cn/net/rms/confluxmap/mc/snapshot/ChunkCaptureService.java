package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.cache.RegionCacheService;
import cn.net.rms.confluxmap.core.cache.RegionDiskCache;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.CaptureRefreshSweep;
import cn.net.rms.confluxmap.core.task.CaptureTickBudget;
import cn.net.rms.confluxmap.core.task.DirtyChunkSet;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import cn.net.rms.confluxmap.terrain.protocol.EncodedChunk;
import cn.net.rms.confluxmap.terrain.protocol.TerrainResult;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/**
 * Drives the capture pipeline: packet hooks mark chunks dirty (via
 * {@link ChunkCaptureHandler}); each tick, {@link LayerSelector} decides the
 * one active layer (per cave-nether-layers.md §1), and a bounded number of the
 * nearest dirty chunks is captured into that layer. Surface layers retain the
 * Minecraft-backed snapshot factory. Cave and Nether floor layers only copy the
 * chunks' compressed block-state containers on the main thread; a child JVM owns
 * the vertical scan and latest-pivot cancellation, then the main thread resolves
 * the selected positions' models, mod tints and light before storing them. The
 * background server send-distance queue remains best effort. A layer change still
 * reseeds the whole server send-distance square, the same way a session change does.
 */
public final class ChunkCaptureService {
    private static final int LOG_INTERVAL_TICKS = 100;
    private static final long FLOOR_FINISH_BUDGET_NANOS = 2_000_000L;

    private final MinecraftClient client;
    private final GameBridge gameBridge;
    private final ConfluxConfig config;
    private final MapWorldService worlds;
    private final MapExecutors executors;
    private final TileService tiles;
    private final PredictionTileService predictionTiles;
    private final IntSupplier serverViewDistance;
    private final RegionCacheService regionCache;
    private final LayerSelector layerSelector;
    private final McChunkSnapshotFactory factory;
    private final McTerrainChunkEncoder terrainEncoder;
    private final McTerrainWorker terrainWorker;
    private final DirtyChunkSet dirtyChunks = new DirtyChunkSet();
    private final CaptureRefreshSweep visibleRefresh = new CaptureRefreshSweep();
    private final AtomicLong storedSnapshots = new AtomicLong();
    private long lastLoggedSnapshots = -1;
    private int tickCounter;
    private LayerSelector.Decision lastDecision;
    private ChunkViewport localCaptureViewport;
    private volatile ChunkViewport minimapViewport;
    private MapLayer workerLayer;

    public ChunkCaptureService(
        final MinecraftClient client,
        final GameBridge gameBridge,
        final ConfluxConfig config,
        final MapWorldService worlds,
        final MapExecutors executors,
        final TileService tiles,
        final PredictionTileService predictionTiles,
        final IntSupplier serverViewDistance,
        final RegionCacheService regionCache,
        final SpriteColorSampler sampler,
        final BiomeTintResolver tintResolver,
        final LayerSelector layerSelector
    ) {
        this.client = client;
        this.gameBridge = gameBridge;
        this.config = config;
        this.worlds = worlds;
        this.executors = executors;
        this.tiles = tiles;
        this.predictionTiles = predictionTiles;
        this.serverViewDistance = serverViewDistance;
        this.regionCache = regionCache;
        this.layerSelector = layerSelector;
        this.factory = new McChunkSnapshotFactory(client, sampler, tintResolver);
        this.terrainEncoder = new McTerrainChunkEncoder(client);
        this.terrainWorker = new McTerrainWorker(new McTerrainMaterialResolver(client));
    }

    public void register() {
        ChunkCaptureHandler.bind(this);
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    /**
     * Main thread, from the session tracker. The initial spawn-area chunk batch
     * arrives before the first session tick, so marks made during the loading
     * phase reference a world this session never saw. Instead of trusting them,
     * reseed the whole server send-distance square around the active viewpoint: chunks that are
     * not actually loaded are skipped by the snapshot factory at drain time.
     */
    public void onSessionChanged(final SessionGuard.Session session) {
        dirtyChunks.clear();
        visibleRefresh.reset();
        minimapViewport = null;
        layerSelector.onSessionChanged(session);
        lastDecision = null;
        localCaptureViewport = null;
        workerLayer = null;
        terrainWorker.reset(session.active() ? session.token() : 0L, 0);
        if (!session.active()) {
            return;
        }
        final PlayerView viewpoint = gameBridge.viewpoint().orElse(null);
        if (viewpoint == null) {
            return;
        }
        reseedViewport(viewpoint.blockX() >> 4, viewpoint.blockZ() >> 4);
    }

    /** Main thread, from packet mixins. */
    public void markDirty(final int chunkX, final int chunkZ) {
        markCaptureDirty(chunkX, chunkZ);
        terrainWorker.invalidate(chunkX, chunkZ);
    }

    private void markCaptureDirty(final int chunkX, final int chunkZ) {
        dirtyChunks.mark(chunkX, chunkZ);
        visibleRefresh.markDirty(chunkX, chunkZ);
    }

    /** Main thread, after a server block update has been applied to the client chunk. */
    public void markBlockDirty(
        final int blockX, final int y, final int blockZ, final int stateId
    ) {
        markCaptureDirty(blockX >> 4, blockZ >> 4);
        final ClientWorld world = client.world;
        if (world != null) {
            terrainWorker.submitDelta(world.getTime(), blockX, y, blockZ, stateId);
        }
    }

    /**
     * Main thread, from the chunk-load mixin: marks the arriving chunk and re-marks the
     * neighbours it just invalidated.
     *
     * <p>Tints are baked into the snapshot through {@link cn.net.rms.confluxmap.mc.color.BiomeTintResolver},
     * which goes through the game's own biome blend - that averages the biome color over a
     * {@code biomeBlendRadius} square that reaches into the adjacent chunks, and every position
     * inside a chunk the client has not received yet answers {@code ClientWorld}'s plains
     * fallback biome. A neighbour snapshotted before this chunk arrived therefore baked
     * plains-tinted water/grass into its border strip, which reads as a chunk grid wherever the
     * real biome's tint is far from plains - warm ocean water being the most visible case.
     * Vanilla discards the same stale colors by clearing the 3x3 {@code BiomeColorCache} square
     * in {@code ClientWorld.resetChunkColor}; a snapshot has the color baked in, so the only
     * way to drop it is to take the snapshot again.
     */
    public void markChunkLoaded(final int chunkX, final int chunkZ) {
        // Biome identity has its own two-block Voronoi footprint even when tint blending is
        // disabled. Revisit loaded neighbours so their temporarily clamped borders recover the
        // exact biome boundary once this chunk arrives.
        terrainWorker.invalidate(chunkX, chunkZ);
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if ((dx != 0 || dz != 0) && isChunkLoaded(chunkX + dx, chunkZ + dz)) {
                    terrainWorker.invalidate(chunkX + dx, chunkZ + dz);
                }
            }
        }
        dirtyChunks.markWithLoadedNeighbors(chunkX, chunkZ, this::isChunkLoaded);
        visibleRefresh.markWithLoadedNeighbors(chunkX, chunkZ, this::isChunkLoaded);
    }

    /** Render thread publishes the bounding chunk rectangle currently visible on the minimap. */
    public void setMinimapViewport(final ChunkViewport viewport) {
        minimapViewport = viewport;
    }

    /**
     * Whether a dirty chunk is worth sampling this tick. A chunk is only fully sampleable once
     * its 3x3 neighbourhood has arrived - the biome blend and the surrounding terrain reach
     * across the border - so an incomplete neighbourhood is held back rather than baked. The
     * server send-distance viewport distinguishes expected streaming neighbours from the outer
     * ring. Expected neighbours wait without falling back to a throwaway snapshot. The outer
     * ring is ready immediately and {@link ChunkTintSampler}'s clamped window keeps its border
     * tint honest without requiring a second capture.
     */
    private DirtyChunkSet.Readiness captureReadiness(final int chunkX, final int chunkZ) {
        return captureReadiness(
            chunkX,
            chunkZ,
            localCaptureViewport,
            this::isChunkLoaded,
            needsTintNeighbors(MinecraftAccess.biomeBlendRadius(client))
        );
    }

    static DirtyChunkSet.Readiness captureReadiness(
        final int chunkX,
        final int chunkZ,
        final ChunkViewport expectedViewport,
        final DirtyChunkSet.ChunkPredicate loaded,
        final boolean needsTintNeighbors
    ) {
        if (!loaded.test(chunkX, chunkZ)) {
            return DirtyChunkSet.Readiness.MISSING;
        }
        if (!needsTintNeighbors) {
            return DirtyChunkSet.Readiness.READY;
        }
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                final int neighborX = chunkX + dx;
                final int neighborZ = chunkZ + dz;
                if (!loaded.test(neighborX, neighborZ)
                    && (expectedViewport == null
                    || expectedViewport.contains(neighborX, neighborZ))) {
                    return expectedViewport == null
                        ? DirtyChunkSet.Readiness.WAITING
                        : DirtyChunkSet.Readiness.AWAITING_NEIGHBORS;
                }
            }
        }
        return DirtyChunkSet.Readiness.READY;
    }

    static boolean needsTintNeighbors(final int biomeBlendRadius) {
        return biomeBlendRadius > 0;
    }

    private boolean isChunkLoaded(final int chunkX, final int chunkZ) {
        final ClientWorld world = client.world;
        return world != null && ChunkTintSampler.loaded(world, chunkX, chunkZ);
    }

    public long storedSnapshotCount() {
        return storedSnapshots.get();
    }

    public int pendingDirtyChunks() {
        return dirtyChunks.size();
    }

    /**
     * Main-thread, read-only terrain probe used only while the normal map session is suspended.
     * Captured snapshots are never queued or stored, so an ambiguous upstream cannot contaminate
     * any candidate profile before recognition succeeds.
     */
    public List<ChunkSnapshot> probeNearest(final MapLayer layer, final int limit) {
        final ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || limit <= 0) {
            return List.of();
        }
        final int centerX = player.getBlockPos().getX() >> 4;
        final int centerZ = player.getBlockPos().getZ() >> 4;
        final int pivotY = layer.type() == MapLayer.Type.NETHER_CEILING ? client.world.getTopY() - 1 : 0;
        final int radiusLimit = captureViewDistance() + 1;
        final List<ChunkSnapshot> snapshots = new ArrayList<>(limit);
        for (int radius = 0; radius <= radiusLimit && snapshots.size() < limit; radius++) {
            for (int dz = -radius; dz <= radius && snapshots.size() < limit; dz++) {
                for (int dx = -radius; dx <= radius && snapshots.size() < limit; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) {
                        continue;
                    }
                    final ChunkSnapshot snapshot = factory.snapshot(
                        centerX + dx, centerZ + dz, layer, pivotY, 0L
                    );
                    if (snapshot != null) {
                        snapshots.add(snapshot);
                    }
                }
            }
        }
        return List.copyOf(snapshots);
    }

    /** Marks every chunk in the current server send-distance square dirty. */
    private void reseedViewport(final int centerChunkX, final int centerChunkZ) {
        final int radius = captureViewDistance() + 1;
        for (int dz = -radius; dz <= radius; dz++) {
            for (int dx = -radius; dx <= radius; dx++) {
                dirtyChunks.mark(centerChunkX + dx, centerChunkZ + dz);
            }
        }
    }

    private void tick() {
        final MapWorld world = worlds.current();
        final ClientPlayerEntity player = client.player;
        final PlayerView viewpoint = gameBridge.viewpoint().orElse(null);
        if (world == null || player == null || viewpoint == null) {
            return;
        }
        final long token = world.session().token();
        final int playerChunkX = player.getBlockPos().getX() >> 4;
        final int playerChunkZ = player.getBlockPos().getZ() >> 4;
        final int viewpointChunkX = viewpoint.blockX() >> 4;
        final int viewpointChunkZ = viewpoint.blockZ() >> 4;
        final ChunkViewport nextCaptureViewport = ChunkViewport.centered(
            playerChunkX, playerChunkZ, captureViewDistance()
        );
        if (!nextCaptureViewport.equals(localCaptureViewport)) {
            localCaptureViewport = nextCaptureViewport;
            tiles.setLocalAuthorityViewport(nextCaptureViewport);
            predictionTiles.refreshLiveCoverage();
        }

        final LayerSelector.Decision decision = layerSelector.tick();
        if (!decision.equals(lastDecision)) {
            // A real layer change needs background coverage for its new store. A player-relative
            // pivot change is handled by visibleRefresh; reseeding the entire server distance on
            // every two-block Y movement wastes thousands of off-screen snapshots.
            if (shouldReseedBackground(lastDecision, decision)) {
                reseedViewport(viewpointChunkX, viewpointChunkZ);
            }
            lastDecision = decision;
        }
        final MapLayer previousWorkerLayer = workerLayer;
        if (isProcessFloorLayer(decision.layer())) {
            workerLayer = decision.layer();
            terrainWorker.updatePivot(decision.pivotY());
        } else {
            workerLayer = null;
            if (previousWorkerLayer != null) {
                terrainWorker.pause();
            }
        }

        terrainWorker.resolveMaterialRequests();

        final int worldTopY = client.world.getTopY();
        final List<LayerSelector.Decision> backgroundPlan = capturePlan(decision, worldTopY);
        final int backgroundChunkBudget = Math.max(
            1, config.snapshotBudgetPerTick / backgroundPlan.size()
        );
        final ChunkViewport visible = visibleCaptureViewport(
            minimapViewport, localCaptureViewport
        );
        if (workerLayer != null) {
            terrainWorker.updateViewport(visible);
        }
        final CaptureTickBudget tickBudget = visible == null
            ? null
            : CaptureTickBudget.visible(config.snapshotBudgetPerTick, System::nanoTime);
        final CaptureRefreshSweep.Batch visibleBatch;
        if (visible == null) {
            visibleRefresh.reset();
            visibleBatch = null;
        } else {
            visibleRefresh.updateTarget(decision.layer(), decision.pivotY(), visible);
            visibleBatch = visibleRefresh.drainNearest(
                tickBudget.maximumCandidates(),
                viewpointChunkX,
                viewpointChunkZ,
                this::captureReadiness
            );
        }
        final List<CapturedSnapshot> captured = new ArrayList<>(Math.max(
            config.snapshotBudgetPerTick,
            visibleBatch == null ? 0 : visibleBatch.chunks().size()
        ));
        final int finishedResults = finishProcessResults(captured, token, tickBudget);
        int visibleAttempts = 0;
        if (visibleBatch != null) {
            final List<LayerSelector.Decision> visiblePlan = visibleCapturePlan(
                new LayerSelector.Decision(visibleBatch.layer(), visibleBatch.pivotY())
            );
            for (final long[] chunk : visibleBatch.chunks()) {
                if (!tickBudget.canCapture(visibleAttempts, finishedResults > 0)) {
                    visibleRefresh.markDirty((int) chunk[0], (int) chunk[1]);
                    continue;
                }
                visibleAttempts += visiblePlan.size();
                if (!captureChunk(captured, chunk, visiblePlan, token)) {
                    visibleRefresh.markDirty((int) chunk[0], (int) chunk[1]);
                } else if (!hasSecondaryLayer(visibleBatch.layer())) {
                    dirtyChunks.discard((int) chunk[0], (int) chunk[1]);
                }
            }
        }
        final boolean canCaptureBackground = tickBudget == null
            || tickBudget.canCapture(visibleAttempts, finishedResults > 0);
        final int remainingBackgroundChunks = !canCaptureBackground
            || visibleAttempts >= config.snapshotBudgetPerTick
            ? 0
            : Math.max(
                1,
                (config.snapshotBudgetPerTick - visibleAttempts) / backgroundPlan.size()
            );
        if (remainingBackgroundChunks > 0 && !visibleRefresh.hasPending()) {
            final List<long[]> background = dirtyChunks.drainNearest(
                Math.min(backgroundChunkBudget, remainingBackgroundChunks),
                viewpointChunkX,
                viewpointChunkZ,
                this::captureReadiness
            );
            for (final long[] chunk : background) {
                final List<LayerSelector.Decision> chunkPlan = backgroundCapturePlan(
                    decision,
                    worldTopY,
                    visible != null && visible.contains((int) chunk[0], (int) chunk[1])
                );
                if (!captureChunk(
                    captured,
                    chunk,
                    chunkPlan,
                    token
                )) {
                    dirtyChunks.mark((int) chunk[0], (int) chunk[1]);
                }
            }
        }
        if (!captured.isEmpty()) {
            executors.workers().execute(() -> storeSnapshots(captured));
        }
        final RegionDiskCache cache = regionCache.current();
        if (cache != null) {
            cache.tick(viewpointChunkX >> 4, viewpointChunkZ >> 4);
        }
        logPeriodically(world);
    }

    private boolean captureChunk(
        final List<CapturedSnapshot> captured,
        final long[] chunk,
        final List<LayerSelector.Decision> plan,
        final long token
    ) {
        int accepted = 0;
        EncodedChunk encoded = null;
        boolean primeTerrain = false;
        for (final LayerSelector.Decision decision : plan) {
            if (isProcessFloorLayer(decision.layer())) {
                if (terrainWorker.hasFreshChunk((int) chunk[0], (int) chunk[1])) {
                    accepted++;
                    continue;
                }
                if (encoded == null) {
                    encoded = terrainEncoder.capture(
                        (int) chunk[0], (int) chunk[1], token
                    );
                }
                if (encoded != null && terrainWorker.submit(encoded)) {
                    accepted++;
                }
                continue;
            }
            final ChunkSnapshot snapshot = factory.snapshot(
                (int) chunk[0], (int) chunk[1], decision.layer(), decision.pivotY(), token
            );
            if (snapshot != null) {
                captured.add(new CapturedSnapshot(snapshot, decision.layer()));
                accepted++;
                primeTerrain = true;
            }
        }
        if (primeTerrain
            && !terrainWorker.hasFreshChunk((int) chunk[0], (int) chunk[1])) {
            if (encoded == null) {
                encoded = terrainEncoder.capture((int) chunk[0], (int) chunk[1], token);
            }
            if (encoded != null) {
                terrainWorker.prime(encoded);
            }
        }
        return accepted == plan.size();
    }

    private int finishProcessResults(
        final List<CapturedSnapshot> captured,
        final long token,
        final CaptureTickBudget tickBudget
    ) {
        if (workerLayer == null) {
            return 0;
        }
        final long started = System.nanoTime();
        int attempts = 0;
        TerrainResult result;
        while ((tickBudget == null
            ? attempts == 0 || System.nanoTime() - started < FLOOR_FINISH_BUDGET_NANOS
            : tickBudget.canFinish(attempts))
            && (result = terrainWorker.pollResult()) != null) {
            attempts++;
            final ChunkSnapshot snapshot = factory.finishFloorSelection(
                result, workerLayer, token
            );
            if (snapshot == null) {
                markDirty(result.result().chunkX(), result.result().chunkZ());
            } else {
                captured.add(new CapturedSnapshot(snapshot, workerLayer));
            }
        }
        return attempts;
    }

    private static boolean isProcessFloorLayer(final MapLayer layer) {
        return layer.type() == MapLayer.Type.CAVE_AUTO
            || layer.type() == MapLayer.Type.CAVE_SLICE
            || layer.type() == MapLayer.Type.NETHER_CURRENT
            || layer.type() == MapLayer.Type.NETHER_SLICE;
    }

    private static boolean hasSecondaryLayer(final MapLayer layer) {
        return layer.type().isNetherFloor();
    }

    private int captureViewDistance() {
        return captureViewDistance(
            MinecraftAccess.viewDistance(client), serverViewDistance.getAsInt()
        );
    }

    static int captureViewDistance(
        final int clientViewDistance, final int advertisedServerViewDistance
    ) {
        return advertisedServerViewDistance >= 0
            ? advertisedServerViewDistance : clientViewDistance;
    }

    /**
     * Limits visible capture work to the server send-distance square plus its loaded guard ring.
     * At the largest minimap size/zoom the screen can cover thousands of chunks, while only this
     * intersection can exist in {@link ClientWorld}; queueing the rest makes every tick sort
     * coordinates that the snapshot factory can only reject.
     */
    static ChunkViewport visibleCaptureViewport(
        final ChunkViewport visible, final ChunkViewport localAuthority
    ) {
        if (visible == null || localAuthority == null) {
            return null;
        }
        final int minChunkX = Math.max(
            visible.minChunkX(), Math.subtractExact(localAuthority.minChunkX(), 1)
        );
        final int maxChunkX = Math.min(
            visible.maxChunkX(), Math.addExact(localAuthority.maxChunkX(), 1)
        );
        final int minChunkZ = Math.max(
            visible.minChunkZ(), Math.subtractExact(localAuthority.minChunkZ(), 1)
        );
        final int maxChunkZ = Math.min(
            visible.maxChunkZ(), Math.addExact(localAuthority.maxChunkZ(), 1)
        );
        return minChunkX > maxChunkX || minChunkZ > maxChunkZ
            ? null
            : new ChunkViewport(minChunkX, maxChunkX, minChunkZ, maxChunkZ);
    }

    /**
     * A below-roof Nether player still needs the persistent roof surface for the fullscreen map.
     * Divide the per-tick chunk batch by this plan's size so normal budgets remain bounded. A
     * configured budget of one deliberately captures one chunk in both layers, making progress on
     * the visible cave and the persistent roof instead of starving either one.
     */
    static List<LayerSelector.Decision> capturePlan(
        final LayerSelector.Decision selected,
        final int worldTopY
    ) {
        if (selected.layer().type().isNetherFloor()) {
            return List.of(
                selected,
                new LayerSelector.Decision(MapLayer.NETHER_CEILING, worldTopY - 1)
            );
        }
        return List.of(selected);
    }

    static boolean shouldReseedBackground(
        final LayerSelector.Decision previous,
        final LayerSelector.Decision current
    ) {
        return previous == null || !previous.layer().equals(current.layer());
    }

    static List<LayerSelector.Decision> visibleCapturePlan(
        final LayerSelector.Decision selected
    ) {
        return List.of(selected);
    }

    static List<LayerSelector.Decision> backgroundCapturePlan(
        final LayerSelector.Decision selected,
        final int worldTopY,
        final boolean visibleChunk
    ) {
        final List<LayerSelector.Decision> plan = capturePlan(selected, worldTopY);
        return visibleChunk && plan.size() > 1 ? plan.subList(1, plan.size()) : plan;
    }

    private void storeSnapshots(final List<CapturedSnapshot> captured) {
        final Map<MapLayer, List<TileService.RegionUnit>> stored = new LinkedHashMap<>();
        MapWorld world = null;
        for (final CapturedSnapshot capture : captured) {
            final ChunkSnapshot snapshot = capture.snapshot;
            if (world == null || world.session().token() != snapshot.sessionToken) {
                world = worlds.ifCurrent(snapshot.sessionToken);
            }
            if (world != null && world.put(capture.layer, snapshot, SampleSource.REAL_LIVE)) {
                storedSnapshots.incrementAndGet();
                stored.computeIfAbsent(capture.layer, ignored -> new ArrayList<>())
                    .add(new TileService.RegionUnit(snapshot.chunkX, snapshot.chunkZ));
            }
        }
        if (world == null) {
            return;
        }
        for (final Map.Entry<MapLayer, List<TileService.RegionUnit>> entry : stored.entrySet()) {
            final MapLayer layer = entry.getKey();
            tiles.markChunksStored(
                world.session().token(), world.session().dimension(), layer, entry.getValue()
            );
            if (layer.type().persistent() && regionCache != null) {
                final RegionDiskCache cache = regionCache.current();
                if (cache != null) {
                    final List<TileService.RegionUnit> regions = new ArrayList<>();
                    final LinkedHashSet<Long> seen = new LinkedHashSet<>();
                    for (final TileService.RegionUnit chunk : entry.getValue()) {
                        final int regionX = chunk.x() >> 4;
                        final int regionZ = chunk.z() >> 4;
                        final long key = ((long) regionX << 32) | (regionZ & 0xFFFFFFFFL);
                        if (seen.add(key)) {
                            regions.add(new TileService.RegionUnit(regionX, regionZ));
                        }
                    }
                    cache.ensureRegionsLoadedAsync(layer.type(), regions);
                }
            }
        }
    }

    void storeSnapshotsForTest(final List<ChunkSnapshot> snapshots, final MapLayer layer) {
        storeSnapshots(
            snapshots.stream().map(snapshot -> new CapturedSnapshot(snapshot, layer)).toList()
        );
    }

    public boolean liveTerrainPaused() {
        return terrainWorker.paused();
    }

    public String liveTerrainFault() {
        return terrainWorker.fault();
    }

    public void close() {
        terrainWorker.close();
    }

    private record CapturedSnapshot(ChunkSnapshot snapshot, MapLayer layer) {
    }

    private void logPeriodically(final MapWorld world) {
        if (++tickCounter < LOG_INTERVAL_TICKS) {
            return;
        }
        tickCounter = 0;
        final long stored = storedSnapshots.get();
        if (stored != lastLoggedSnapshots) {
            lastLoggedSnapshots = stored;
            ConfluxMapMod.LOGGER.info(
                "Capture: {} snapshots stored, {} dirty pending, {} regions in memory",
                stored, dirtyChunks.size(), world.totalRegions()
            );
        }
    }
}
