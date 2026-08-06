package cn.net.rms.confluxmap.nativepredict;

/** Allocation-light native parser for the centered columns used by coarse Anvil scans. */
public final class NativeChunkNbtScanner {
    private static final int NUMERIC_HEADER = 1;
    private static final int NUMERIC_FIELDS = 4;
    private static final int STRING_FIELDS = 3;

    public record Sample(
        int biomeId,
        String biomeName,
        int surfaceY,
        int fluidKind,
        int fluidDepth,
        int blockLight,
        String surfaceBlock,
        String floorBlock
    ) {
    }

    public record Chunk(boolean generated, long revision, int sampleStride, Sample[] samples) {
        public Chunk {
            samples = samples.clone();
        }
    }

    private NativeChunkNbtScanner() {
    }

    /** Returns {@code null} when native parsing is unavailable or the chunk NBT is malformed. */
    public static Chunk scan(final byte[] nbt, final int lod) {
        return scan(nbt, nbt == null ? 0 : nbt.length, lod);
    }

    /** Buffer-length variant used by the Anvil reader to avoid copying a growable input buffer. */
    public static Chunk scan(final byte[] nbt, final int nbtLength, final int lod) {
        if (!NativeLib.available() || nbt == null || nbtLength < 0 || nbtLength > nbt.length
            || lod < 0 || lod > 4) {
            return null;
        }
        final int sampleStride = 1 << lod;
        final int samplesPerSide = 16 / sampleStride;
        final int sampleCount = samplesPerSide * samplesPerSide;
        final long[] revision = new long[1];
        final int lightOffset = NUMERIC_HEADER + sampleCount * NUMERIC_FIELDS;
        final int[] numeric = new int[lightOffset + sampleCount];
        final String[] strings = new String[sampleCount * STRING_FIELDS];
        final int status;
        try {
            status = CubiomesNative.cfxScanChunkNbt(
                nbt, nbtLength, lod, revision, numeric, strings
            );
        } catch (final RuntimeException | UnsatisfiedLinkError e) {
            return null;
        }
        if (status != 0) {
            return null;
        }
        if (numeric[0] == 0) {
            return new Chunk(false, 0L, sampleStride, new Sample[0]);
        }
        final Sample[] samples = new Sample[sampleCount];
        for (int i = 0; i < sampleCount; i++) {
            final int n = NUMERIC_HEADER + i * NUMERIC_FIELDS;
            final int s = i * STRING_FIELDS;
            samples[i] = new Sample(
                numeric[n],
                strings[s],
                numeric[n + 1],
                numeric[n + 2],
                numeric[n + 3],
                numeric[lightOffset + i],
                strings[s + 1],
                strings[s + 2]
            );
        }
        return new Chunk(true, revision[0], sampleStride, samples);
    }
}
