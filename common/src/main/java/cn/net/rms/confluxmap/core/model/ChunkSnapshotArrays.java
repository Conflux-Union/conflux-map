package cn.net.rms.confluxmap.core.model;

/**
 * The ten per-column parallel arrays of one 16x16 chunk, allocated together by
 * every {@link ChunkSnapshot} producer and filled in place before wrapping.
 * Field order mirrors the fourteen-argument {@link ChunkSnapshot} constructor.
 */
public final class ChunkSnapshotArrays {
    public final short[] surfaceY = new short[ChunkSnapshot.COLUMNS];
    public final String[] biomeId = new String[ChunkSnapshot.COLUMNS];
    public final byte[] fluidDepth = new byte[ChunkSnapshot.COLUMNS];
    public final int[] baseArgb = new int[ChunkSnapshot.COLUMNS];
    public final int[] xaeroBaseArgb = new int[ChunkSnapshot.COLUMNS];
    public final int[] tintArgb = new int[ChunkSnapshot.COLUMNS];
    public final int[] overlayArgb = new int[ChunkSnapshot.COLUMNS];
    public final int[] xaeroOverlayArgb = new int[ChunkSnapshot.COLUMNS];
    public final byte[] kind = new byte[ChunkSnapshot.COLUMNS];
    public final byte[] light = new byte[ChunkSnapshot.COLUMNS];

    public ChunkSnapshot toSnapshot(int chunkX, int chunkZ, long sessionToken, long sourceRevision) {
        return new ChunkSnapshot(chunkX, chunkZ, sessionToken, sourceRevision, surfaceY,
            biomeId, fluidDepth, baseArgb, xaeroBaseArgb, tintArgb, overlayArgb,
            xaeroOverlayArgb, kind, light);
    }

    /** Wraps the buffers with the given {@code surfaceY} taking the place of the buffered one. */
    public ChunkSnapshot toSnapshot(int chunkX, int chunkZ, long sessionToken, long sourceRevision, short[] surfaceY) {
        return new ChunkSnapshot(chunkX, chunkZ, sessionToken, sourceRevision, surfaceY,
            biomeId, fluidDepth, baseArgb, xaeroBaseArgb, tintArgb, overlayArgb,
            xaeroOverlayArgb, kind, light);
    }
}
