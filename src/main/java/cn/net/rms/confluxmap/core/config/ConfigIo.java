package cn.net.rms.confluxmap.core.config;

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

/**
 * Loads and saves {@link ConfluxConfig}. Writes are atomic (tmp + move);
 * a corrupt file is quarantined as {@code *.bad} and replaced with defaults
 * so a broken edit never wedges the mod. Loading also upgrades the on-disk
 * document in place: fields added since the file was written are persisted
 * with their defaults, while a file stamped by a newer schema is left intact.
 */
public final class ConfigIo {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    public ConfigIo(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public ConfluxConfig load() {
        if (!Files.exists(file)) {
            final ConfluxConfig fresh = new ConfluxConfig();
            save(fresh);
            return fresh;
        }
        try {
            final String json = Files.readString(file, StandardCharsets.UTF_8);
            final ConfluxConfig config = GSON.fromJson(json, ConfluxConfig.class);
            if (config == null) {
                throw new JsonParseException("empty config");
            }
            config.normalize();
            upgradeOnDisk(json, config);
            return config;
        } catch (final IOException | JsonParseException e) {
            logger.warn("Config file {} unreadable ({}), quarantining and using defaults", file, e.toString());
            quarantine();
            final ConfluxConfig fresh = new ConfluxConfig();
            save(fresh);
            return fresh;
        }
    }

    public void save(final ConfluxConfig config) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            move(tmp);
        } catch (final IOException e) {
            logger.error("Failed to save config to {}", file, e);
        }
    }

    /**
     * Rewrites the file when its content no longer matches the current schema
     * (missing fields, normalized values). A newer schemaVersion means the file
     * belongs to a later mod version, so a temporary downgrade must not strip it.
     */
    private void upgradeOnDisk(final String onDisk, final ConfluxConfig config) {
        if (config.schemaVersion > ConfluxConfig.SCHEMA_VERSION) {
            logger.warn(
                "Config {} has schema {} newer than this build's {}; leaving the file untouched",
                file, config.schemaVersion, ConfluxConfig.SCHEMA_VERSION
            );
            return;
        }
        config.schemaVersion = ConfluxConfig.SCHEMA_VERSION;
        if (!GSON.toJson(config).equals(onDisk)) {
            logger.info("Updating config {} to the current schema (missing fields added)", file);
            save(config);
        }
    }

    private void move(final Path tmp) throws IOException {
        try {
            Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine() {
        try {
            Files.move(file, file.resolveSibling(file.getFileName() + ".bad"), StandardCopyOption.REPLACE_EXISTING);
        } catch (final IOException e) {
            logger.warn("Could not quarantine {}", file, e);
        }
    }
}
