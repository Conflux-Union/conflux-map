package cn.net.rms.confluxmap.core.store;

import cn.net.rms.confluxmap.core.model.ChunkSnapshot;
import cn.net.rms.confluxmap.core.model.SampleSource;

/**
 * 256x256 columns of map samples covering 16x16 chunks. Column arrays are
 * indexed {@code localZ * 256 + localX}; chunk-granular metadata (source,
 * update time) is indexed {@code chunkZ * 16 + chunkX} within the region.
 * All access to the data arrays must hold the instance monitor; readers
 * copy out under the lock (see {@link #copyChunkRows}).
 */
public final class RegionColumns {
    public static final int SIZE = 256;
    public static final int CHUNKS = 16;

    public final int regionX;
    public final int regionZ;

    private final short[] surfaceY = new short[SIZE * SIZE];
    private final byte[] fluidDepth = new byte[SIZE * SIZE];
    private final int[] baseArgb = new int[SIZE * SIZE];
    private final int[] tintArgb = new int[SIZE * SIZE];
    private final byte[] kind = new byte[SIZE * SIZE];
    private final byte[] chunkSource = new byte[CHUNKS * CHUNKS];
    private final int[] chunkUpdateSeconds = new int[CHUNKS * CHUNKS];
    private volatile int version;

    public RegionColumns(final int regionX, final int regionZ) {
        this.regionX = regionX;
        this.regionZ = regionZ;
        java.util.Arrays.fill(surfaceY, ChunkSnapshot.NO_SURFACE);
    }

    /** Monotonic change counter; bumps on every accepted write. */
    public int version() {
        return version;
    }

    public synchronized boolean putChunk(final ChunkSnapshot snapshot, final SampleSource source) {
        final int chunkLocalX = snapshot.chunkX & (CHUNKS - 1);
        final int chunkLocalZ = snapshot.chunkZ & (CHUNKS - 1);
        final int chunkIndex = chunkLocalZ * CHUNKS + chunkLocalX;
        final SampleSource existing = SampleSource.byOrdinal(chunkSource[chunkIndex]);
        if (!source.overrides(existing)) {
            return false;
        }
        final int baseX = chunkLocalX * 16;
        final int baseZ = chunkLocalZ * 16;
        for (int z = 0; z < 16; z++) {
            final int rowFrom = z * 16;
            final int rowTo = (baseZ + z) * SIZE + baseX;
            System.arraycopy(snapshot.surfaceY, rowFrom, surfaceY, rowTo, 16);
            System.arraycopy(snapshot.fluidDepth, rowFrom, fluidDepth, rowTo, 16);
            System.arraycopy(snapshot.baseArgb, rowFrom, baseArgb, rowTo, 16);
            System.arraycopy(snapshot.tintArgb, rowFrom, tintArgb, rowTo, 16);
            System.arraycopy(snapshot.kind, rowFrom, kind, rowTo, 16);
        }
        chunkSource[chunkIndex] = (byte) source.ordinal();
        chunkUpdateSeconds[chunkIndex] = (int) (System.currentTimeMillis() / 1000L);
        version++;
        return true;
    }

    public synchronized SampleSource chunkSource(final int chunkLocalX, final int chunkLocalZ) {
        return SampleSource.byOrdinal(chunkSource[chunkLocalZ * CHUNKS + chunkLocalX]);
    }

    public synchronized int chunkUpdateSeconds(final int chunkLocalX, final int chunkLocalZ) {
        return chunkUpdateSeconds[chunkLocalZ * CHUNKS + chunkLocalX];
    }

    /** Count of chunks whose source priority is at least {@code min}. */
    public synchronized int countChunksAtLeast(final SampleSource min) {
        int n = 0;
        for (final byte b : chunkSource) {
            if (SampleSource.byOrdinal(b).priority() >= min.priority()) {
                n++;
            }
        }
        return n;
    }

    /**
     * Copy {@code rows} consecutive column rows starting at {@code startRow}
     * into caller-owned arrays, all of length {@code rows * 256}. Used by tile
     * composition and disk serialization to read a consistent snapshot without
     * holding the lock during downstream work.
     */
    public synchronized void copyChunkRows(
        final int startRow,
        final int rows,
        final short[] outSurfaceY,
        final byte[] outFluidDepth,
        final int[] outBaseArgb,
        final int[] outTintArgb,
        final byte[] outKind
    ) {
        final int from = startRow * SIZE;
        final int length = rows * SIZE;
        System.arraycopy(surfaceY, from, outSurfaceY, 0, length);
        System.arraycopy(fluidDepth, from, outFluidDepth, 0, length);
        System.arraycopy(baseArgb, from, outBaseArgb, 0, length);
        System.arraycopy(tintArgb, from, outTintArgb, 0, length);
        System.arraycopy(kind, from, outKind, 0, length);
    }
}
