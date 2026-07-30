package cn.net.rms.confluxmap.core.multiworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.apache.logging.log4j.Logger;

/** Atomic persistence for the client-owned world profile registry. */
public final class ClientWorldProfileIo {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    public ClientWorldProfileIo(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public ClientWorldProfileRegistry load() {
        if (!Files.exists(file)) {
            return new ClientWorldProfileRegistry();
        }
        try {
            final ClientWorldProfileRegistry registry = GSON.fromJson(
                Files.readString(file, StandardCharsets.UTF_8),
                ClientWorldProfileRegistry.class
            );
            if (registry == null) {
                throw new JsonParseException("empty client world registry");
            }
            registry.normalize();
            return registry;
        } catch (final IOException | JsonParseException | IllegalArgumentException e) {
            logger.warn("Client world registry {} unreadable ({}), quarantining", file, e.toString());
            quarantine();
            return new ClientWorldProfileRegistry();
        }
    }

    public void save(final ClientWorldProfileRegistry registry) {
        try {
            Files.createDirectories(file.getParent());
            final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(registry), StandardCharsets.UTF_8);
            move(temporary);
        } catch (final IOException e) {
            logger.error("Failed to save client world registry to {}", file, e);
        }
    }

    private void move(final Path temporary) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("Could not quarantine client world registry {}", file, e);
        }
    }
}
