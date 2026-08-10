package cn.net.rms.confluxmap.core.multiworld;

import java.util.Objects;

/** One locally observed movement sample. Client observations are not server truth. */
public record ClientWorldTrajectorySample(
    double x,
    double y,
    double z,
    double horizontalVelocityX,
    double horizontalVelocityZ,
    double yawDegrees,
    double pitchDegrees,
    long clientTimeMs,
    long clientTick,
    String dimensionId,
    long sequence,
    long serverAckTimeMs,
    long connectionGeneration,
    EvidenceSource evidenceSource
) {
    public static final long NO_SERVER_ACK = -1L;

    public ClientWorldTrajectorySample {
        requireFinite(x, "x");
        requireFinite(y, "y");
        requireFinite(z, "z");
        requireFinite(horizontalVelocityX, "horizontalVelocityX");
        requireFinite(horizontalVelocityZ, "horizontalVelocityZ");
        requireFinite(yawDegrees, "yawDegrees");
        requireFinite(pitchDegrees, "pitchDegrees");
        if (clientTimeMs < 0L) {
            throw new IllegalArgumentException("clientTimeMs must not be negative");
        }
        if (clientTick < 0L) {
            throw new IllegalArgumentException("clientTick must not be negative");
        }
        if (sequence < 0L) {
            throw new IllegalArgumentException("sequence must not be negative");
        }
        if (serverAckTimeMs < NO_SERVER_ACK) {
            throw new IllegalArgumentException("serverAckTimeMs is invalid");
        }
        if (connectionGeneration < 0L) {
            throw new IllegalArgumentException("connectionGeneration must not be negative");
        }
        dimensionId = Objects.requireNonNull(dimensionId, "dimensionId");
        if (dimensionId.isBlank()) {
            throw new IllegalArgumentException("dimensionId must not be blank");
        }
        evidenceSource = Objects.requireNonNull(evidenceSource, "evidenceSource");
    }

    public static ClientWorldTrajectorySample observed(
        final double x,
        final double y,
        final double z,
        final double velocityX,
        final double velocityZ,
        final double yawDegrees,
        final double pitchDegrees,
        final long clientTimeMs,
        final long clientTick,
        final String dimensionId,
        final long sequence,
        final long serverAckTimeMs
    ) {
        return observed(
            x, y, z, velocityX, velocityZ, yawDegrees, pitchDegrees, clientTimeMs,
            clientTick, dimensionId, sequence, serverAckTimeMs, 0L
        );
    }

    public static ClientWorldTrajectorySample confirmed(
        final double x,
        final double y,
        final double z,
        final double velocityX,
        final double velocityZ,
        final double yawDegrees,
        final double pitchDegrees,
        final long clientTimeMs,
        final long clientTick,
        final String dimensionId,
        final long sequence,
        final long connectionGeneration
    ) {
        return new ClientWorldTrajectorySample(
            x, y, z, velocityX, velocityZ, yawDegrees, pitchDegrees,
            clientTimeMs, clientTick, dimensionId, sequence, clientTimeMs, connectionGeneration,
            EvidenceSource.SERVER_CONFIRMED
        );
    }

    public static ClientWorldTrajectorySample observed(
        final double x,
        final double y,
        final double z,
        final double velocityX,
        final double velocityZ,
        final double yawDegrees,
        final double pitchDegrees,
        final long clientTimeMs,
        final long clientTick,
        final String dimensionId,
        final long sequence,
        final long serverAckTimeMs,
        final long connectionGeneration
    ) {
        return new ClientWorldTrajectorySample(
            x, y, z, velocityX, velocityZ, yawDegrees, pitchDegrees,
            clientTimeMs, clientTick, dimensionId, sequence, serverAckTimeMs, connectionGeneration,
            EvidenceSource.CLIENT_OBSERVED
        );
    }

    /** Compatibility constructor for callers created before connection generations were persisted. */
    public ClientWorldTrajectorySample(
        final double x,
        final double y,
        final double z,
        final double horizontalVelocityX,
        final double horizontalVelocityZ,
        final double yawDegrees,
        final double pitchDegrees,
        final long clientTimeMs,
        final long clientTick,
        final String dimensionId,
        final long sequence,
        final long serverAckTimeMs,
        final EvidenceSource evidenceSource
    ) {
        this(x, y, z, horizontalVelocityX, horizontalVelocityZ, yawDegrees, pitchDegrees,
            clientTimeMs, clientTick, dimensionId, sequence, serverAckTimeMs, 0L, evidenceSource);
    }

    public double horizontalSpeed() {
        return Math.hypot(horizontalVelocityX, horizontalVelocityZ);
    }

    public double horizontalDistanceTo(final ClientWorldPosition position) {
        Objects.requireNonNull(position, "position");
        return Math.hypot(x - position.x(), z - position.z());
    }

    public double spatialDistanceTo(final ClientWorldPosition position) {
        Objects.requireNonNull(position, "position");
        return Math.sqrt(
            square(x - position.x()) + square(y - position.y()) + square(z - position.z())
        );
    }

    public enum EvidenceSource {
        CLIENT_OBSERVED,
        SERVER_CONFIRMED
    }

    private static void requireFinite(final double value, final String field) {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException(field + " must be finite");
        }
    }

    private static double square(final double value) {
        return value * value;
    }
}
