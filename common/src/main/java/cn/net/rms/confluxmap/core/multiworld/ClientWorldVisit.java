package cn.net.rms.confluxmap.core.multiworld;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Per-dimension evidence retained after a confirmed visit to a logical profile. */
public final class ClientWorldVisit {
    private String dimensionId;
    private String gameMode;
    private ClientWorldPosition lastPosition;
    private long lastVisitedAtEpochMs;
    private ClientWorldTerrainFingerprint terrainFingerprint;
    private List<ClientWorldTerrainAnchor> terrainAnchors;
    private List<ClientWorldTrajectorySample> trajectorySamples;
    private long lastServerAckTimeMs;
    private long connectionGeneration;
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
        this.terrainAnchors = terrainFingerprint == null ? new ArrayList<>() : new ArrayList<>();
        this.trajectorySamples = new ArrayList<>();
        this.lastServerAckTimeMs = ClientWorldTrajectorySample.NO_SERVER_ACK;
        this.connectionGeneration = 0L;
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
        if ((terrainFingerprint == null) && terrainAnchors != null && !terrainAnchors.isEmpty()) {
            terrainFingerprint = terrainAnchors.get(terrainAnchors.size() - 1).fingerprint();
        }
        return terrainFingerprint;
    }

    public List<ClientWorldTerrainAnchor> terrainAnchors() {
        return List.copyOf(terrainAnchors == null ? List.of() : terrainAnchors);
    }

    public List<ClientWorldTrajectorySample> trajectorySamples() {
        return List.copyOf(trajectorySamples == null ? List.of() : trajectorySamples);
    }

    public long lastServerAckTimeMs() {
        return lastServerAckTimeMs;
    }

    public long connectionGeneration() {
        return connectionGeneration;
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
        if (terrainAnchors == null) {
            terrainAnchors = new ArrayList<>();
        }
        terrainAnchors.removeIf(anchor -> !validTerrainAnchor(anchor));
        if (terrainFingerprint != null && terrainAnchors.isEmpty()
            && terrainFingerprint.complete() && terrainFingerprint.hasCenter()) {
            // Legacy files had one fingerprint but no explicit anchor metadata.
            terrainAnchors.add(new ClientWorldTerrainAnchor(
                new ClientWorldPosition(terrainFingerprint.centerChunkX() << 4, 0,
                    terrainFingerprint.centerChunkZ() << 4), terrainFingerprint,
                Math.max(0L, lastVisitedAtEpochMs)
            ));
        }
        if (terrainAnchors.size() > ClientWorldProfile.MAX_TERRAIN_ANCHORS) {
            terrainAnchors = new ArrayList<>(terrainAnchors.subList(
                terrainAnchors.size() - ClientWorldProfile.MAX_TERRAIN_ANCHORS, terrainAnchors.size()
            ));
        }
        terrainFingerprint = terrainAnchors.isEmpty()
            ? terrainFingerprint : terrainAnchors.get(terrainAnchors.size() - 1).fingerprint();
        if (trajectorySamples == null) {
            trajectorySamples = new ArrayList<>();
        }
        trajectorySamples.removeIf(sample -> !validTrajectorySample(sample));
        if (!trajectorySamples.isEmpty()) {
            final ClientWorldTrajectory normalized = ClientWorldTrajectory.fromHistoricalSamples(
                trajectorySamples, ClientWorldTrajectory.DEFAULT_CAPACITY
            );
            trajectorySamples = new ArrayList<>(normalized.samples());
            lastServerAckTimeMs = normalized.lastServerAckTimeMs();
            connectionGeneration = normalized.latest().connectionGeneration();
        }
        if (lastServerAckTimeMs < ClientWorldTrajectorySample.NO_SERVER_ACK) {
            lastServerAckTimeMs = ClientWorldTrajectorySample.NO_SERVER_ACK;
        }
        if (connectionGeneration < 0L) {
            connectionGeneration = 0L;
        }
        contextSignals = normalizeContextSignals(contextSignals);
    }

    ClientWorldVisit copy() {
        final ClientWorldVisit copied = new ClientWorldVisit(
            dimensionId, gameMode, lastPosition, lastVisitedAtEpochMs,
            terrainFingerprint == null ? null : terrainFingerprint.copy(), contextSignals
        );
        copied.terrainAnchors = new ArrayList<>(terrainAnchors());
        copied.trajectorySamples = new ArrayList<>(trajectorySamples());
        copied.lastServerAckTimeMs = lastServerAckTimeMs;
        copied.connectionGeneration = connectionGeneration;
        return copied;
    }

    void copyPersistedEvidenceFrom(final ClientWorldVisit previous) {
        terrainAnchors = new ArrayList<>(previous.terrainAnchors());
        terrainFingerprint = previous.terrainFingerprint();
        trajectorySamples = new ArrayList<>(previous.trajectorySamples());
        lastServerAckTimeMs = previous.lastServerAckTimeMs();
        connectionGeneration = previous.connectionGeneration();
    }

    void rememberTrajectory(final ClientWorldTrajectory trajectory) {
        if (trajectory == null || trajectory.samples().isEmpty()) {
            return;
        }
        trajectorySamples = new ArrayList<>(trajectory.samples());
        lastServerAckTimeMs = trajectory.lastServerAckTimeMs();
        connectionGeneration = trajectory.latest().connectionGeneration();
    }

    void rememberTerrainAnchor(final ClientWorldTerrainAnchor anchor) {
        if (anchor == null) {
            return;
        }
        if (terrainAnchors == null) {
            terrainAnchors = new ArrayList<>();
        }
        terrainAnchors.removeIf(existing -> existing.position().equals(anchor.position()));
        terrainAnchors.add(anchor);
        while (terrainAnchors.size() > ClientWorldProfile.MAX_TERRAIN_ANCHORS) {
            terrainAnchors.remove(0);
        }
        terrainFingerprint = anchor.fingerprint();
    }

    ClientWorldTerrainAnchor terrainAnchorFor(final ClientWorldTerrainFingerprint observed) {
        if (observed == null || !observed.hasCenter()) {
            return null;
        }
        final List<ClientWorldTerrainAnchor> anchors = terrainAnchors();
        for (int index = anchors.size() - 1; index >= 0; index--) {
            final ClientWorldTerrainAnchor anchor = anchors.get(index);
            if (observed.sameCenter(anchor.fingerprint())) {
                return anchor;
            }
        }
        return null;
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

    private static boolean validTerrainAnchor(final ClientWorldTerrainAnchor anchor) {
        if (anchor == null || anchor.position() == null || anchor.fingerprint() == null
            || anchor.capturedAtEpochMs() < 0L) {
            return false;
        }
        final ClientWorldTerrainFingerprint fingerprint = anchor.fingerprint();
        return fingerprint.complete() && fingerprint.hasCenter()
            && (anchor.position().x() >> 4) == fingerprint.centerChunkX()
            && (anchor.position().z() >> 4) == fingerprint.centerChunkZ();
    }

    private static boolean validTrajectorySample(final ClientWorldTrajectorySample sample) {
        return sample != null && sample.dimensionId() != null && !sample.dimensionId().isBlank()
            && sample.evidenceSource() != null && sample.clientTimeMs() >= 0L
            && sample.clientTick() >= 0L && sample.sequence() >= 0L
            && sample.serverAckTimeMs() >= ClientWorldTrajectorySample.NO_SERVER_ACK
            && sample.connectionGeneration() >= 0L;
    }

    private static String requireText(final String value, final String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return Objects.requireNonNull(value, field);
    }
}
