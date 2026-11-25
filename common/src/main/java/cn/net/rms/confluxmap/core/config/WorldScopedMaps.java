package cn.net.rms.confluxmap.core.config;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.BiFunction;

/** Shared defensive copy and repair pass over the {@code servers} maps of the world-scoped configs. */
final class WorldScopedMaps {
    private WorldScopedMaps() {
    }

    static <E> Map<String, Map<String, E>> copy(final Map<String, Map<String, E>> servers) {
        final Map<String, Map<String, E>> copy = new TreeMap<>();
        for (final Map.Entry<String, Map<String, E>> server : servers.entrySet()) {
            copy.put(server.getKey(), new TreeMap<>(server.getValue()));
        }
        return copy;
    }

    /** Drops blank keys, null entries and emptied sections; applies {@code normalizeEntry} to the rest in place. */
    static <E> Map<String, Map<String, E>> normalize(
        final Map<String, Map<String, E>> servers, final BiFunction<String, E, E> normalizeEntry
    ) {
        final Map<String, Map<String, E>> normalized = new TreeMap<>();
        if (servers != null) {
            for (final Map.Entry<String, Map<String, E>> server : servers.entrySet()) {
                if (server.getKey() == null || server.getKey().isBlank() || server.getValue() == null) {
                    continue;
                }
                final Map<String, E> worlds = new TreeMap<>();
                for (final Map.Entry<String, E> world : server.getValue().entrySet()) {
                    if (world.getKey() == null || world.getKey().isBlank() || world.getValue() == null) {
                        continue;
                    }
                    final E entry = normalizeEntry.apply(world.getKey(), world.getValue());
                    if (entry != null) {
                        worlds.put(world.getKey(), entry);
                    }
                }
                if (!worlds.isEmpty()) {
                    normalized.put(server.getKey(), worlds);
                }
            }
        }
        return normalized;
    }
}
