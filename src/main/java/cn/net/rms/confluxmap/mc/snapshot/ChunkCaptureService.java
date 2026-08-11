package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;
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
import cn.net.rms.confluxmap.core.task.DirtyChunkSet;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.mc.color.BiomeTintResolver;
import cn.net.rms.confluxmap.mc.color.SpriteColorSampler;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Drives the capture pipeline: packet hooks mark chunks dirty (via
 * {@link ChunkCaptureHandler}); each tick, {@link LayerSelector} decides the
 * one active layer (per cave-nether-layers.md §1), and a bounded number of
 * the nearest dirty chunks is snapshotted into that layer on the main thread
 * and merged into the store on a worker thread, which then tells the {@link
 * TileService} which tile(s) need recomposing. Only one layer is captured at
 * a time; when the active layer (or its floor-scan pivot Y) changes, the
 * whole server send-distance square is reseeded into the new layer, the same way a
 * session change reseeds it.
 */
public final class ChunkCaptureService {
    private static final int LOG_INTERVAL_TICKS = 100;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final MapWorldService worlds;
    private final MapExecutors executors;
    private final TileService tiles;
    private final PredictionTileService predictionTiles;
    private final IntSupplier serverViewDistance;
    private final RegionCacheService regionCache;
    private final LayerSelector layerSelector;
    private final McChunkSnapshotFactory factory;
    private final DirtyChunkSet dirtyChunks = new DirtyChunkSet();
    private final AtomicLong storedSnapshots = new AtomicLong();
    private long lastLoggedSnapshots = -1;
    private int tickCounter;
    private LayerSelector.Decision lastDecision;
    private int lastPlayerChunkX = Integer.MIN_VALUE;
    private int lastPlayerChunkZ = Integer.MIN_VALUE;
    private int lastServerViewDistance = Integer.MIN_VALUE;

    public ChunkCaptureService(
        final MinecraftClient client,
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
        this.config = config;
        this.worlds = worlds;
        this.executors = executors;
        this.tiles = tiles;
        this.predictionTiles = predictionTiles;
        this.serverViewDistance = serverViewDistance;
        this.regionCache = regionCache;
        this.layerSelector = layerSelector;
        this.factory = new McChunkSnapshotFactory(client, sampler, tintResolver);
    }

    public void register() {
        ChunkCaptureHandler.bind(this);
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    /**
     * Main thread, from the session tracker. The initial spawn-area chunk batch
     * arrives before the first session tick, so marks made during the loading
     * phase reference a world this session never saw. Instead of trusting them,
     * reseed the whole server send-distance square around the player: chunks that are
     * not actually loaded are skipped by the snapshot factory at drain time.
     */
    public void onSessionChanged(final SessionGuard.Session session) {
        dirtyChunks.clear();
        layerSelector.onSessionChanged(session);
        lastDecision = null;
        lastPlayerChunkX = Integer.MIN_VALUE;
        lastPlayerChunkZ = Integer.MIN_VALUE;
        lastServerViewDistance = Integer.MIN_VALUE;
        if (!session.active()) {
            return;
        }
        final ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        reseedViewport(player.getBlockPos().getX() >> 4, player.getBlockPos().getZ() >> 4);
    }

    /** Main thread, from packet mixins. */
    public void markDirty(final int chunkX, final int chunkZ) {
        dirtyChunks.mark(chunkX, chunkZ);
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
        if (world == null || player == null) {
            return;
        }
        final long token = world.session().token();
        final int playerChunkX = player.getBlockPos().getX() >> 4;
        final int playerChunkZ = player.getBlockPos().getZ() >> 4;
        final int currentServerViewDistance = serverViewDistance.getAsInt();
        if (currentServerViewDistance != lastServerViewDistance
            || (currentServerViewDistance >= 0
            && (playerChunkX != lastPlayerChunkX || playerChunkZ != lastPlayerChunkZ))) {
            lastPlayerChunkX = playerChunkX;
            lastPlayerChunkZ = playerChunkZ;
            lastServerViewDistance = currentServerViewDistance;
            tiles.setLocalAuthorityViewport(ChunkViewport.centered(
                playerChunkX,
                playerChunkZ,
                captureViewDistance(MinecraftAccess.viewDistance(client), currentServerViewDistance)
            ));
            predictionTiles.refreshLiveCoverage();
        }

        final LayerSelector.Decision decision = layerSelector.tick();
        if (!decision.equals(lastDecision)) {
            // Layer (or its floor-scan pivot Y band) changed - reseed so the new layer fills the
            // viewport instead of leaving stale/empty data from whatever was active before.
            lastDecision = decision;
            reseedViewport(playerChunkX, playerChunkZ);
        }

        final List<long[]> batch = dirtyChunks.drainNearest(config.snapshotBudgetPerTick, playerChunkX, playerChunkZ);
        for (final long[] chunkPos : batch) {
            final ChunkSnapshot snapshot = factory.snapshot((int) chunkPos[0], (int) chunkPos[1], decision.layer(), decision.pivotY(), token);
            if (snapshot != null) {
                final MapLayer layer = decision.layer();
                executors.workers().execute(() -> storeSnapshot(snapshot, layer));
            }
        }
        final RegionDiskCache cache = regionCache.current();
        if (cache != null) {
            cache.tick(playerChunkX >> 4, playerChunkZ >> 4);
        }
        logPeriodically(world);
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

    private void storeSnapshot(final ChunkSnapshot snapshot, final MapLayer layer) {
        final MapWorld world = worlds.ifCurrent(snapshot.sessionToken);
        if (world != null && world.put(layer, snapshot, SampleSource.REAL_LIVE)) {
            storedSnapshots.incrementAndGet();
            tiles.markChunkStored(snapshot.sessionToken, world.session().dimension(), layer, snapshot.chunkX, snapshot.chunkZ);
            // Non-persistent layers (CAVE_AUTO, NETHER_CURRENT, the Y-slices) never touch disk -
            // ensureRegionLoaded() also self-guards, but skip the call entirely for clarity here.
            if (layer.type().persistent()) {
                final RegionDiskCache cache = regionCache.current();
                if (cache != null) {
                    cache.ensureRegionLoaded(layer.type(), snapshot.chunkX >> 4, snapshot.chunkZ >> 4);
                }
            }
        }
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
