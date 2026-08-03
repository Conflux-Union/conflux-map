package cn.net.rms.confluxmap.core.config;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.predict.SeedInput;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/** Client-owned seed and worldgen-version choices, scoped to one multiplayer world identity. */
public final class ManualSeedConfig {
    private Map<String, Map<String, Entry>> servers = new TreeMap<>();

    public Optional<Entry> get(final WorldIdentity world) {
        if (world == null || !world.isPresent()) {
            return Optional.empty();
        }
        final Map<String, Entry> worlds = servers.get(world.serverId());
        return worlds == null ? Optional.empty() : Optional.ofNullable(worlds.get(world.worldId()));
    }

    public void set(final WorldIdentity world, final String seedInput, final String worldgenVersion) {
        if (world == null || !world.isPresent()) {
            throw new IllegalArgumentException("world must be active");
        }
        final Entry entry = Entry.create(seedInput, worldgenVersion);
        servers.computeIfAbsent(world.serverId(), ignored -> new TreeMap<>())
            .put(world.worldId(), entry);
    }

    public boolean clear(final WorldIdentity world) {
        if (world == null) {
            return false;
        }
        final Map<String, Entry> worlds = servers.get(world.serverId());
        if (worlds == null || worlds.remove(world.worldId()) == null) {
            return false;
        }
        if (worlds.isEmpty()) {
            servers.remove(world.serverId());
        }
        return true;
    }

    public ManualSeedConfig copy() {
        final ManualSeedConfig copy = new ManualSeedConfig();
        for (final Map.Entry<String, Map<String, Entry>> server : servers.entrySet()) {
            copy.servers.put(server.getKey(), new TreeMap<>(server.getValue()));
        }
        return copy;
    }

    /** Repairs hand-edited JSON and discards entries that cannot produce a supported prediction. */
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
                    final Entry entry = world.getValue().normalized();
                    if (entry != null) {
                        worlds.put(world.getKey(), entry);
                    }
                }
                if (!worlds.isEmpty()) {
                    normalized.put(server.getKey(), worlds);
                }
            }
        }
        servers = normalized;
    }

    public static final class Entry {
        private String seedInput;
        private long seed;
        private String worldgenVersion;

        private Entry() {
            // Gson
        }

        private Entry(final String seedInput, final long seed, final String worldgenVersion) {
            this.seedInput = seedInput;
            this.seed = seed;
            this.worldgenVersion = worldgenVersion;
        }

        static Entry create(final String seedInput, final String worldgenVersion) {
            final String normalizedInput = seedInput == null ? "" : seedInput.trim();
            final OptionalLong parsed = SeedInput.parse(normalizedInput);
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("seed must not be empty");
            }
            if (worldgenVersion == null || McVersions.toCubiomes(worldgenVersion).isEmpty()) {
                throw new IllegalArgumentException("unsupported worldgen version " + worldgenVersion);
            }
            return new Entry(normalizedInput, parsed.getAsLong(), worldgenVersion);
        }

        private Entry normalized() {
            try {
                return create(seedInput, worldgenVersion);
            } catch (final IllegalArgumentException ignored) {
                return null;
            }
        }

        public String seedInput() {
            return seedInput;
        }

        public long seed() {
            return seed;
        }

        public String worldgenVersion() {
            return worldgenVersion;
        }
    }
}
