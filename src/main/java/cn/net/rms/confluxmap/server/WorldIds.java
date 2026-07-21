package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.store.WorldIdStore;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/**
 * Stable per-world UUID namespace for cache keys. Reads/writes {@code
 * <worldRoot>/confluxmap/world_uuid.json} as JSON; generates a random UUID on first access. Cached
 * in-memory per {@link MinecraftServer} so repeat lookups during one server lifetime are free.
 *
 * <p>The actual file IO lives in {@link Io} (pure {@code java.nio.file}, MC-free, unit-tested);
 * this class only adds the {@link MinecraftServer}-keyed cache and the world-root path lookup.
 */
public final class WorldIds {
    private final ConcurrentMap<MinecraftServer, UUID> cache = new ConcurrentHashMap<>();

    /** Returns the stable UUID for {@code server}'s world root, generating and persisting it on first access. */
    public UUID get(final MinecraftServer server) {
        return cache.computeIfAbsent(server, this::loadOrGenerate);
    }

    /** Drops the cached entry for {@code server}; called on {@code SERVER_STOPPING}. */
    public void forget(final MinecraftServer server) {
        cache.remove(server);
    }

    private UUID loadOrGenerate(final MinecraftServer server) {
        final Path worldRoot = server.getSavePath(WorldSavePath.ROOT);
        return Io.loadOrCreate(worldRoot);
    }

    /**
     * MC-free entry point for {@code <worldRoot>/confluxmap/world_uuid.json}. Exposed for unit tests
     * so they can round-trip the file in a temp directory without spinning up a Minecraft server.
     */
    public static final class Io {
        private Io() {
        }

        /** {@code worldRoot} is the directory the server resolved as {@link WorldSavePath#ROOT}. */
        public static UUID loadOrCreate(final Path worldRoot) {
            return WorldIdStore.loadOrCreate(worldRoot);
        }
    }
}
