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
        for (final Map.Entry<String, String> entry : Objects.requireNonNull(signals, "signals").entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                && entry.getValue() != null && !entry.getValue().isBlank()) {
                normalizedSignals.put(entry.getKey(), entry.getValue());
            }
        }
        signals = Map.copyOf(normalizedSignals);
        dimensionId = normalizeText(dimensionId);
        gameMode = normalizeText(gameMode);
        final Map<String, ClientWorldTerrainFingerprint> normalizedTerrain = new LinkedHashMap<>();
        for (final Map.Entry<String, ClientWorldTerrainFingerprint> entry : Objects.requireNonNull(
            terrainFingerprintsByProfileId, "terrainFingerprintsByProfileId"
        ).entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank() && entry.getValue() != null) {
                normalizedTerrain.put(entry.getKey(), entry.getValue());
            }
        }
        terrainFingerprintsByProfileId = Map.copyOf(normalizedTerrain);
        final Map<String, ClientWorldTrajectory> normalizedTrajectories = new LinkedHashMap<>();
        for (final Map.Entry<String, ClientWorldTrajectory> entry : Objects.requireNonNull(
            candidateTrajectoriesByProfileId, "candidateTrajectoriesByProfileId"
        ).entrySet()) {
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
