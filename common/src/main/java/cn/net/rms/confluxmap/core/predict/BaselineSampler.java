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

    /**
     * Nether-only biome grid at one block Y. Coordinates use the same scale and stride convention
     * as {@link #biomesStrided}; implementations must preserve the Nether biome source's vertical
     * noise dimension rather than substituting the roof plane.
     */
    default boolean biomesAtYStrided(
        final int blockY,
        final int scale,
        final int x,
        final int z,
        final int w,
        final int h,
        final int stride,
        final int[] out
    ) {
        return false;
    }

    /**
     * Sparse strided coarse-biome sampling for wide-area search: each output cell is one
     * independent biome-source query, instead of the dense-row sweep {@link #biomesStrided}
     * performs (whose cost scales with {@code stride} per output cell). Overworld supports
     * scale 16 on the post-1.18 climate model, Nether and End scale 4; implementations return
     * false for unsupported (dimension, scale) combinations and callers fall back.
     */
    default boolean biomesSparseStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        return false;
    }

    /**
     * Search-targeted variant of {@link #biomesSparseStrided}: cells that cannot be {@code
     * targetBiomeId} may be rejected cheaply and reported as {@code -1} instead of paying a full
     * biome query; every other cell carries its real biome id. The default falls back to the
     * plain sparse sweep, which yields the same hits.
     */
    default boolean biomesScanStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride,
        final int targetBiomeId, final int[] out
    ) {
        return biomesSparseStrided(scale, x, z, w, h, stride, out);
    }

    /**
     * Finest native biome scale this sampler resolves without any terrain work, for the
     * biome-identity view that never needs heights: 1 for the pre-1.18 layer stacks (any scale
     * is a pure biome-stack query there), 16 for the post-1.18 Overworld climate model (scale
     * 1/4 embed the full per-column surface solve), 0 when no terrain-free query exists and the
     * caller must keep the height-aware pipeline.
     */
    default int terrainFreeBiomeScale() {
        return 0;
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

    /**
     * Resolves Overworld base columns at block coordinates. Adjacent output cells are {@code
     * stride} blocks apart; the four arrays receive the highest solid block, highest base-fluid
     * block, final visible base surface, and surface flags respectively. Native-backed samplers
     * override this so fluid ownership stays inside the terrain generator.
     */
    default boolean surfaceColumns(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] outSolidY,
        final int[] outFluidY,
        final int[] outSurfaceY,
        final int[] outFlags
    ) {
        return false;
    }

    /**
     * Produces a cheap terrain overview at every requested output position. The output layout is
     * identical for every Minecraft version; native implementations may use the version's own
     * terrain model internally, but must not change the sampling density or return a coarser grid.
     * Exact sparse anchors are applied by {@link LodSampling} after this call.
     */
    default boolean overviewHeights(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] outTerrainY
    ) {
        final int cells = w * h;
        final int[] fluid = new int[cells];
        final int[] surface = new int[cells];
        final int[] flags = new int[cells];
        return surfaceColumns(
            blockX, blockZ, w, h, stride,
            outTerrainY, fluid, surface, flags
        );
    }

    /**
     * Whether {@link LodSampling} may reconstruct this sampler's fields from sparse lattices
     * instead of querying every position: overview heights bilinearly between every-second-pixel
     * anchors (waterline zones still solve exactly), and the LOD3-4 sub-biome sweep copied from
     * anchor-uniform cells where the water/land classification provably ignores the biome. This
     * is a quality statement about the sampler's models, not a capability: the 1.18+ native
     * predictor is smooth at those spacings, while pre-1.18 output is pinned bit-exact as the
     * reference and non-native samplers keep their per-position contract, so both opt out (the
     * default).
     */
    default boolean interpolatableOverviewHeights() {
        return false;
    }

    /**
     * Coarse-lattice overview for the LOD2-4 anchor sweeps: same geometry as {@link
     * #overviewHeights}, but implementations may skip terrain detail finer than the sampling
     * stride. The default keeps full detail; waterline-band and exact-residual queries always
     * use {@link #overviewHeights}.
     */
    default boolean overviewHeightsCoarse(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] outTerrainY
    ) {
        return overviewHeights(blockX, blockZ, w, h, stride, outTerrainY);
    }

    /**
     * Resolves final Overworld surface biomes at the supplied terrain heights without generating
     * those heights a second time. Implementations that cannot reuse the height grid return false,
     * and {@link LodSampling} falls back to {@link #biomesStrided}.
     */
    default boolean surfaceBiomes(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] terrainY,
        final int[] outBiomeIds
    ) {
        return false;
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
     * Fills {@code out} with version-specific natural vegetation candidates for one Overworld
     * chunk. Returns the number written, {@link #TREES_UNSUPPORTED}, or {@link #TREES_FAILED}.
     * The default keeps non-native test samplers and other implementations on the synthetic path.
     */
    default int treeCandidates(final int chunkX, final int chunkZ, final TreeCandidate[] out) {
        return TREES_UNSUPPORTED;
    }
}
