package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.NativeLib;

/**
 * Real {@link BaselineSampler}, backed by the calling thread's cached {@link CubiomesContext}
 * for one fixed (mcVersion, seed, dim, flags) tuple (see {@link CubiomesContexts} for the per-thread
 * caching/epoch-invalidation contract - this class does no caching of its own, it's cheap
 * enough to construct fresh per tile compose). Never throws on native failure; every method
 * returns false, matching {@link BaselineSampler}'s contract.
 */
public final class NativeBaselineSampler implements BaselineSampler {
    private final int mcVersion;
    private final long seed;
    private final int dim;
    private final int flags;

    /** {@code flags} are cubiomes {@code setupGenerator} flags - see {@link WorldPreset#cubiomesFlags()}. */
    public NativeBaselineSampler(final int mcVersion, final long seed, final int dim, final int flags) {
        this.mcVersion = mcVersion;
        this.seed = seed;
        this.dim = dim;
        this.flags = flags;
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
