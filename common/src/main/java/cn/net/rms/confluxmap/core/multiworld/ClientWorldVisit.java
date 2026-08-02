package cn.net.rms.confluxmap.core.multiworld;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Per-dimension evidence retained after a confirmed visit to a logical profile. */
public final class ClientWorldVisit {
    private String dimensionId;
    private String gameMode;
    private ClientWorldPosition lastPosition;
    private long lastVisitedAtEpochMs;
    private ClientWorldTerrainFingerprint terrainFingerprint;
    private Map<String, String> contextSignals;

    ClientWorldVisit() {
        // Gson
    }

    ClientWorldVisit(
        final String dimensionId,
        final String gameMode,
        final ClientWorldPosition lastPosition,
        final long lastVisitedAtEpochMs,
        final ClientWorldTerrainFingerprint terrainFingerprint,
        final Map<String, String> contextSignals
    ) {
        this.dimensionId = requireText(dimensionId, "dimensionId");
        this.gameMode = gameMode;
        this.lastPosition = lastPosition;
        this.lastVisitedAtEpochMs = lastVisitedAtEpochMs;
        this.terrainFingerprint = terrainFingerprint;
        this.contextSignals = normalizeContextSignals(contextSignals);
    }

    public String dimensionId() {
        return dimensionId;
    }

    public String gameMode() {
        return gameMode;
    }

    public ClientWorldPosition lastPosition() {
        return lastPosition;
    }

    public long lastVisitedAtEpochMs() {
        return lastVisitedAtEpochMs;
    }

    public ClientWorldTerrainFingerprint terrainFingerprint() {
        return terrainFingerprint;
    }

    /** Hashed, dimension-scoped signals used as low-cost matching evidence. */
    public Map<String, String> contextSignals() {
        return Map.copyOf(contextSignals == null ? Map.of() : contextSignals);
    }

    void normalize() {
        dimensionId = requireText(dimensionId, "dimensionId");
        gameMode = gameMode == null || gameMode.isBlank() ? null : gameMode;
        if (lastVisitedAtEpochMs < 0L) {
            lastVisitedAtEpochMs = 0L;
        }
        if (terrainFingerprint != null) {
            terrainFingerprint.normalize();
        }
        contextSignals = normalizeContextSignals(contextSignals);
    }

    ClientWorldVisit copy() {
        return new ClientWorldVisit(
            dimensionId, gameMode, lastPosition, lastVisitedAtEpochMs,
            terrainFingerprint == null ? null : terrainFingerprint.copy(), contextSignals
        );
    }

    static Map<String, String> mergeContextSignals(
        final Map<String, String> previous,
        final Map<String, String> observed
    ) {
        final Map<String, String> merged = new LinkedHashMap<>(normalizeContextSignals(previous));
        for (final Map.Entry<String, String> entry : normalizeContextSignals(observed).entrySet()) {
            merged.put(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    ContextMatch contextMatch(final Map<String, String> observed) {
        int matches = 0;
        int shared = 0;
        int stableConflicts = 0;
        for (final Map.Entry<String, String> entry : contextSignals().entrySet()) {
            final String current = observed.get(entry.getKey());
            if (current == null || current.isBlank()) {
                continue;
            }
            shared++;
            if (entry.getValue().equals(current)) {
                matches++;
            } else if (isStableContextKey(entry.getKey())) {
                stableConflicts++;
            }
        }
        return new ContextMatch(matches, shared, stableConflicts);
    }

    record ContextMatch(int matches, int shared, int stableConflicts) {
        double score() {
            return shared == 0 ? 0.0D : (double) matches / shared;
        }

        boolean hasStableConflict() {
            return stableConflicts > 0;
        }
    }

    private static Map<String, String> normalizeContextSignals(final Map<String, String> source) {
        final Map<String, String> normalized = new LinkedHashMap<>();
        if (source == null) {
            return normalized;
        }
        for (final Map.Entry<String, String> entry : source.entrySet()) {
            if (isContextKey(entry.getKey()) && entry.getValue() != null && !entry.getValue().isBlank()) {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        return normalized;
    }

    private static boolean isContextKey(final String key) {
        return "dimension_type".equals(key)
            || "world_shape".equals(key)
            || "spawn".equals(key)
            || "world_border".equals(key)
            || "difficulty".equals(key);
    }

    private static boolean isStableContextKey(final String key) {
        return "dimension_type".equals(key) || "world_shape".equals(key);
    }

    private static String requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Objects.requireNonNull(value, field);
    }
}
