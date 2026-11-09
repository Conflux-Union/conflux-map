package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;

/**
 * Real {@link BaselineSampler}, backed by the calling thread's cached {@link CubiomesContext}
 * for one fixed (mcVersion, seed, dim, flags) tuple (see {@link CubiomesContexts} for the per-thread
 * caching/epoch-invalidation contract - this class does no caching of its own, it's cheap
 * enough to construct fresh per tile compose). Never throws on native failure; every method
 * returns false, matching {@link BaselineSampler}'s contract. Also the production {@link
 * WaveComposer.BatchDispatch}: the wave leader resolves its own thread's context here (the
 * calling thread's cached context <em>is</em> the leader's generator state) and the voronoi sha
 * the biome batch validates is read from that context, never derived in Java.
 */
public final class NativeBaselineSampler implements BaselineSampler, WaveComposer.BatchDispatch {
    private static final int CLIMATE_MODEL_MIN = McVersions.toCubiomes("1.18").orElseThrow();

    private final int mcVersion;
    private final long seed;
    private final int dim;
    private final int flags;

    /**
     * Latches the sparse refinement entries off for the whole process once the loaded library
     * proves to predate them ({@link UnsatisfiedLinkError} on the first packed call). These are
     * optional new symbols on a library whose older exports keep working, so only this
     * capability drops - never {@link NativeLib#disableForSession}, which would kill the GPU
     * kernels the same library still serves. Every later call takes the interface's default
     * per-point loops.
     */
    private static volatile boolean sparseNativeAvailable = true;

    /** Whether the sparse native entries are usable on the loaded library; test-visible latch state. */
    static boolean sparseNativeAvailable() {
        return sparseNativeAvailable;
    }

    /** Test seam for the latch: simulate a stale library and restore the default afterwards. */
    static void setSparseNativeAvailable(final boolean available) {
        sparseNativeAvailable = available;
    }

    /** {@code flags} are cubiomes {@code setupGenerator} flags - see {@link WorldPreset#cubiomesFlags()}. */
    public NativeBaselineSampler(final int mcVersion, final long seed, final int dim, final int flags) {
        this.mcVersion = mcVersion;
        this.seed = seed;
        this.dim = dim;
        this.flags = flags;
    }

