package cn.net.rms.confluxmap.core.model;

/** Coarse classification of what a map column's visible surface is. */
public enum SurfaceKind {
    UNKNOWN,
    LAND,
    WATER,
    LAVA,
    FOLIAGE,
    SNOW,
    ICE,
    SAND,
    BEDROCK_CEILING,
    VOID;

    private static final SurfaceKind[] VALUES = values();

    public static SurfaceKind byOrdinal(final int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : UNKNOWN;
    }
}
