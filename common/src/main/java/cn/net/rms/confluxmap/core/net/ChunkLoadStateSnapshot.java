package cn.net.rms.confluxmap.core.net;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Thread-safe client state for the one active load-state subscription. */
public final class ChunkLoadStateSnapshot {
    private final Map<ChunkKey, LoadStateDeltaS2C.Entry> entries = new LinkedHashMap<>();
    private int subscriptionId;
    private int dimIndex = -1;
    private boolean active;
    private boolean complete;

    public synchronized void begin(final int subscriptionId, final int dimIndex) {
        this.subscriptionId = subscriptionId;
        this.dimIndex = dimIndex;
        active = true;
        complete = false;
        entries.clear();
    }

    public synchronized boolean apply(final LoadStateDeltaS2C delta) {
        if (!active || delta.subscriptionId() != subscriptionId) {
            return false;
        }
        if (delta.reset()) {
            entries.clear();
            complete = false;
        }
        for (final LoadStateDeltaS2C.Entry entry : delta.entries()) {
            final ChunkKey key = new ChunkKey(entry.chunkX(), entry.chunkZ());
            if (entry.band() == ChunkLoadBand.UNLOADED) {
                entries.remove(key);
            } else {
                entries.put(key, entry);
            }
        }
        if (delta.complete()) {
            complete = true;
        }
        return true;
    }

    public synchronized Optional<LoadStateDeltaS2C.Entry> get(final int chunkX, final int chunkZ) {
        return Optional.ofNullable(entries.get(new ChunkKey(chunkX, chunkZ)));
    }

    public synchronized List<LoadStateDeltaS2C.Entry> entries() {
        return List.copyOf(entries.values());
    }

    public synchronized boolean complete() {
        return complete;
    }

    public synchronized boolean active() {
        return active;
    }

    public synchronized int subscriptionId() {
        return subscriptionId;
    }

    public synchronized int dimIndex() {
        return dimIndex;
    }

    public synchronized void reset() {
        entries.clear();
        active = false;
        complete = false;
        dimIndex = -1;
    }

    private record ChunkKey(int chunkX, int chunkZ) {
    }
}