    /**
     * Debug-only stage accounting of this sampler's (thread-cached) context - see {@link
     * CubiomesNative#cfxStageStats}. Take one packed value before and one after a tile's
     * sampling; the difference attributes that tile's overview/biome stages to GPU or CPU.
     * Returns {@code 0} (no data) when the native library is unavailable or predates this
     * export, so a stale library degrades to a {@code none} backend annotation instead of
     * failing a debug path.
     */
    public long gpuStageStats() {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx == null ? 0L : ctx.stageStats();
        } catch (final UnsatisfiedLinkError staleLibrary) {
            return 0L;
        }
    }

    @Override
    public int terrainFreeBiomeScale() {
        if (dim != PredictionDimensions.OVERWORLD) {
            return 0;
        }
        // Mirrors the shim's cfxGenerateBiomes gating: from 1.18 on, Overworld scale 1/4 embed
        // the per-column surface solve, so the finest terrain-free query is the scale-16
        // climate pass; the layer stacks before that never touch terrain at any scale.
        return mcVersion >= CLIMATE_MODEL_MIN ? 16 : 1;
    }

    @Override
    public boolean interpolatableOverviewHeights() {
        // Only the climate-model overview is smooth at half output resolution; the pre-1.18
        // overview is the frozen reference output and must keep its per-position values.
        return mcVersion >= CLIMATE_MODEL_MIN;
    }

    @Override
    public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.biomes(scale, x, z, w, h, out) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean biomesStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.biomesStrided(scale, x, z, w, h, stride, out) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean biomesSparseStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.biomesSparseStrided(scale, x, z, w, h, stride, out) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean biomesScanStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride,
        final int targetBiomeId, final int[] out
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.biomesScanStrided(
                scale, x, z, w, h, stride, targetBiomeId, out
            ) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean biomesAtYStrided(
        final int blockY,
        final int scale,
        final int x,
        final int z,
        final int w,
        final int h,
        final int stride,
        final int[] out
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.biomesAtYStrided(
                blockY, scale, x, z, w, h, stride, out
            ) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return false;
            }
            final int[] scratchIds = new int[w * h];
            return ctx.heights(x4, z4, w, h, outY, scratchIds) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean heightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return false;
            }
            final int[] scratchIds = new int[w * h];
            return ctx.heightsStrided(x4, z4, w, h, stride, outY, scratchIds) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean surfaceColumns(
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
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.surfaceColumns(
                blockX, blockZ, w, h, stride,
                outSolidY, outFluidY, outSurfaceY, outFlags
            ) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean overviewHeights(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] outTerrainY
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.overviewHeights(
                blockX, blockZ, w, h, stride, outTerrainY
            ) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean overviewHeightsCoarse(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] outTerrainY
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.overviewHeightsCoarse(
                blockX, blockZ, w, h, stride, outTerrainY
            ) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    /**
     * One packed JNI call per bounded chunk of the waterline band, resolved on the calling
     * thread's cached context. A {@code null} context (unsupported identity) and a latched or
     * newly-missing sparse capability leave the request list to the interface's default
     * per-point loop; a nonzero status from a working entry returns false so the caller
     * repairs through {@link #overviewHeights} - all-or-nothing per chunk.
     */
    @Override
    public boolean overviewHeightsSparse(
        final int[] blockX,
        final int[] blockZ,
        final int n,
        final int[] outTerrainY
    ) {
        if (!sparseNativeAvailable) {
            return BaselineSampler.super.overviewHeightsSparse(blockX, blockZ, n, outTerrainY);
        }
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return BaselineSampler.super.overviewHeightsSparse(blockX, blockZ, n, outTerrainY);
            }
            return ctx.overviewHeightsSparse(blockX, blockZ, n, outTerrainY) == 0;
        } catch (final UnsatisfiedLinkError staleLibrary) {
            sparseNativeAvailable = false;
            return false;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean surfaceBiomes(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int[] terrainY,
        final int[] outBiomeIds
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.surfaceBiomes(
                blockX, blockZ, w, h, stride, terrainY, outBiomeIds
            ) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    /**
     * One packed JNI call per bounded chunk of the cell refinement sweep - same capability
     * split as {@link #overviewHeightsSparse}: unavailable context or missing sparse symbol
     * takes the interface's default per-cell loop, a working entry's nonzero status returns
     * false for the caller to repair through {@link #surfaceBiomes}.
     */
    @Override
    public boolean surfaceBiomesSparse(
        final int[] cellX,
        final int[] cellZ,
        final int edge,
        final int stride,
        final int[] terrainYPacked,
        final int n,
        final int[] outBiomeIdsPacked
    ) {
        if (!sparseNativeAvailable) {
            return BaselineSampler.super.surfaceBiomesSparse(
                cellX, cellZ, edge, stride, terrainYPacked, n, outBiomeIdsPacked
            );
        }
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return BaselineSampler.super.surfaceBiomesSparse(
                    cellX, cellZ, edge, stride, terrainYPacked, n, outBiomeIdsPacked
                );
            }
            return ctx.surfaceBiomesSparse(
                cellX, cellZ, edge, stride, terrainYPacked, n, outBiomeIdsPacked
            ) == 0;
        } catch (final UnsatisfiedLinkError staleLibrary) {
            sparseNativeAvailable = false;
            return false;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    /**
     * Batched coarse-lattice overview heights for a wave of tiles, resolved on the calling
     * thread's cached context - see {@link CubiomesNative#cfxOverviewHeightsCoarseBatch} for
     * the flat-array contract and the per-tile failure bitmap. Returns 0 when the batch was
     * attempted (repair the {@code failMask} set bits through {@link #overviewHeightsCoarse});
     * nonzero when the caller must dispatch its tiles individually, which is also what an
     * unavailable context or a latched fault returns. A refusal latches wave batching off for
     * the rest of the process via {@link GpuBackend#noteBatchRefused}.
     */
    @Override
    public int overviewHeightsCoarseBatch(
        final int[] blockX,
        final int[] blockZ,
        final int[] w,
        final int[] h,
        final int[] stride,
        final int n,
        final int[] outTerrainY,
        final int[] failMask
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return 1;
            }
            final int status = ctx.overviewHeightsCoarseBatch(
                blockX, blockZ, w, h, stride, n, outTerrainY, failMask
            );
            if (status != 0) {
                GpuBackend.noteBatchRefused();
            }
            return status;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return 1;
        }
    }

    /**
     * Batched surface biomes from flat caller-filled terrain planes, resolved on the calling
     * thread's cached context - see {@link CubiomesNative#cfxSurfaceBiomesBatch} for the
     * flat-array contract. {@code sha} is cubiomes' voronoi sha for the seed (cubiomes'
     * {@code getVoronoiSHA}); a probe from a different generator refuses the batch. Returns 0
     * when the batch was attempted (repair the {@code failMask} set bits through
     * {@link #surfaceBiomes}); nonzero when the caller must dispatch its tiles individually.
     */
    public int surfaceBiomesBatch(
        final long sha,
        final int[] blockX,
        final int[] blockZ,
        final int[] w,
        final int[] h,
        final int[] stride,
        final int[] terrainY,
        final int n,
        final int[] outBiomeIds,
        final int[] failMask
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx == null
                ? 1
                : ctx.surfaceBiomesBatch(
                    sha, blockX, blockZ, w, h, stride, terrainY, n, outBiomeIds, failMask
                );
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return 1;
        }
    }

    /**
     * Wave-seam dispatch: same batch as {@link #surfaceBiomesBatch(long, int[], int[], int[],
     * int[], int[], int[], int, int[], int[])}, but the voronoi sha comes from the resolved
     * context instead of the caller - the wave leader owns the generator, so the probe cannot
     * disagree with it. A library predating the sha accessor refuses the batch (per-tile
     * fallback) without latching the native library dead. A refusal latches wave batching off
     * for the rest of the process via {@link GpuBackend#noteBatchRefused}.
     */
    @Override
    public int surfaceBiomesBatch(
        final int[] blockX,
        final int[] blockZ,
        final int[] w,
        final int[] h,
        final int[] stride,
        final int[] terrainY,
        final int n,
        final int[] outBiomeIds,
        final int[] failMask
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return 1;
            }
            final long sha;
            try {
                sha = ctx.voronoiSha();
            } catch (final UnsatisfiedLinkError staleLibrary) {
                return 1;
            }
            final int status = ctx.surfaceBiomesBatch(
                sha, blockX, blockZ, w, h, stride, terrainY, n, outBiomeIds, failMask
            );
            if (status != 0) {
                GpuBackend.noteBatchRefused();
            }
            return status;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return 1;
        }
    }

    /**
     * The calling thread's context serves exactly this sampler's fixed identity whenever it
     * resolves at all ({@link CubiomesContexts} is a keyed lookup), so the real check happens
     * through the resolution inside the batch entries; this comparison pins the wave
     * coordinator's pre-dispatch re-validation contract at the seam.
     */
    @Override
    public boolean batchContextMatches(final long waveSeed, final int waveMcVersion) {
        return waveSeed == seed && waveMcVersion == mcVersion;
    }

    @Override
    public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.endHeights(x4, z4, w, h, outY) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean endHeightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            return ctx != null && ctx.endHeightsStrided(x4, z4, w, h, stride, outY) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public int treeCandidates(final int chunkX, final int chunkZ, final TreeCandidate[] out) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, flags);
            if (ctx == null) {
                return TREES_FAILED;
            }
            final int capacity = out.length;
            final int[] xs = new int[capacity];
            final int[] ys = new int[capacity];
            final int[] zs = new int[capacity];
            final int[] types = new int[capacity];
            final int[] biomes = new int[capacity];
            final int[] treeFlags = new int[capacity];
            final int[] count = new int[1];
            final int status = ctx.treeCandidates(
                chunkX, chunkZ, xs, ys, zs, types, biomes, treeFlags, count
            );
            if (status == CubiomesContext.STATUS_FEATURE_PARTIAL) {
                return TREES_UNSUPPORTED;
            }
            if (status != 0 || count[0] < 0 || count[0] > capacity) {
                return TREES_FAILED;
            }
            for (int i = 0; i < count[0]; i++) {
                out[i] = new TreeCandidate(xs[i], ys[i], zs[i], types[i], biomes[i], treeFlags[i]);
            }
            return count[0];
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return TREES_FAILED;
        }
    }
}
