package cn.net.rms.confluxmap.core.net;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Minecraft-free authoritative load-state index with one bounded viewport subscription per client.
 * Initial snapshots walk the sorted index without retaining fail-fast iterators; changes that race
 * a snapshot are coalesced and delivered after its completion marker.
 */
public final class ChunkLoadStatePublisher {
    private static final int MAX_PENDING_CHANGES = 8_192;

    private final Map<Integer, NavigableMap<ChunkKey, LoadStateDeltaS2C.Entry>> states = new LinkedHashMap<>();
    private final Map<UUID, Subscription> subscriptions = new LinkedHashMap<>();

    public void update(
        final int dimIndex,
        final int chunkX,
        final int chunkZ,
        final int level,
        final ChunkLoadBand band
    ) {
        Objects.requireNonNull(band, "band");
        if (band == ChunkLoadBand.UNLOADED || level == Proto.LOAD_STATE_UNLOADED_LEVEL) {
            throw new IllegalArgumentException("loaded state cannot use the unloaded sentinel");
        }
        final ChunkKey key = new ChunkKey(chunkX, chunkZ);
        final LoadStateDeltaS2C.Entry candidate = new LoadStateDeltaS2C.Entry(chunkX, chunkZ, level, band);
        final LoadStateDeltaS2C.Entry previous = states
            .computeIfAbsent(dimIndex, ignored -> new TreeMap<>())
            .put(key, candidate);
        if (!candidate.equals(previous)) {
            publish(dimIndex, key, candidate);
        }
    }

    public void remove(final int dimIndex, final int chunkX, final int chunkZ) {
        final NavigableMap<ChunkKey, LoadStateDeltaS2C.Entry> dimension = states.get(dimIndex);
        final ChunkKey key = new ChunkKey(chunkX, chunkZ);
        if (dimension == null || dimension.remove(key) == null) {
            return;
        }
        if (dimension.isEmpty()) {
            states.remove(dimIndex);
        }
        publish(
            dimIndex,
            key,
            new LoadStateDeltaS2C.Entry(
                chunkX, chunkZ, Proto.LOAD_STATE_UNLOADED_LEVEL, ChunkLoadBand.UNLOADED
            )
        );
    }

    public void subscribe(final UUID clientId, final LoadStateSubscribeC2S request) {
        Objects.requireNonNull(clientId, "clientId");
        Objects.requireNonNull(request, "request");
        if (!request.active()) {
            subscriptions.remove(clientId);
            return;
        }
        subscriptions.put(clientId, new Subscription(request));
    }

    public void unsubscribe(final UUID clientId) {
        subscriptions.remove(clientId);
    }

    /** Returns at most one payload; {@code inspectionBudget} bounds initial-index work. */
    public LoadStateDeltaS2C poll(final UUID clientId, final int inspectionBudget) {
        final Subscription subscription = subscriptions.get(clientId);
        if (subscription == null) {
            return null;
        }
        if (!subscription.snapshotComplete) {
            return pollSnapshot(subscription, Math.max(1, inspectionBudget));
        }
        if (subscription.pending.isEmpty()) {
            return null;
        }
        final List<LoadStateDeltaS2C.Entry> entries = drainPending(subscription);
        return new LoadStateDeltaS2C(subscription.request.subscriptionId(), false, false, entries);
    }

    public void clear() {
        states.clear();
        subscriptions.clear();
    }

    private LoadStateDeltaS2C pollSnapshot(final Subscription subscription, final int inspectionBudget) {
        final NavigableMap<ChunkKey, LoadStateDeltaS2C.Entry> dimension = states.get(subscription.request.dimIndex());
        final List<LoadStateDeltaS2C.Entry> entries = new ArrayList<>();
        int inspected = 0;
        while (dimension != null && inspected < inspectionBudget && entries.size() < Proto.MAX_LOAD_STATE_ENTRIES) {
            final Map.Entry<ChunkKey, LoadStateDeltaS2C.Entry> next = subscription.cursor == null
                ? dimension.firstEntry()
                : dimension.higherEntry(subscription.cursor);
            if (next == null) {
                break;
            }
            subscription.cursor = next.getKey();
            inspected++;
            if (subscription.contains(next.getKey())) {
                entries.add(next.getValue());
            }
        }
        final boolean complete = dimension == null
            || dimension.isEmpty()
            || (subscription.cursor != null && dimension.higherEntry(subscription.cursor) == null);
        final boolean reset = !subscription.resetSent;
        subscription.resetSent = true;
        subscription.snapshotComplete = complete;
        return new LoadStateDeltaS2C(
            subscription.request.subscriptionId(), reset, complete, entries
        );
    }

    private static List<LoadStateDeltaS2C.Entry> drainPending(final Subscription subscription) {
        final List<LoadStateDeltaS2C.Entry> entries = new ArrayList<>(
            Math.min(Proto.MAX_LOAD_STATE_ENTRIES, subscription.pending.size())
        );
        final var iterator = subscription.pending.entrySet().iterator();
        while (iterator.hasNext() && entries.size() < Proto.MAX_LOAD_STATE_ENTRIES) {
            entries.add(iterator.next().getValue());
            iterator.remove();
        }
        return entries;
    }

    private void publish(
        final int dimIndex,
        final ChunkKey key,
        final LoadStateDeltaS2C.Entry entry
    ) {
        for (final Subscription subscription : subscriptions.values()) {
            if (subscription.request.dimIndex() != dimIndex || !subscription.contains(key)) {
                continue;
            }
            subscription.pending.put(key, entry);
            if (subscription.pending.size() > MAX_PENDING_CHANGES) {
                subscription.restartSnapshot();
            }
        }
    }

    private record ChunkKey(int chunkX, int chunkZ) implements Comparable<ChunkKey> {
        @Override
        public int compareTo(final ChunkKey other) {
            final int byX = Integer.compare(chunkX, other.chunkX);
            return byX != 0 ? byX : Integer.compare(chunkZ, other.chunkZ);
        }
    }

    private static final class Subscription {
        private final LoadStateSubscribeC2S request;
        private final LinkedHashMap<ChunkKey, LoadStateDeltaS2C.Entry> pending = new LinkedHashMap<>();
        private ChunkKey cursor;
        private boolean resetSent;
        private boolean snapshotComplete;

        private Subscription(final LoadStateSubscribeC2S request) {
            this.request = request;
        }

        private boolean contains(final ChunkKey key) {
            return key.chunkX >= request.minChunkX() && key.chunkX <= request.maxChunkX()
                && key.chunkZ >= request.minChunkZ() && key.chunkZ <= request.maxChunkZ();
        }

        private void restartSnapshot() {
            pending.clear();
            cursor = null;
            resetSent = false;
            snapshotComplete = false;
        }
    }
}
