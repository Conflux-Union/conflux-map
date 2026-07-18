package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.ConfluxMapMod;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.WorldSavePath;

/**
 * Stable per-world UUID namespace for cache keys. Reads/writes {@code
 * <worldRoot>/confluxmap/world_uuid} as JSON; generates a random UUID on first access. Cached
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
     * MC-free IO for {@code <worldRoot>/confluxmap/world_uuid}. Exposed for unit tests so they
     * can round-trip the file in a temp directory without spinning up a Minecraft server.
     */
    public static final class Io {
        private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
        private static final String FILE_NAME = "world_uuid.json";

        private Io() {
        }

        /** {@code worldRoot} is the directory the server resolved as {@link WorldSavePath#ROOT}. */
        public static UUID loadOrCreate(final Path worldRoot) {
            final Path dir = worldRoot.resolve(ConfluxMapMod.ID);
            final Path file = dir.resolve(FILE_NAME);
            if (Files.exists(file)) {
                try {
                    final UUID parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
                    if (parsed != null) {
                        return parsed;
                    }
                    ConfluxMapMod.LOGGER.warn("world_uuid.json unreadable, regenerating ({})", file);
                } catch (final IOException | JsonParseException e) {
                    ConfluxMapMod.LOGGER.warn("world_uuid.json read failed, regenerating ({})", file, e);
                }
            }
            final UUID fresh = UUID.randomUUID();
            try {
                writeAtomic(file, fresh);
            } catch (final IOException e) {
                ConfluxMapMod.LOGGER.warn("Failed to persist world_uuid.json at {} (using in-memory UUID)", file, e);
            }
            return fresh;
        }

        static UUID parse(final String json) {
            if (json == null || json.isBlank()) {
                return null;
            }
            try {
                final Record r = GSON.fromJson(json, Record.class);
                if (r == null || r.uuid == null) {
                    return null;
                }
                return UUID.fromString(r.uuid);
            } catch (final IllegalArgumentException | JsonParseException e) {
                return null;
            }
        }

        static void writeAtomic(final Path file, final UUID uuid) throws IOException {
            Files.createDirectories(file.getParent());
            final String json = GSON.toJson(new Record(uuid.toString()));
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, json, StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (final AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        }

        /** Gson carrier record; lowercase field name to match the JSON property {@code "uuid"}. */
        private static final class Record {
            String uuid;

            Record() {
            }

            Record(final String uuid) {
                this.uuid = uuid;
            }
        }
    }
}
