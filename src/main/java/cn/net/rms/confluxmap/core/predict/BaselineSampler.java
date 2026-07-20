package cn.net.rms.confluxmap.core.predict;

/**
 * Pure abstraction over raw seed-predicted biome/height sampling, batched over rectangles in
 * each query's own native coordinate scale - the same shape as {@code
 * nativepredict.CubiomesContext}, so {@link NativeBaselineSampler} is a thin adapter over one.
 * Tests substitute a fake implementation to exercise {@link LodSampling}/{@link
 * PredictedTileComposer} deterministically without a native library.
 *
 * <p>A single sampler instance is bound to one (mcVersion, seed, dimension) triple; which of
 * {@link #heights}/{@link #endHeights} is meaningful to call depends on that dimension (mirrors
 * {@code CubiomesContext}, which likewise exposes both regardless of which dimension its handle
 * was actually created for) - callers ({@link LodSampling}) only ever call the one matching the
 * tile's own dimension.
 */
public interface BaselineSampler {
    /** The biome decorator is not modeled for this chunk; retain the synthetic canopy fallback. */
    int TREES_UNSUPPORTED = -1;
    /** Native feature sampling failed; retain the synthetic canopy fallback. */
    int TREES_FAILED = -2;

    /**
     * Fills {@code out} (length &ge; w*h) with cubiomes biome ids for a w*h rectangle at native
     * {@code scale} (1, 4 or 16 for this milestone's LOD table), {@code x}/{@code z} already
     * expressed in that scale's coordinate units. Returns false on failure.
     */
    boolean biomes(int scale, int x, int z, int w, int h, int[] out);

    /**
     * Fills an output grid whose neighboring cells are {@code stride} native coordinates apart.
     * The default implementation batches one dense source row at a time; native-backed samplers
     * override this with one JNI call so high LODs do not materialize a mostly-discarded square.
     */
    default boolean biomesStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        final int rawWidth = (w - 1) * stride + 1;
        final int[] row = new int[rawWidth];
        for (int zz = 0; zz < h; zz++) {
            if (!biomes(scale, x, z + zz * stride, rawWidth, 1, row)) {
                return false;
            }
            for (int xx = 0; xx < w; xx++) {
                out[zz * w + xx] = row[xx * stride];
            }
        }
        return true;
    }

    /** Overworld: floored heights for a w*h rectangle at 1:4 scale ({@code x4}/{@code z4} = blockX/4, blockZ/4). */
    boolean heights(int x4, int z4, int w, int h, int[] outY);

    /** Strided Overworld height equivalent of {@link #biomesStrided}. */
    default boolean heightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        final int rawWidth = (w - 1) * stride + 1;
        final int[] row = new int[rawWidth];
        for (int zz = 0; zz < h; zz++) {
            if (!heights(x4, z4 + zz * stride, rawWidth, 1, row)) {
                return false;
            }
            for (int xx = 0; xx < w; xx++) {
                outY[zz * w + xx] = row[xx * stride];
            }
        }
        return true;
    }

    /** End: floored End surface heights (0 = void) for a w*h rectangle at 1:4 scale. */
    boolean endHeights(int x4, int z4, int w, int h, int[] outY);

    /** Strided End height equivalent of {@link #biomesStrided}. */
    default boolean endHeightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        final int rawWidth = (w - 1) * stride + 1;
        final int[] row = new int[rawWidth];
        for (int zz = 0; zz < h; zz++) {
            if (!endHeights(x4, z4 + zz * stride, rawWidth, 1, row)) {
                return false;
            }
            for (int xx = 0; xx < w; xx++) {
                outY[zz * w + xx] = row[xx * stride];
            }
        }
        return true;
    }

    /**
     * Fills {@code out} with the 1.17.1 tree-like decoration candidates for one Overworld chunk.
     * Returns the number written, {@link #TREES_UNSUPPORTED}, or {@link #TREES_FAILED}. The
     * default keeps non-native test samplers and other implementations on the synthetic path.
     */
    default int treeCandidates(final int chunkX, final int chunkZ, final TreeCandidate[] out) {
        return TREES_UNSUPPORTED;
    }
}
