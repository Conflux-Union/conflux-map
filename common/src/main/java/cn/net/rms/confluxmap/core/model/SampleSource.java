package cn.net.rms.confluxmap.core.model;

/**
 * Provenance of a map sample. Merge rule: higher priority always wins;
 * a lower-priority write may only fill {@link #UNKNOWN}.
 */
public enum SampleSource {
    UNKNOWN(0),
    PREDICTED(1),
    REAL_CACHED(2),
    REAL_LIVE(3);

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

    private static final SampleSource[] VALUES = values();

    public static SampleSource byOrdinal(final int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNKNOWN;
    }
}
