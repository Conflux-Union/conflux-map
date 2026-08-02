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
    ClientWorldTerrainFingerprint terrainFingerprint
) {
    public ClientWorldObservation(final OptionalLong seedHash, final Map<String, String> signals) {
        this(seedHash, signals, null, null, null, null);
    }

    public ClientWorldObservation {
        seedHash = Objects.requireNonNull(seedHash, "seedHash");
        final Map<String, String> normalized = new LinkedHashMap<>();
        for (final Map.Entry<String, String> entry : Objects.requireNonNull(signals, "signals").entrySet()) {
            if (entry.getKey() != null && !entry.getKey().isBlank()
                && entry.getValue() != null && !entry.getValue().isBlank()) {
                normalized.put(entry.getKey(), entry.getValue());
            }
        }
        signals = Map.copyOf(normalized);
        dimensionId = normalizeText(dimensionId);
        gameMode = normalizeText(gameMode);
    }

    private static String normalizeText(final String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
