package cn.net.rms.confluxmap.paper;

import cn.net.rms.confluxmap.server.ServerConfig;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.slf4j.Logger;

/** Atomic JSON persistence shared with the Fabric companion's server.json schema. */
final class PaperServerConfigIo {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int LEGACY_MAX_PENDING_TILES = 16;
    private static final int LEGACY_MAX_BYTES_PER_SECOND = 65_536;
    private static final int LEGACY_MIN_REQUEST_INTERVAL_MS = 300;

    private final Path file;
    private final Logger logger;

    PaperServerConfigIo(final Path file, final Logger logger) {
        this.file = file;
        this.logger = logger;
    }

    ServerConfig load() {
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
            migrateLegacyDefaults(config);
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

    boolean save(final ServerConfig config) {
        try {
            Files.createDirectories(file.getParent());
            final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(config), StandardCharsets.UTF_8);
            move(temporary, file);
            return true;
        } catch (final IOException | RuntimeException e) {
            logger.error("Failed to save server config {}", file, e);
            return false;
        }
    }

    private void upgradeOnDisk(final String json, final ServerConfig config) {
        if (config.schemaVersion > ServerConfig.SCHEMA_VERSION) {
            logger.warn(
                "Server config {} has schema {} newer than supported {}; leaving it untouched",
                file, config.schemaVersion, ServerConfig.SCHEMA_VERSION
            );
            return;
        }
        config.schemaVersion = ServerConfig.SCHEMA_VERSION;
        if (!GSON.toJson(config).equals(json)) {
            save(config);
        }
    }

    private static void migrateLegacyDefaults(final ServerConfig config) {
        if (config.schemaVersion >= 3) {
            return;
        }
        final ServerConfig defaults = new ServerConfig();
        if (config.maxPendingTilesPerPlayer == LEGACY_MAX_PENDING_TILES) {
            config.maxPendingTilesPerPlayer = defaults.maxPendingTilesPerPlayer;
        }
        if (config.maxBytesPerSecondPerPlayer == LEGACY_MAX_BYTES_PER_SECOND) {
            config.maxBytesPerSecondPerPlayer = defaults.maxBytesPerSecondPerPlayer;
        }
        if (config.minRequestIntervalMs == LEGACY_MIN_REQUEST_INTERVAL_MS) {
            config.minRequestIntervalMs = defaults.minRequestIntervalMs;
        }
    }

    private void quarantine() {
        try {
            move(file, file.resolveSibling(file.getFileName() + ".bad"));
        } catch (final IOException e) {
            logger.warn("Could not quarantine {}", file, e);
        }
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
