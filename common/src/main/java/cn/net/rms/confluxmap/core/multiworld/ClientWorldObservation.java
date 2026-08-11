package cn.net.rms.confluxmap.core.multiworld;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/** Stable, privacy-safe evidence observed for one upstream server visit. */
public record ClientWorldObservation(
    OptionalLong seedHash,
    Map<String, String> signals,
    String dimensionId,
    String gameMode,
    ClientWorldPosition position,
    ClientWorldTerrainFingerprint terrainFingerprint,
    Map<String, ClientWorldTerrainFingerprint> terrainFingerprintsByProfileId,
    ClientWorldTrajectory trajectory,
    Map<String, ClientWorldTrajectory> candidateTrajectoriesByProfileId
) {
    public static final int MAX_SIGNAL_ENTRIES = 64;
    public static final int MAX_SIGNAL_KEY_LENGTH = 64;
    public static final int MAX_SIGNAL_VALUE_LENGTH = 256;
    public static final int MAX_DIMENSION_LENGTH = 128;
    public static final int MAX_GAME_MODE_LENGTH = 64;
    public static final int MAX_PROFILE_EVIDENCE_ENTRIES = 128;

    public ClientWorldObservation(final OptionalLong seedHash, final Map<String, String> signals) {
        this(seedHash, signals, null, null, null, null, Map.of(), null, Map.of());
    }

    public ClientWorldObservation(
        final OptionalLong seedHash,
        final Map<String, String> signals,
        final String dimensionId,
        final String gameMode,
        final ClientWorldPosition position,
        final ClientWorldTerrainFingerprint terrainFingerprint
    ) {
        this(seedHash, signals, dimensionId, gameMode, position, terrainFingerprint, Map.of(), null, Map.of());
    }

    public ClientWorldObservation(
        final OptionalLong seedHash,
        final Map<String, String> signals,
        final String dimensionId,
        final String gameMode,
        final ClientWorldPosition position,
        final ClientWorldTerrainFingerprint terrainFingerprint,
        final Map<String, ClientWorldTerrainFingerprint> terrainFingerprintsByProfileId
    ) {
        this(seedHash, signals, dimensionId, gameMode, position, terrainFingerprint,
            terrainFingerprintsByProfileId, null, Map.of());
    }

    public ClientWorldObservation(
        final OptionalLong seedHash,
        final Map<String, String> signals,
        final String dimensionId,
        final String gameMode,
        final ClientWorldPosition position,
        final ClientWorldTerrainFingerprint terrainFingerprint,
        final Map<String, ClientWorldTerrainFingerprint> terrainFingerprintsByProfileId,
        final ClientWorldTrajectory trajectory
    ) {
        this(seedHash, signals, dimensionId, gameMode, position, terrainFingerprint,
            terrainFingerprintsByProfileId, trajectory, Map.of());
    }

    public ClientWorldObservation {
        seedHash = Objects.requireNonNull(seedHash, "seedHash");
        final Map<String, String> normalizedSignals = new LinkedHashMap<>();
        final Map<String, String> suppliedSignals = Objects.requireNonNull(signals, "signals");
        if (suppliedSignals.size() > MAX_SIGNAL_ENTRIES) {
            throw new IllegalArgumentException("signals exceed " + MAX_SIGNAL_ENTRIES + " entries");
        }
        for (final Map.Entry<String, String> entry : suppliedSignals.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                && entry.getValue() != null && !entry.getValue().isBlank()) {
                if (entry.getKey().length() > MAX_SIGNAL_KEY_LENGTH
                    || entry.getValue().length() > MAX_SIGNAL_VALUE_LENGTH) {
                    throw new IllegalArgumentException("signal key or value exceeds configured length");
                }
                normalizedSignals.put(entry.getKey(), entry.getValue());
            }
        }
        signals = Map.copyOf(normalizedSignals);
        dimensionId = normalizeText(dimensionId);
        gameMode = normalizeText(gameMode);
        if (dimensionId != null && dimensionId.length() > MAX_DIMENSION_LENGTH) {
            throw new IllegalArgumentException("dimensionId exceeds " + MAX_DIMENSION_LENGTH + " characters");
        }
        if (gameMode != null && gameMode.length() > MAX_GAME_MODE_LENGTH) {
            throw new IllegalArgumentException("gameMode exceeds " + MAX_GAME_MODE_LENGTH + " characters");
        }
        final Map<String, ClientWorldTerrainFingerprint> normalizedTerrain = new LinkedHashMap<>();
        final Map<String, ClientWorldTerrainFingerprint> suppliedTerrain = Objects.requireNonNull(
            terrainFingerprintsByProfileId, "terrainFingerprintsByProfileId"
        );
        if (suppliedTerrain.size() > MAX_PROFILE_EVIDENCE_ENTRIES) {
            throw new IllegalArgumentException(
                "terrain profile evidence exceeds " + MAX_PROFILE_EVIDENCE_ENTRIES + " entries"
            );
        }
        for (final Map.Entry<String, ClientWorldTerrainFingerprint> entry : suppliedTerrain.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                normalizedTerrain.put(entry.getKey(), entry.getValue());
            }
        }
        terrainFingerprintsByProfileId = Map.copyOf(normalizedTerrain);
        final Map<String, ClientWorldTrajectory> normalizedTrajectories = new LinkedHashMap<>();
        final Map<String, ClientWorldTrajectory> suppliedTrajectories = Objects.requireNonNull(
            candidateTrajectoriesByProfileId, "candidateTrajectoriesByProfileId"
        );
        if (suppliedTrajectories.size() > MAX_PROFILE_EVIDENCE_ENTRIES) {
            throw new IllegalArgumentException(
                "trajectory profile evidence exceeds " + MAX_PROFILE_EVIDENCE_ENTRIES + " entries"
            );
        }
        for (final Map.Entry<String, ClientWorldTrajectory> entry : suppliedTrajectories.entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null
                && entry.getValue().latest() != null) {
                normalizedTrajectories.put(entry.getKey(), entry.getValue().copy());
            }
        }
        candidateTrajectoriesByProfileId = Map.copyOf(normalizedTrajectories);
    }

    public ClientWorldTerrainFingerprint terrainFingerprintFor(final String profileId) {
        final ClientWorldTerrainFingerprint candidate = terrainFingerprintsByProfileId.get(profileId);
        return candidate == null ? terrainFingerprint : candidate;
    }

    public ClientWorldTrajectory candidateTrajectoryFor(final String profileId) {
        final ClientWorldTrajectory candidate = candidateTrajectoriesByProfileId.get(profileId);
        return candidate == null ? null : candidate.copy();
    }

    private static String normalizeText(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
