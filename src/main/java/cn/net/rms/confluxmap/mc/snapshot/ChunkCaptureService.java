package cn.net.rms.confluxmap.mc.snapshot;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.SampleSource;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.DirtyChunkSet;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;

/**
 * Drives the capture pipeline: packet hooks mark chunks dirty (via
 * {@link ChunkCaptureHandler}); each tick a bounded number of the nearest
 * dirty chunks is snapshotted on the main thread and merged into the store
 * on a worker thread.
 */
public final class ChunkCaptureService {
    private static final int LOG_INTERVAL_TICKS = 100;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final MapWorldService worlds;
    private final MapExecutors executors;
    private final McChunkSnapshotFactory factory;
    private final DirtyChunkSet dirtyChunks = new DirtyChunkSet();
    private final AtomicLong storedSnapshots = new AtomicLong();
    private long lastLoggedSnapshots = -1;
    private int tickCounter;

    public ChunkCaptureService(
        final MinecraftClient client,
        final ConfluxConfig config,
        final MapWorldService worlds,
        final MapExecutors executors
    ) {
        this.client = client;
        this.config = config;
        this.worlds = worlds;
        this.executors = executors;
        this.factory = new McChunkSnapshotFactory(client);
    }

    public void register() {
        ChunkCaptureHandler.bind(this);
        ClientTickEvents.END_CLIENT_TICK.register(c -> tick());
    }

    /** Main thread, from the session tracker. */
    public void onSessionChanged(final SessionGuard.Session session) {
        dirtyChunks.clear();
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

    private void tick() {
        final MapWorld world = worlds.current();
        final ClientPlayerEntity player = client.player;
        if (world == null || player == null) {
            return;
        }
        final long token = world.session().token();
        final List<long[]> batch = dirtyChunks.drainNearest(
            config.snapshotBudgetPerTick,
            player.getBlockPos().getX() >> 4,
            player.getBlockPos().getZ() >> 4
        );
        for (final long[] chunkPos : batch) {
            final ChunkSnapshot snapshot = factory.snapshot((int) chunkPos[0], (int) chunkPos[1], token);
            if (snapshot != null) {
                executors.workers().execute(() -> storeSnapshot(snapshot));
            }
        }
        logPeriodically(world);
    }

    private void storeSnapshot(final ChunkSnapshot snapshot) {
        final MapWorld world = worlds.ifCurrent(snapshot.sessionToken);
        if (world != null && world.store(MapLayer.SURFACE).put(snapshot, SampleSource.REAL_LIVE)) {
            storedSnapshots.incrementAndGet();
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
