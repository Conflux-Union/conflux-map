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
import org.apache.logging.log4j.Logger;

/**
 * One UUID in one JSON file, generated on first use and stable afterwards.
 *
 * <p>Shared by every identity this mod pins to a directory. An unreadable file is replaced rather
 * than propagated as a failure: the identity it named is already unrecoverable, and refusing to
 * start would be a worse outcome than a fresh namespace.
 */
final class UuidFileStore {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private UuidFileStore() {
    }

    static synchronized UUID loadOrCreate(final Path file, final Logger logger) {
        if (Files.exists(file)) {
            try {
                final UUID parsed = parse(Files.readString(file, StandardCharsets.UTF_8));
                if (parsed != null) {
                    return parsed;
                }
                logger.warn("{} unreadable, regenerating ({})", file.getFileName(), file);
            } catch (final IOException | JsonParseException e) {
                logger.warn("{} read failed, regenerating ({})", file.getFileName(), file, e);
            }
        }
        final UUID fresh = UUID.randomUUID();
        try {
            writeAtomic(file, fresh);
        } catch (final IOException e) {
            logger.warn("Failed to persist {} (using in-memory UUID)", file, e);
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
