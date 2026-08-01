package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.ChunkLoadStatePublisher;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.LoadStateSubscribeC2S;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.bukkit.Chunk;

/** Paper API adapter for authoritative chunk load levels and bounded viewport deltas. */
final class PaperChunkLoadStateService {
    private static final int MAX_INSPECTIONS_PER_TICK = 4_096;

    private record Key(PaperWorldDirectory.Entry world, int chunkX, int chunkZ) {
    }

    private final ChunkLoadStatePublisher publisher = new ChunkLoadStatePublisher();
    private final Map<Key, Chunk> loaded = new LinkedHashMap<>();
    private final Map<UUID, Consumer<LoadStateDeltaS2C>> subscribers = new LinkedHashMap<>();

    void onChunkLoad(final PaperWorldDirectory.Entry world, final Chunk chunk) {
        if (world == null || chunk == null) {
            return;
        }
        final Key key = new Key(world, chunk.getX(), chunk.getZ());
        loaded.put(key, chunk);
        refresh(key, chunk);
    }

    void onChunkUnload(final PaperWorldDirectory.Entry world, final Chunk chunk) {
        if (world == null || chunk == null) {
            return;
        }
        final Key key = new Key(world, chunk.getX(), chunk.getZ());
        loaded.remove(key);
        publisher.remove(world.index(), chunk.getX(), chunk.getZ());
    }

    boolean subscribe(
        final UUID playerId,
        final LoadStateSubscribeC2S request,
        final PaperWorldDirectory worlds,
        final Consumer<LoadStateDeltaS2C> sender
    ) {
        if (!request.active()) {
            remove(playerId);
            return true;
        }
        if (worlds.at(request.dimIndex()) == null) {
            return false;
        }
        publisher.subscribe(playerId, request);
        subscribers.put(playerId, sender);
        return true;
    }

    void remove(final UUID playerId) {
        publisher.unsubscribe(playerId);
        subscribers.remove(playerId);
    }

    void tick() {
        int inspected = 0;
        final int loadedCount = loaded.size();
        while (inspected < loadedCount && inspected < MAX_INSPECTIONS_PER_TICK / 2) {
            final Iterator<Map.Entry<Key, Chunk>> iterator = loaded.entrySet().iterator();
            final Map.Entry<Key, Chunk> entry = iterator.next();
            iterator.remove();
            loaded.put(entry.getKey(), entry.getValue());
            refresh(entry.getKey(), entry.getValue());
            inspected++;
        }
        int snapshotBudget = MAX_INSPECTIONS_PER_TICK - inspected;
        int remaining = subscribers.size();
        while (remaining > 0 && snapshotBudget > 0) {
            final Iterator<Map.Entry<UUID, Consumer<LoadStateDeltaS2C>>> iterator =
                subscribers.entrySet().iterator();
            final Map.Entry<UUID, Consumer<LoadStateDeltaS2C>> entry = iterator.next();
            iterator.remove();
            subscribers.put(entry.getKey(), entry.getValue());
            final int share = Math.max(1, snapshotBudget / remaining);
            final LoadStateDeltaS2C delta = publisher.poll(entry.getKey(), share);
            if (delta != null) {
                entry.getValue().accept(delta);
            }
            snapshotBudget -= share;
            remaining--;
        }
    }

    void clear() {
        publisher.clear();
        loaded.clear();
        subscribers.clear();
    }

    private void refresh(final Key key, final Chunk chunk) {
        final Chunk.LoadLevel loadLevel = chunk.getLoadLevel();
        final int level;
        final ChunkLoadBand band;
        switch (loadLevel) {
            case ENTITY_TICKING -> {
                level = 31;
                band = ChunkLoadBand.ENTITY_TICKING;
            }
            case TICKING -> {
                level = 32;
                band = ChunkLoadBand.BLOCK_TICKING;
            }
            case BORDER -> {
                level = 33;
                band = ChunkLoadBand.BORDER;
            }
            default -> {
                publisher.remove(key.world().index(), key.chunkX(), key.chunkZ());
                return;
            }
        }
        publisher.update(key.world().index(), key.chunkX(), key.chunkZ(), level, band);
    }
}
