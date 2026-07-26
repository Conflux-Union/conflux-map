package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.ChunkLoadStatePublisher;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.WorldChunk;

/** Tracks loaded chunks, refreshes their effective levels, and drains bounded client deltas. */
public final class ChunkLoadStateService {
    static final int MAX_CHUNK_INSPECTIONS_PER_TICK = 4_096;
    private static final int MAX_LEVEL_CHECKS_PER_TICK = MAX_CHUNK_INSPECTIONS_PER_TICK / 2;

    private final ChunkLoadStatePublisher publisher = new ChunkLoadStatePublisher();
    private final Map<LoadedKey, ChunkPos> loadedChunks = new LinkedHashMap<>();
    private final LinkedHashSet<LoadedKey> levelCheckQueue = new LinkedHashSet<>();
    private final Map<UUID, Consumer<LoadStateDeltaS2C>> subscribers = new LinkedHashMap<>();

    private record LoadedKey(ServerWorld world, int chunkX, int chunkZ) {
    }

    public void onChunkLoad(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final LoadedKey key = new LoadedKey(world, chunkX(pos), chunkZ(pos));
        loadedChunks.put(key, pos);
        levelCheckQueue.add(key);
        refresh(key, pos);
    }

    public void onChunkUnload(final ServerWorld world, final WorldChunk chunk) {
        final ChunkPos pos = chunk.getPos();
        final LoadedKey key = new LoadedKey(world, chunkX(pos), chunkZ(pos));
        loadedChunks.remove(key);
        levelCheckQueue.remove(key);
        final int dimIndex = worldIndex(world.getServer(), world);
        if (dimIndex >= 0) {
            publisher.remove(dimIndex, key.chunkX(), key.chunkZ());
        }
    }

    /** Returns false for an invalid dimension index; the networking boundary sends the error. */
    public boolean subscribe(
        final MinecraftServer server,
        final UUID playerId,
        final LoadStateSubscribeC2S request,
        final Consumer<LoadStateDeltaS2C> sender
    ) {
        if (!request.active()) {
            remove(playerId);
            return true;
        }
        if (worldAt(server, request.dimIndex()) == null) {
            return false;
        }
        publisher.subscribe(playerId, request);
        subscribers.put(playerId, sender);
        return true;
    }

    public void remove(final UUID playerId) {
        publisher.unsubscribe(playerId);
        subscribers.remove(playerId);
    }

    public void tick(final MinecraftServer server) {
        refreshLevels(MAX_LEVEL_CHECKS_PER_TICK);
        int snapshotBudget = MAX_CHUNK_INSPECTIONS_PER_TICK - MAX_LEVEL_CHECKS_PER_TICK;
        int remainingSubscribers = Math.min(subscribers.size(), snapshotBudget);
        while (remainingSubscribers > 0) {
            final Iterator<Map.Entry<UUID, Consumer<LoadStateDeltaS2C>>> iterator =
                subscribers.entrySet().iterator();
            final Map.Entry<UUID, Consumer<LoadStateDeltaS2C>> subscriber = iterator.next();
            final UUID playerId = subscriber.getKey();
            final Consumer<LoadStateDeltaS2C> sender = subscriber.getValue();
            iterator.remove();
            subscribers.put(playerId, sender);

            final int share = Math.max(1, snapshotBudget / remainingSubscribers);
            final LoadStateDeltaS2C delta = publisher.poll(playerId, share);
            if (delta != null) {
                sender.accept(delta);
            }
            snapshotBudget -= share;
            remainingSubscribers--;
        }
    }

    public void clear() {
        publisher.clear();
        loadedChunks.clear();
        levelCheckQueue.clear();
        subscribers.clear();
    }

    private void refreshLevels(final int budget) {
        final int available = levelCheckQueue.size();
        for (int inspected = 0; inspected < available && inspected < budget; inspected++) {
            final Iterator<LoadedKey> iterator = levelCheckQueue.iterator();
            final LoadedKey key = iterator.next();
            iterator.remove();
            final ChunkPos pos = loadedChunks.get(key);
            if (pos == null) {
                continue;
            }
            levelCheckQueue.add(key);
            refresh(key, pos);
        }
    }

    private void refresh(final LoadedKey key, final ChunkPos pos) {
        final int dimIndex = worldIndex(key.world().getServer(), key.world());
        if (dimIndex < 0) {
            return;
        }
        ChunkLoadStateAccess.read(key.world(), pos).ifPresentOrElse(
            state -> publisher.update(dimIndex, key.chunkX(), key.chunkZ(), state.level(), state.band()),
            () -> publisher.remove(dimIndex, key.chunkX(), key.chunkZ())
        );
    }

    private static ServerWorld worldAt(final MinecraftServer server, final int targetIndex) {
        if (targetIndex < 0) {
            return null;
        }
        int index = 0;
        for (final ServerWorld world : server.getWorlds()) {
            if (index++ == targetIndex) {
                return world;
            }
        }
        return null;
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
