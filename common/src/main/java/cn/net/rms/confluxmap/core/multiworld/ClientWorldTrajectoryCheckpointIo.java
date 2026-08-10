package cn.net.rms.confluxmap.core.multiworld;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.OptionalLong;
import org.apache.logging.log4j.Logger;

/**
 * Atomic, profile-independent persistence for the newest local movement history.
 *
 * <p>This store deliberately does not contain a profile id or map data. It is therefore safe to
 * keep while a reconnect is provisional: the history can help the next resolver pass, but it
 * cannot publish evidence into a stable profile by itself.</p>
 */
public final class ClientWorldTrajectoryCheckpointIo {
    public static final int SCHEMA_VERSION = 1;
    private static final int MAX_SAMPLES = ClientWorldTrajectory.DEFAULT_CAPACITY;
    private static final Gson GSON = new GsonBuilder()
        .registerTypeAdapter(
            ClientWorldTrajectorySample.class,
            (com.google.gson.JsonDeserializer<ClientWorldTrajectorySample>)
                ClientWorldTrajectoryCheckpointIo::deserializeTrajectorySample
        )
        .setPrettyPrinting()
        .create();

    private final Path root;
    private final Logger logger;

    public ClientWorldTrajectoryCheckpointIo(final Path root, final Logger logger) {
        this.root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /** Loads one server's checkpoint, returning {@code null} for absent or malformed evidence. */
    public Checkpoint load(final String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return null;
        }
        final Path file = file(serverId);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            final JsonCheckpoint raw = GSON.fromJson(
                Files.readString(file, StandardCharsets.UTF_8), JsonCheckpoint.class
            );
            if (raw == null || raw.schemaVersion > SCHEMA_VERSION
                || !ClientWorldSignalHasher.hash(serverId).equals(raw.serverKey)) {
                return null;
            }
            final List<ClientWorldTrajectorySample> valid = new ArrayList<>();
            if (raw.samples != null) {
                for (final ClientWorldTrajectorySample sample : raw.samples) {
                    if (sample != null && validTrajectorySample(sample)) {
                        valid.add(sample);
                    }
                }
            }
            if (valid.size() > MAX_SAMPLES) {
                valid.subList(0, valid.size() - MAX_SAMPLES).clear();
            }
            return new Checkpoint(
                SCHEMA_VERSION,
                raw.hasSeed,
                raw.seedHash,
                raw.dimensionId == null || raw.dimensionId.isBlank() ? null : raw.dimensionId,
                Math.max(0L, raw.connectionGeneration),
                Math.max(0L, raw.savedAtEpochMs),
                List.copyOf(valid)
            );
        } catch (final IOException | JsonParseException | IllegalArgumentException error) {
            logger.warn("Client world trajectory checkpoint {} unreadable; ignoring it", file, error);
            return null;
        }
    }

    /** Saves only movement evidence using a temporary file and an atomic replacement. */
    public SaveResult save(
        final String serverId,
        final OptionalLong seedHash,
        final ClientWorldTrajectory trajectory
    ) {
        if (serverId == null || serverId.isBlank() || trajectory == null || trajectory.samples().isEmpty()) {
            return SaveResult.success();
        }
        final List<ClientWorldTrajectorySample> samples = trajectory.samples();
        final ClientWorldTrajectorySample latest = trajectory.latest();
        final JsonCheckpoint checkpoint = new JsonCheckpoint();
        checkpoint.schemaVersion = SCHEMA_VERSION;
        checkpoint.serverKey = ClientWorldSignalHasher.hash(serverId);
        checkpoint.hasSeed = seedHash != null && seedHash.isPresent();
        checkpoint.seedHash = checkpoint.hasSeed ? seedHash.getAsLong() : 0L;
        checkpoint.dimensionId = latest.dimensionId();
        checkpoint.connectionGeneration = latest.connectionGeneration();
        checkpoint.savedAtEpochMs = System.currentTimeMillis();
        checkpoint.samples = samples.size() <= MAX_SAMPLES
            ? new ArrayList<>(samples)
            : new ArrayList<>(samples.subList(samples.size() - MAX_SAMPLES, samples.size()));
        final Path file = file(serverId);
        final Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(root);
            Files.writeString(temporary, GSON.toJson(checkpoint), StandardCharsets.UTF_8);
            move(temporary, file);
            return SaveResult.success();
        } catch (final IOException error) {
            try {
                Files.deleteIfExists(temporary);
            } catch (final IOException cleanupError) {
                logger.warn("Could not remove temporary trajectory checkpoint {}", temporary, cleanupError);
            }
            logger.warn("Failed to save client world trajectory checkpoint {}", file, error);
            return SaveResult.failure(error.toString());
        }
    }

    /** Removes a checkpoint after its history has been committed to a confirmed profile. */
    public SaveResult clear(final String serverId) {
        if (serverId == null || serverId.isBlank()) {
            return SaveResult.success();
        }
        try {
            Files.deleteIfExists(file(serverId));
            Files.deleteIfExists(file(serverId).resolveSibling(file(serverId).getFileName() + ".tmp"));
            return SaveResult.success();
        } catch (final IOException error) {
            logger.warn("Failed to clear client world trajectory checkpoint for {}", serverId, error);
            return SaveResult.failure(error.toString());
        }
    }

    private Path file(final String serverId) {
        final String key = ClientWorldSignalHasher.hash(serverId);
        return root.resolve(key + ".json").normalize();
    }

    private static void move(final Path temporary, final Path target) throws IOException {
        try {
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException error) {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
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

    private static boolean validTrajectorySample(final ClientWorldTrajectorySample sample) {
        return sample.dimensionId() != null && !sample.dimensionId().isBlank()
            && sample.clientTimeMs() >= 0L && sample.clientTick() >= 0L
            && sample.sequence() >= 0L
            && sample.serverAckTimeMs() >= ClientWorldTrajectorySample.NO_SERVER_ACK
            && sample.connectionGeneration() >= 0L;
    }

    public record Checkpoint(
        int schemaVersion,
        boolean hasSeed,
        long seedHash,
        String dimensionId,
        long connectionGeneration,
        long savedAtEpochMs,
        List<ClientWorldTrajectorySample> samples
    ) {
        public Checkpoint {
            samples = List.copyOf(samples == null ? List.of() : samples);
        }

        public ClientWorldTrajectory trajectory() {
            return ClientWorldTrajectory.fromHistoricalSamples(samples, MAX_SAMPLES);
        }
    }

    public record SaveResult(boolean saved, String error) {
        public static SaveResult success() {
            return new SaveResult(true, null);
        }

        public static SaveResult failure(final String error) {
            return new SaveResult(false, error);
        }
    }

    private static final class JsonCheckpoint {
        private int schemaVersion;
        private String serverKey;
        private boolean hasSeed;
        private long seedHash;
        private String dimensionId;
        private long connectionGeneration;
        private long savedAtEpochMs;
        private List<ClientWorldTrajectorySample> samples;
    }
}
