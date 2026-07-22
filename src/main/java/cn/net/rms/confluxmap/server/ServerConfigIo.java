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
import org.apache.logging.log4j.Logger;

/**
 * Atomic JSON loader/saver for {@link ServerConfig}. Pattern mirrors {@code core.config.ConfigIo}:
 * tmp-file + atomic move, quarantine a corrupt file as {@code *.bad}, never wedge the mod on a
 * broken edit, and load-time upgrade of the on-disk document (missing fields persisted with
 * defaults; a newer-schema file is left intact). Lives in {@code server/} (MC-aware side) because
 * it runs on the dedicated server where the {@code core.config.ConfigIo} (client-only) is not
 * loaded.
 */
public final class ServerConfigIo {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Path file;
    private final Logger logger;

    public ServerConfigIo(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    public ServerConfig load() {
        if (!Files.exists(file)) {
            final ServerConfig fresh = new ServerConfig();
            save(fresh);
            return fresh;
        }
        try {
            final String json = Files.readString(file, StandardCharsets.UTF_8);
            final ServerConfig config = GSON.fromJson(json, ServerConfig.class);
            if (config == null) {
                throw new JsonParseException("empty server config");
            }
            config.normalize();
            upgradeOnDisk(json, config);
            return config;
        } catch (final IOException | JsonParseException e) {
            logger.warn("Server config {} unreadable ({}), quarantining and using defaults", file, e.toString());
            quarantine();
            final ServerConfig fresh = new ServerConfig();
            save(fresh);
            return fresh;
        }
    }

    /** Preserves the original fire-and-log API for existing callers. */
    public void save(final ServerConfig config) {
        saveAtomically(config);
    }

    /** Atomically saves one complete config document and reports whether it reached the target. */
    public boolean saveAtomically(final ServerConfig config) {
        try {
            Files.createDirectories(file.getParent());
            final Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(config), StandardCharsets.UTF_8);
            move(tmp);
            return true;
        } catch (final IOException | RuntimeException e) {
            logger.error("Failed to save server config to {}", file, e);
            return false;
        }
    }

    public static ServerConfig loadDefault() {
        return new ServerConfig();
    }

    /** Convenience factory bound to the mod's standard config location. */
    public static ServerConfigIo atDefault(final Path configDir) {
        return new ServerConfigIo(
            configDir.resolve(ConfluxMapMod.ID).resolve("server.json"),
            ConfluxMapMod.LOGGER
        );
    }

    /**
     * Rewrites the file when its content no longer matches the current schema
     * (missing fields, normalized values). A newer schemaVersion means the file
     * belongs to a later mod version, so a temporary downgrade must not strip it.
     */
    private void upgradeOnDisk(final String onDisk, final ServerConfig config) {
        if (config.schemaVersion > ServerConfig.SCHEMA_VERSION) {
            logger.warn(
                "Server config {} has schema {} newer than this build's {}; leaving the file untouched",
                file, config.schemaVersion, ServerConfig.SCHEMA_VERSION
            );
            return;
        }
        config.schemaVersion = ServerConfig.SCHEMA_VERSION;
        if (!GSON.toJson(config).equals(onDisk)) {
            logger.info("Updating server config {} to the current schema (missing fields added)", file);
            saveAtomically(config);
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
