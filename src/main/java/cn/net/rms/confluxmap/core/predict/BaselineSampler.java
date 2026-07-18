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
    /**
     * Fills {@code out} (length &ge; w*h) with cubiomes biome ids for a w*h rectangle at native
     * {@code scale} (1, 4 or 16 for this milestone's LOD table), {@code x}/{@code z} already
     * expressed in that scale's coordinate units. Returns false on failure.
     */
    boolean biomes(int scale, int x, int z, int w, int h, int[] out);

    /** Overworld: floored heights for a w*h rectangle at 1:4 scale ({@code x4}/{@code z4} = blockX/4, blockZ/4). */
    boolean heights(int x4, int z4, int w, int h, int[] outY);

    /** End: floored End surface heights (0 = void) for a w*h rectangle at 1:4 scale. */
    boolean endHeights(int x4, int z4, int w, int h, int[] outY);
}
