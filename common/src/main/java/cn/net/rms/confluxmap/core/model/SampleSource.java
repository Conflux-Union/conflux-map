package cn.net.rms.confluxmap.core.model;

/**
 * Provenance of a map sample. Merge rule: higher priority always wins;
 * a lower-priority write may only fill {@link #UNKNOWN}.
 */
public enum SampleSource {
    UNKNOWN(0),
    PREDICTED(1),
    /** Terrain imported from an external world-map data source; loses to every captured source. */
    IMPORTED(2),
    REAL_CACHED(4),
    REAL_LIVE(5),
    /**
     * Terrain scanned from the singleplayer world's own Anvil region files.
     * Real terrain like every REAL_*, but derived from disk instead of live
     * capture, so both captured sources outrank it. Ordinal is persisted in
     * {@code .cfr} chunk tables: append only, never renumber — the priority
     * slot sits between IMPORTED and REAL_CACHED by construction, not position.
     */
    REAL_SCAN(3);

    private final int priority;

    SampleSource(final int priority) {
        this.priority = priority;
    }

    public int priority() {
        return priority;
    }

    public boolean overrides(final SampleSource existing) {
        return priority >= existing.priority;
    }

    /**
     * Whether this source represents observed terrain rather than a projection:
     * imported and captured data both answer location/teleport/biome queries,
     * predictions do not.
     */
    public boolean isExplored() {
        return priority >= IMPORTED.priority;
    }

    private static final SampleSource[] VALUES = values();

    public static SampleSource byOrdinal(final int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNKNOWN;
    }
}
