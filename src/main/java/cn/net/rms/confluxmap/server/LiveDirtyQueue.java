package cn.net.rms.confluxmap.server;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Predicate;

/** Coalesces dirty loaded objects until a later live-view demand is ready to consume them. */
final class LiveDirtyQueue<K> {
    private final ArrayDeque<K> queue = new ArrayDeque<>();
    private final Set<K> queued = new HashSet<>();

    void mark(final K key) {
        if (queued.add(key)) {
            queue.addFirst(key);
        }
    }

    K pollMatching(final Predicate<K> eligible, final int maxInspections) {
        final int inspections = Math.min(queue.size(), Math.max(0, maxInspections));
        for (int inspected = 0; inspected < inspections; inspected++) {
            final K key = queue.removeFirst();
            if (!queued.contains(key)) {
                continue;
            }
            if (eligible.test(key)) {
                queued.remove(key);
                return key;
            }
            queue.addLast(key);
        }
        return null;
    }

    void remove(final K key) {
        queued.remove(key);
    }

    void clear() {
        queue.clear();
        queued.clear();
    }
}
