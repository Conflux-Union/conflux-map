package cn.net.rms.confluxmap.core.store;

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
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Persists the stable UUID shared by every local and companion view of one world root. */
public final class WorldIdStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/WorldIdStore");
    private static final String DIRECTORY_NAME = "confluxmap";
    private static final String FILE_NAME = "world_uuid.json";

    private WorldIdStore() {
    }

    /** Generates the UUID once and keeps it inside the save so deleting the save deletes its identity. */
    public static synchronized UUID loadOrCreate(final Path worldRoot) {
        final Path file = worldRoot.resolve(DIRECTORY_NAME).resolve(FILE_NAME);
        if (Files.exists(file)) {
            try {
                final UUID parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed != null) {
                    return parsed;
                }
                LOGGER.warn("world_uuid.json unreadable, regenerating ({})", file);
            } catch (final IOException | JsonParseException e) {
                LOGGER.warn("world_uuid.json read failed, regenerating ({})", file, e);
            }
        }
        final UUID fresh = UUID.randomUUID();
        try {
            writeAtomic(file, fresh);
        } catch (final IOException e) {
            LOGGER.warn("Failed to persist world_uuid.json at {} (using in-memory UUID)", file, e);
        }
        return fresh;
    }

    static UUID parse(final String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            final Record record = GSON.fromJson(json, Record.class);
            if (record == null || record.uuid == null) {
                return null;
            }
            return UUID.fromString(record.uuid);
        } catch (final IllegalArgumentException | JsonParseException e) {
            return null;
        }
    }

    static void writeAtomic(final Path file, final UUID uuid) throws IOException {
        Files.createDirectories(file.getParent());
        final String json = GSON.toJson(new Record(uuid.toString()));
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        Files.writeString(temporary, json, StandardCharsets.UTF_8);
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static final class Record {
        String uuid;

        Record() {
        }

        Record(final String uuid) {
            this.uuid = uuid;
        }
    }
}
