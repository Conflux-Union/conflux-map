package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.NativeLib;

/**
 * Real {@link BaselineSampler}, backed by the calling thread's cached {@link CubiomesContext}
 * for one fixed (mcVersion, seed, dim) triple (see {@link CubiomesContexts} for the per-thread
 * caching/epoch-invalidation contract - this class does no caching of its own, it's cheap
 * enough to construct fresh per tile compose). Never throws on native failure; every method
 * returns false, matching {@link BaselineSampler}'s contract.
 */
public final class NativeBaselineSampler implements BaselineSampler {
    private static final int FLAGS = 0;

    private final int mcVersion;
    private final long seed;
    private final int dim;

    public NativeBaselineSampler(final int mcVersion, final long seed, final int dim) {
        this.mcVersion = mcVersion;
        this.seed = seed;
        this.dim = dim;
    }

    @Override
    public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, FLAGS);
            return ctx != null && ctx.biomes(scale, x, z, w, h, out) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }

    @Override
    public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, FLAGS);
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
    public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        try {
            final CubiomesContext ctx = CubiomesContexts.get(mcVersion, seed, dim, FLAGS);
            return ctx != null && ctx.endHeights(x4, z4, w, h, outY) == 0;
        } catch (final Throwable fault) {
            NativeLib.disableForSession(fault);
            return false;
        }
    }
}
