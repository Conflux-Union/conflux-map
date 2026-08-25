package cn.net.rms.confluxmap.core.radar;

import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Render-thread LRU state machine for lazily baked icons. Successful values stay cached until
 * explicitly invalidated, while failed bakes use a bounded retry backoff.
 */
public final class IconBakeCache<K, V> {
    private static final class Entry<V> {
        private V value;
        private long retryAt;
        private boolean queued;
    }

    private final int capacity;
    private final long retryTicks;
    private final LinkedHashMap<K, Entry<V>> entries = new LinkedHashMap<>(16, 0.75f, true);
    private final ArrayDeque<K> pending = new ArrayDeque<>();

    public IconBakeCache(final int capacity, final long retryTicks) {
        if (capacity < 1 || retryTicks < 1) {
            throw new IllegalArgumentException("capacity and retryTicks must be positive");
        }
        this.capacity = capacity;
        this.retryTicks = retryTicks;
    }

    public Optional<V> request(final K key, final long now) {
        final Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>());
        final boolean retryable = entry.value == null && now >= entry.retryAt;
        if (!entry.queued && retryable) {
            entry.queued = true;
            pending.addLast(key);
        }
        return Optional.ofNullable(entry.value);
    }

    public Optional<K> pollNext(final long now) {
        while (!pending.isEmpty()) {
            final K key = pending.removeFirst();
            final Entry<V> entry = entries.get(key);
            if (entry != null && entry.queued && now >= entry.retryAt) {
                entry.queued = false;
                return Optional.of(key);
            }
        }
        return Optional.empty();
    }

    /** Returns the current value without scheduling refresh work. */
    public Optional<V> value(final K key) {
        final Entry<V> entry = entries.get(key);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.value);
    }

    /** Publishes a bake and returns the value evicted from the LRU, if any. */
    public Optional<V> complete(final K key, final V value, final long now) {
        final Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>());
        entry.value = value;
        entry.retryAt = 0;
        entry.queued = false;
        entries.get(key); // touch after completion

        if (entries.size() <= capacity) {
            return Optional.empty();
        }
        final Iterator<Map.Entry<K, Entry<V>>> iterator = entries.entrySet().iterator();
        final Map.Entry<K, Entry<V>> eldest = iterator.next();
        iterator.remove();
        return Optional.ofNullable(eldest.getValue().value);
    }

    public void fail(final K key, final long now) {
        final Entry<V> entry = entries.computeIfAbsent(key, ignored -> new Entry<>());
        entry.retryAt = now + retryTicks;
        entry.queued = false;
    }

    public void clear() {
        entries.clear();
        pending.clear();
    }
}
