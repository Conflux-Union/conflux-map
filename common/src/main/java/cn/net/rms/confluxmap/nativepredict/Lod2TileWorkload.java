package cn.net.rms.confluxmap.nativepredict;


/**
 * The benchmark's fixed workload in both production forms: {@link GpuBenchmark#TILE_COUNT}
 * LOD2-shaped tiles, each a 64x64 coarse-lattice overview-heights sweep at stride 8 (a
 * 512-block tile) followed by the surface-biome solve of the same grid. The CPU form is
 * the per-tile per-thread call pair the worker pool issues; the GPU form is the one-shot
 * wave batch the leader thread issues - the exact shapes whose relative speed decides the
 * AUTO verdict. Batches honor the all-or-nothing contract: any nonzero status or failMask
 * bit fails the leg (no per-tile repair, this is a measurement).
 */
final class Lod2TileWorkload implements GpuBenchmark.Workload {
    private static final int GRID = 64;
    private static final int STRIDE = 8;
    /** Engine limit per batch dispatch ({@code CFX_BATCH_MAX_BLOCKS}); the wave leader chunks at it too. */
    private static final int MAX_TILES_PER_BATCH = 32;

    static int tileOriginX(final int tileIndex) {
        return (tileIndex % 4) * (GRID * STRIDE);
    }

    static int tileOriginZ(final int tileIndex) {
        return (tileIndex / 4) * (GRID * STRIDE);
    }

    @Override
    public boolean cpuTile(final CubiomesContext ctx, final int tileIndex) {
        if (ctx == null) {
            return false;
        }
        final int[] terrainY = new int[GRID * GRID];
        final int[] biomeIds = new int[GRID * GRID];
        final int x = tileOriginX(tileIndex);
        final int z = tileOriginZ(tileIndex);
        return ctx.overviewHeightsCoarse(x, z, GRID, GRID, STRIDE, terrainY) == 0
            && ctx.surfaceBiomes(x, z, GRID, GRID, STRIDE, terrainY, biomeIds) == 0;
    }

    @Override
    public boolean gpuBatch(final CubiomesContext ctx, final int firstTile, final int tileCount) {
        if (ctx == null) {
            return false;
        }
        final long sha;
        try {
            sha = ctx.voronoiSha();
        } catch (final UnsatisfiedLinkError staleLibrary) {
            return false;
        }
        final int[] blockX = new int[MAX_TILES_PER_BATCH];
        final int[] blockZ = new int[MAX_TILES_PER_BATCH];
        final int[] w = new int[MAX_TILES_PER_BATCH];
        final int[] h = new int[MAX_TILES_PER_BATCH];
        final int[] stride = new int[MAX_TILES_PER_BATCH];
        final int[] terrainY = new int[MAX_TILES_PER_BATCH * GRID * GRID];
        final int[] biomeIds = new int[MAX_TILES_PER_BATCH * GRID * GRID];
        final int[] failMask = new int[1];
        for (int offset = 0; offset < tileCount; offset += MAX_TILES_PER_BATCH) {
            final int n = Math.min(MAX_TILES_PER_BATCH, tileCount - offset);
            for (int i = 0; i < n; i++) {
                blockX[i] = tileOriginX(firstTile + offset + i);
                blockZ[i] = tileOriginZ(firstTile + offset + i);
                w[i] = GRID;
                h[i] = GRID;
                stride[i] = STRIDE;
            }
            if (ctx.overviewHeightsCoarseBatch(blockX, blockZ, w, h, stride, n, terrainY, failMask) != 0
                || failMask[0] != 0) {
                return false;
            }
            if (ctx.surfaceBiomesBatch(
                    sha, blockX, blockZ, w, h, stride, terrainY, n, biomeIds, failMask) != 0
                || failMask[0] != 0) {
                return false;
            }
        }
        return true;
    }
}
