package cn.net.rms.confluxmap.core.predict;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Deterministic terrain and biome model shared by {@link WaveComposerTest} and
 * PredictionTileServiceTest's service-level wave pin. The per-tile and batch entries fill from
 * the same pure functions of block coordinates, so batched dispatch and per-tile dispatch agree
 * by construction, and a wave's staged result is comparable member-for-member against
 * {@link LodSampling#sample}. Heights straddle sea level so the waterline band and the compose
 * stage both do real work. Also records every per-tile and batch call so tests can observe
 * which dispatch shape actually ran.
 */
final class RecordingWaveSampler implements BaselineSampler, WaveComposer.BatchDispatch {
    /** Opaque identity the fake serves; only equality is ever observed. */
    static final int MC_VERSION = 1;
    static final long SEED = 146008555L;

    final List<String> perTileCalls = Collections.synchronizedList(new ArrayList<>());
    final List<String> batchCalls = Collections.synchronizedList(new ArrayList<>());
    /** Names of the threads that ran the per-tile coarse heights entry; the gate pin reads the spread. */
    final List<String> coarseHeightsThreads = Collections.synchronizedList(new ArrayList<>());
    volatile boolean throwOnPerTileHeights;
    volatile Runnable insideHeightsBatch;
    /** Heights-batch tiles (by eligible index) reported failed; the wave must repair them per tile. */
    volatile int heightsFailBits;
    /** When set, both batch entries refuse (0-attempted/nonzero-refused contract) and the wave must go per tile. */
    volatile boolean refuseBatches;
    /** When set, the first context-identity probe (the leader's batch planning) mutates the seed. */
    volatile boolean mutateSeedOnContextProbe;
    /** The identity the wave was admitted with; the probe compares it against the live seed. */
    volatile long seed = SEED;

    /**
     * Only the seed can drift in this fake (the mc version is an opaque constant every caller
     * passes through unchanged), so identity drift is modeled on the seed alone.
     */
    @Override
    public boolean batchContextMatches(final long waveSeed, final int waveMcVersion) {
        return waveSeed == seed;
    }

    @Override
    public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        perTileCalls.add("biomes");
        fillBiomes(x, z, w, h, 1, out, 0);
        return true;
    }

    @Override
    public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        perTileCalls.add("heights");
        throwIfArmed();
        fillHeights(x4, z4, w, h, 1, outY, 0);
        return true;
    }

    @Override
    public boolean overviewHeights(
        final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
    ) {
        perTileCalls.add("overviewHeights");
        throwIfArmed();
        fillHeights(blockX, blockZ, w, h, stride, outTerrainY, 0);
        return true;
    }

    @Override
    public boolean overviewHeightsCoarse(
        final int blockX, final int blockZ, final int w, final int h, final int stride, final int[] outTerrainY
    ) {
        perTileCalls.add("overviewHeightsCoarse");
        coarseHeightsThreads.add(Thread.currentThread().getName());
        throwIfArmed();
        fillHeights(blockX, blockZ, w, h, stride, outTerrainY, 0);
        return true;
    }

    @Override
    public boolean surfaceBiomes(
        final int blockX, final int blockZ, final int w, final int h, final int stride,
        final int[] terrainY, final int[] outBiomeIds
    ) {
        perTileCalls.add("surfaceBiomes");
        fillBiomes(blockX, blockZ, w, h, stride, outBiomeIds, 0);
        return true;
    }

    @Override
    public boolean interpolatableOverviewHeights() {
        if (mutateSeedOnContextProbe) {
            mutateSeedOnContextProbe = false;
            seed++;
        }
        return true;
    }

    @Override
    public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        perTileCalls.add("endHeights");
        fillHeights(x4, z4, w, h, 1, outY, 0);
        return true;
    }

    /**
     * Feeds the lod0/1 exact-residual pass: solid equals the overview height (zero residual),
     * flags report no aquifer anywhere, so the exact pass neither moves a pixel nor marks fluid.
     */
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
        perTileCalls.add("surfaceColumns");
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                final int solid = heightAt(blockX + col * stride, blockZ + row * stride);
                final int i = row * w + col;
                outSolidY[i] = solid;
                outFluidY[i] = 0;
                outSurfaceY[i] = solid;
                outFlags[i] = 0;
            }
        }
        return true;
    }

    @Override
    public int overviewHeightsCoarseBatch(
        final int[] blockX, final int[] blockZ, final int[] w, final int[] h, final int[] stride,
        final int n, final int[] outTerrainY, final int[] failMask
    ) {
        batchCalls.add("heights:" + n);
        final Runnable hook = insideHeightsBatch;
        if (hook != null) {
            hook.run();
        }
        if (refuseBatches) {
            return 1;
        }
        int offset = 0;
        for (int i = 0; i < n; i++) {
            fillHeights(blockX[i], blockZ[i], w[i], h[i], stride[i], outTerrainY, offset);
            offset += w[i] * h[i];
        }
        failMask[0] |= heightsFailBits;
        return 0;
    }

    @Override
    public int surfaceBiomesBatch(
        final int[] blockX, final int[] blockZ, final int[] w, final int[] h, final int[] stride,
        final int[] terrainY, final int n, final int[] outBiomeIds, final int[] failMask
    ) {
        batchCalls.add("biomes:" + n);
        if (refuseBatches) {
            return 1;
        }
        int offset = 0;
        for (int i = 0; i < n; i++) {
            fillBiomes(blockX[i], blockZ[i], w[i], h[i], stride[i], outBiomeIds, offset);
            offset += w[i] * h[i];
        }
        return 0;
    }

    private void throwIfArmed() {
        if (throwOnPerTileHeights) {
            throw new IllegalStateException("per-tile height query failed");
        }
    }

    private static void fillHeights(
        final int x, final int z, final int w, final int h, final int stride, final int[] out, final int offset
    ) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                out[offset + row * w + col] = heightAt(x + col * stride, z + row * stride);
            }
        }
    }

    private static void fillBiomes(
        final int x, final int z, final int w, final int h, final int stride, final int[] out, final int offset
    ) {
        for (int row = 0; row < h; row++) {
            for (int col = 0; col < w; col++) {
                out[offset + row * w + col] = biomeAt(x + col * stride, z + row * stride);
            }
        }
    }

    private static int heightAt(final int x, final int z) {
        return 40 + ((x * 31 + z * 17) & 63);
    }

    private static int biomeAt(final int x, final int z) {
        return 1 + ((x * 7 + z * 13) & 3);
    }
}
