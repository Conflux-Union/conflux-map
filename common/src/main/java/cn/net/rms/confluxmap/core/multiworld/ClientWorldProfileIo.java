package cn.net.rms.confluxmap.core.multiworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import org.apache.logging.log4j.Logger;

/** Atomic persistence for the client-owned world profile registry. */
public final class ClientWorldProfileIo {
    private static final long MAX_REGISTRY_BYTES = 4L * 1024L * 1024L;
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(
            ClientWorldPosition.class,
            (JsonDeserializer<ClientWorldPosition>) ClientWorldProfileIo::deserializePosition
        )
        .registerTypeAdapter(
            ClientWorldProfileRegistry.LastStableProfile.class,
            (JsonDeserializer<ClientWorldProfileRegistry.LastStableProfile>)
                ClientWorldProfileIo::deserializeLastStableProfile
        )
        .registerTypeAdapter(
            ClientWorldTerrainAnchor.class,
            (JsonDeserializer<ClientWorldTerrainAnchor>) ClientWorldProfileIo::deserializeTerrainAnchor
        )
        .registerTypeAdapter(
            ClientWorldTrajectorySample.class,
            (JsonDeserializer<ClientWorldTrajectorySample>) ClientWorldProfileIo::deserializeTrajectorySample
        )
        .setPrettyPrinting()
        .create();

    private final Path file;
    private final Path blockedMarker;
    private final Logger logger;
    private final FileMover fileMover;
    private int consecutiveSaveFailures;
    private long nextFailureLogAt;

    public ClientWorldProfileIo(final Path file, final Logger logger) {
        this(file, logger, (source, target, atomic) -> {
            if (atomic) {
                Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
            }
        });
    }

    ClientWorldProfileIo(final Path file, final Logger logger, final FileMover fileMover) {
        this.file = file;
        this.blockedMarker = file.resolveSibling(file.getFileName() + ".blocked");
        this.logger = logger;
        this.fileMover = fileMover;
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
            if (Files.size(file) > MAX_REGISTRY_BYTES) {
                throw new IOException("client world registry exceeds " + MAX_REGISTRY_BYTES + " bytes");
            }
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
            final byte[] serialized = GSON.toJson(registry).getBytes(StandardCharsets.UTF_8);
            if (serialized.length > MAX_REGISTRY_BYTES) {
                return SaveResult.failure(
                    "client world registry exceeds " + MAX_REGISTRY_BYTES + " bytes"
                );
            }
            Files.write(temporary, serialized);
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
            fileMover.move(temporary, file, true);
        } catch (final AtomicMoveNotSupportedException e) {
            fileMover.move(temporary, file, false);
        }
    }

    @FunctionalInterface
    interface FileMover {
        void move(Path source, Path target, boolean atomic) throws IOException;
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

    /** Gson versions bundled with older Minecraft lines cannot reflectively populate Java records on Java 21. */
    private static ClientWorldPosition deserializePosition(
        final JsonElement json,
        final Type ignored,
        final JsonDeserializationContext context
    ) {
        try {
            final JsonObject object = json.getAsJsonObject();
            return new ClientWorldPosition(
                object.get("x").getAsInt(), object.get("y").getAsInt(), object.get("z").getAsInt()
            );
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    /** The persisted stable pointer is a record too, so it needs constructor-based parsing on Java 21. */
    private static ClientWorldProfileRegistry.LastStableProfile deserializeLastStableProfile(
        final JsonElement json,
        final Type ignored,
        final JsonDeserializationContext context
    ) {
        try {
            final JsonObject object = json.getAsJsonObject();
            final Map<String, String> stableSignals = object.has("stableSignals")
                ? context.deserialize(object.get("stableSignals"), Map.class) : Map.of();
            return new ClientWorldProfileRegistry.LastStableProfile(
                object.get("profileId").getAsString(),
                object.get("confirmedAtEpochMs").getAsLong(),
                object.get("connectionGeneration").getAsLong(),
                object.has("hasSeed") && object.get("hasSeed").getAsBoolean(),
                object.has("seedHash") ? object.get("seedHash").getAsLong() : 0L,
                stableSignals == null ? Map.of() : stableSignals
            );
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    /** Optional evidence must fail closed per entry, not quarantine an otherwise valid registry. */
    private static ClientWorldTerrainAnchor deserializeTerrainAnchor(
        final JsonElement json,
        final Type ignored,
        final JsonDeserializationContext context
    ) {
        try {
            final JsonObject object = json.getAsJsonObject();
            return new ClientWorldTerrainAnchor(
                context.deserialize(object.get("position"), ClientWorldPosition.class),
                context.deserialize(object.get("fingerprint"), ClientWorldTerrainFingerprint.class),
                object.get("capturedAtEpochMs").getAsLong()
            );
        } catch (final RuntimeException malformed) {
            return null;
        }
    }

    private static ClientWorldTrajectorySample deserializeTrajectorySample(
        final JsonElement json,
        final Type ignored,
        final JsonDeserializationContext context
    ) {
        try {
            final JsonObject object = json.getAsJsonObject();
            return new ClientWorldTrajectorySample(
                object.get("x").getAsDouble(),
                object.get("y").getAsDouble(),
                object.get("z").getAsDouble(),
                object.get("horizontalVelocityX").getAsDouble(),
                object.get("horizontalVelocityZ").getAsDouble(),
                object.get("yawDegrees").getAsDouble(),
                object.get("pitchDegrees").getAsDouble(),
                object.get("clientTimeMs").getAsLong(),
                object.get("clientTick").getAsLong(),
                object.get("dimensionId").getAsString(),
                object.get("sequence").getAsLong(),
                object.get("serverAckTimeMs").getAsLong(),
                object.get("connectionGeneration").getAsLong(),
                context.deserialize(object.get("evidenceSource"), ClientWorldTrajectorySample.EvidenceSource.class)
            );
        } catch (final RuntimeException malformed) {
            return null;
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
