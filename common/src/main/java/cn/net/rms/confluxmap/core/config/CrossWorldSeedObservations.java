package cn.net.rms.confluxmap.core.config;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Latest hashed seed observed for each multiplayer world identity the client has played. Vanilla
 * sends the hashed seed (a deterministic scramble of the real seed) in every join/respawn packet,
 * so two sub-worlds of one address share siblingship exactly when their observed hashes match;
 * a 64-bit scramble collision is negligible. Companion-instance namespaces never enter the client
 * profile bindings, which is why this log exists alongside them.
 *
 * <p>Each entry also keeps every name key the identity carried: the storage namespace plus its
 * legacy ids. Companion worlds are named by their world UUID in
 * {@code ClientWorldProfileRegistry.serverWorldNames} while storage keys on the instance id, so
 * label lookup has to try the UUID; it only exists here, inside {@link WorldIdentity#legacyStorageIds()}.
 */
public final class CrossWorldSeedObservations {
    private Map<String, Map<String, Entry>> servers = new TreeMap<>();

    /** Records the hash observed for {@code world}; returns true when the stored entry changed. */
    public boolean record(final WorldIdentity world, final long seedHash) {
        if (world == null || !world.isPresent()) {
            return false;
        }
        final Map<String, Entry> worlds = servers.computeIfAbsent(
            world.serverId(), ignored -> new TreeMap<>()
        );
        final List<String> keys = nameKeys(world);
        final Entry previous = worlds.put(world.worldId(), Entry.of(seedHash, keys));
        return previous == null
            || previous.seedHash != seedHash
            || !previous.nameKeys.equals(keys);
    }

    /** Observed entries by storage namespace for one server id; never null. */
    public Map<String, Entry> observations(final String serverId) {
        final Map<String, Entry> worlds = servers.get(serverId);
        return worlds == null ? Map.of() : Map.copyOf(worlds);
    }

    public CrossWorldSeedObservations copy() {
        final CrossWorldSeedObservations copy = new CrossWorldSeedObservations();
        for (final Map.Entry<String, Map<String, Entry>> server : servers.entrySet()) {
            copy.servers.put(server.getKey(), new TreeMap<>(server.getValue()));
        }
        return copy;
    }

    /** Repairs hand-edited JSON; drops blank keys and empty server sections. */
    public void normalize() {
        final Map<String, Map<String, Entry>> normalized = new TreeMap<>();
        if (servers != null) {
            for (final Map.Entry<String, Map<String, Entry>> server : servers.entrySet()) {
                if (server.getKey() == null || server.getKey().isBlank() || server.getValue() == null) {
                    continue;
                }
                final Map<String, Entry> worlds = new TreeMap<>();
                for (final Map.Entry<String, Entry> world : server.getValue().entrySet()) {
                    if (world.getKey() == null || world.getKey().isBlank() || world.getValue() == null) {
                        continue;
                    }
                    world.getValue().normalize(world.getKey());
                    worlds.put(world.getKey(), world.getValue());
                }
                if (!worlds.isEmpty()) {
                    normalized.put(server.getKey(), worlds);
                }
            }
        }
        servers = normalized;
    }

    private static List<String> nameKeys(final WorldIdentity world) {
        final List<String> keys = new ArrayList<>(1 + world.legacyStorageIds().size());
        keys.add(world.worldId());
        for (final String legacyId : world.legacyStorageIds()) {
            if (!keys.contains(legacyId)) {
                keys.add(legacyId);
            }
        }
        return keys;
    }

    /** Gson DTO; immutable once normalized. */
    public static final class Entry {
        private long seedHash;
        private List<String> nameKeys = new ArrayList<>();

        private Entry() {
            // Gson
        }

        static Entry of(final long seedHash, final List<String> nameKeys) {
            final Entry entry = new Entry();
            entry.seedHash = seedHash;
            entry.nameKeys = List.copyOf(nameKeys);
            return entry;
        }

        public long seedHash() {
            return seedHash;
        }

        public List<String> nameKeys() {
            return nameKeys;
        }

        private void normalize(final String storageId) {
            final List<String> keys = new ArrayList<>();
            keys.add(storageId);
            if (nameKeys != null) {
                for (final String key : nameKeys) {
                    if (key != null && !key.isBlank() && !keys.contains(key)) {
                        keys.add(key);
                    }
                }
            }
            nameKeys = List.copyOf(keys);
        }
    }
}
