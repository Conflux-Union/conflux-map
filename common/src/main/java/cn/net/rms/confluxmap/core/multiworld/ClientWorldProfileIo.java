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
    private final Path blockedMarker;
    private final Logger logger;
    private int consecutiveSaveFailures;
    private long nextFailureLogAt;

    public ClientWorldProfileIo(final Path file, final Logger logger) {
        this.file = file;
        this.blockedMarker = file.resolveSibling(file.getFileName() + ".blocked");
        this.logger = logger;
    }

    public ClientWorldProfileRegistry load() {
        if (!Files.exists(file)) {
            if (Files.exists(blockedMarker)) {
                return ClientWorldProfileRegistry.unavailable(
                    "client world registry is quarantined; restore or remove the blocked marker explicitly"
                );
            }
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
            for (final ClientWorldProfileRegistry.CommandConflict conflict : registry.normalize()) {
                logger.warn(
                    "Discarded duplicate client-world command on server {} from profile {} (commandHash={})",
                    conflict.serverId(),
                    conflict.profileId(),
                    ClientWorldSignalHasher.hash(conflict.command()).substring(0, 12)
                );
            }
            for (final ClientWorldProfileRegistry.ProfileIssue issue : registry.invalidProfiles()) {
                logger.warn(
                    "Discarded invalid client-world profile on server {} (profileId={}, reason={})",
                    issue.serverId(), issue.profileId(), issue.reason()
                );
            }
            clearBlockedMarker();
            return registry;
        } catch (final IOException | JsonParseException | IllegalArgumentException e) {
            logger.warn("Client world registry {} unreadable ({}), quarantining", file, e.toString());
            quarantine(e.toString());
            return ClientWorldProfileRegistry.unavailable(
                "client world registry unavailable: " + e
            );
        }
    }

    public SaveResult save(final ClientWorldProfileRegistry registry) {
        Path temporary = null;
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(temporary, GSON.toJson(registry), StandardCharsets.UTF_8);
            move(temporary);
            clearBlockedMarker();
            consecutiveSaveFailures = 0;
            nextFailureLogAt = 0L;
            return SaveResult.success();
        } catch (final IOException e) {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (final IOException cleanupError) {
                    logger.warn("Could not remove temporary client world registry {}", temporary, cleanupError);
                }
            }
            final long now = System.currentTimeMillis();
            consecutiveSaveFailures++;
            if (now >= nextFailureLogAt) {
                logger.error("Failed to save client world registry (attempt {}): {}", consecutiveSaveFailures, e.toString());
                final long delay = Math.min(60_000L, 1_000L << Math.min(6, consecutiveSaveFailures - 1));
                nextFailureLogAt = now + delay;
            }
            return SaveResult.failure(e.toString());
        }
    }

    private void move(final Path temporary) throws IOException {
        try {
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void quarantine(final String reason) {
        try {
            if (blockedMarker.getParent() != null) {
                Files.createDirectories(blockedMarker.getParent());
            }
            Files.writeString(blockedMarker, reason, StandardCharsets.UTF_8);
            final Path backup = nextBackupFile();
            Files.move(file, backup);
        } catch (final IOException e) {
            logger.warn("Could not quarantine client world registry {}", file, e);
        }
    }

    private void clearBlockedMarker() {
        try {
            Files.deleteIfExists(blockedMarker);
        } catch (final IOException error) {
            logger.warn("Could not clear client world registry blocked marker {}", blockedMarker, error);
        }
    }

    private Path nextBackupFile() {
        final String prefix = file.getFileName() + ".bad." + System.currentTimeMillis();
        Path candidate = file.resolveSibling(prefix);
        int suffix = 1;
        while (Files.exists(candidate)) {
            candidate = file.resolveSibling(prefix + "." + suffix++);
        }
        return candidate;
    }

    /** The result returned to profile mutations so UI actions never report an unsaved change as successful. */
    public record SaveResult(boolean saved, String error) {
        public static SaveResult success() {
            return new SaveResult(true, null);
        }

        public static SaveResult failure(final String error) {
            return new SaveResult(false, error);
        }
    }
}
