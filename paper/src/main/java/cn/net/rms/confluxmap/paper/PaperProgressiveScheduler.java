package cn.net.rms.confluxmap.paper;

import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** Selects which progressive Paper correction tile receives the next worker step. */
final class PaperProgressiveScheduler<K, V> {
    private K cursor;

    void tick(
        final Map<K, V> tasks,
        final Predicate<K> watched,
        final Predicate<V> complete,
        final Consumer<V> advance
    ) {
        Objects.requireNonNull(tasks, "tasks");
        Objects.requireNonNull(watched, "watched");
        Objects.requireNonNull(complete, "complete");
        Objects.requireNonNull(advance, "advance");

        K next = nextIncomplete(tasks, watched, complete, cursor, true);
        if (next == null) {
            next = nextIncomplete(tasks, watched, complete, cursor, false);
        }
        if (next == null) {
            cursor = null;
            return;
        }
        cursor = next;
        advance.accept(tasks.get(next));
    }

    void clear() {
        cursor = null;
    }

    private static <K, V> K nextIncomplete(
        final Map<K, V> tasks,
        final Predicate<K> watched,
        final Predicate<V> complete,
        final K cursor,
        final boolean watchedOnly
    ) {
        K first = null;
        boolean afterCursor = cursor == null;
        for (final Map.Entry<K, V> entry : tasks.entrySet()) {
            final boolean eligible = !complete.test(entry.getValue())
                && (!watchedOnly || watched.test(entry.getKey()));
            if (eligible && first == null) {
                first = entry.getKey();
            }
            if (Objects.equals(entry.getKey(), cursor)) {
                afterCursor = true;
                continue;
            }
            if (eligible && afterCursor) {
                return entry.getKey();
            }
        }
        return first;
    }
}
