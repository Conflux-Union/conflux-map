package cn.net.rms.confluxmap.core.model;

/**
 * Immutable per-column map data for one 16x16 chunk, produced on the main
 * thread and consumed by worker threads. Arrays are indexed {@code z * 16 + x}
 * and must never be mutated after construction.
 */
public final class ChunkSnapshot {
    public static final int COLUMNS = 256;
    /** Marker in {@link #surfaceY} for columns with no surface (void). */
    public static final short NO_SURFACE = Short.MIN_VALUE;

    public final int chunkX;
    public final int chunkZ;
    public final long sessionToken;
    public final short[] surfaceY;
    public final byte[] fluidDepth;
    public final int[] baseArgb;
    public final int[] tintArgb;
    public final byte[] kind;

    public ChunkSnapshot(
        final int chunkX,
        final int chunkZ,
        final long sessionToken,
        final short[] surfaceY,
        final byte[] fluidDepth,
        final int[] baseArgb,
        final int[] tintArgb,
        final byte[] kind
    ) {
        if (surfaceY.length != COLUMNS || fluidDepth.length != COLUMNS
            || baseArgb.length != COLUMNS || tintArgb.length != COLUMNS || kind.length != COLUMNS) {
            throw new IllegalArgumentException("snapshot arrays must have 256 entries");
        }
        this.chunkX = chunkX;
        this.chunkZ = chunkZ;
        this.sessionToken = sessionToken;
        this.surfaceY = surfaceY;
        this.fluidDepth = fluidDepth;
        this.baseArgb = baseArgb;
        this.tintArgb = tintArgb;
        this.kind = kind;
    }
}
