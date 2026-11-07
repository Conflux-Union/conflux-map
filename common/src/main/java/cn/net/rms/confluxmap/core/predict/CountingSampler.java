package cn.net.rms.confluxmap.core.predict;

/**
 * Debug-only point-counting and stage-timing front over any {@link BaselineSampler}: every
 * query rectangle adds its {@code w*h} output cells to {@link #points()}, and its wall time to
 * the {@link SampleStage} bucket the shim entry behind it belongs to, so a tile's log line
 * carries the sampled workload and the per-stage generation split next to its wall time.
 * Delegates every method - including the interface's own defaults - straight to the wrapped
 * sampler, so default implementations run on the delegate with their internal calls bypassing
 * this wrapper and each query counts exactly once.
 *
 * <p>Not thread-safe by design: one wrapper per composition, used by one worker. Kept in sync
 * with {@link BaselineSampler} by the compiler - a new interface method without a counting
 * override here still works, but its points go uncounted and its time lands in no stage bucket
 * until an override is added.
 */
final class CountingSampler implements BaselineSampler {
    private final BaselineSampler delegate;
    private long points;
    private final long[] stageNanos = new long[SampleStage.COUNT];

    CountingSampler(final BaselineSampler delegate) {
        this.delegate = delegate;
    }

    /** Sampled output cells summed over every query rectangle this sampler was asked for. */
    long points() {
        return points;
    }

    /**
     * Wall nanos per {@link SampleStage} bucket, indexed by ordinal. The array is live state
     * while sampling runs; compositions hand it downstream only after their last sampler call,
     * so no copy is needed.
     */
    long[] stageNanos() {
        return stageNanos;
    }

    @Override
    public int terrainFreeBiomeScale() {
        return delegate.terrainFreeBiomeScale();
    }

    @Override
    public boolean interpolatableOverviewHeights() {
        return delegate.interpolatableOverviewHeights();
    }

    @Override
    public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.biomes(scale, x, z, w, h, out);
        stageNanos[SampleStage.SCALE.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean biomesStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.biomesStrided(scale, x, z, w, h, stride, out);
        stageNanos[SampleStage.SCALE.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean biomesSparseStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride, final int[] out
    ) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.biomesSparseStrided(scale, x, z, w, h, stride, out);
        stageNanos[SampleStage.SCALE.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean biomesScanStrided(
        final int scale, final int x, final int z, final int w, final int h, final int stride,
        final int targetBiomeId, final int[] out
    ) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.biomesScanStrided(scale, x, z, w, h, stride, targetBiomeId, out);
        stageNanos[SampleStage.SCALE.ordinal()] += System.nanoTime() - start;
        return ok;
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
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.biomesAtYStrided(blockY, scale, x, z, w, h, stride, out);
        stageNanos[SampleStage.SCALE.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.heights(x4, z4, w, h, outY);
        stageNanos[SampleStage.LEG.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean heightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.heightsStrided(x4, z4, w, h, stride, outY);
        stageNanos[SampleStage.LEG.ordinal()] += System.nanoTime() - start;
        return ok;
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
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.surfaceColumns(
            blockX, blockZ, w, h, stride, outSolidY, outFluidY, outSurfaceY, outFlags
        );
        stageNanos[SampleStage.COL.ordinal()] += System.nanoTime() - start;
        return ok;
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
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.overviewHeights(blockX, blockZ, w, h, stride, outTerrainY);
        stageNanos[SampleStage.H.ordinal()] += System.nanoTime() - start;
        return ok;
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
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.overviewHeightsCoarse(blockX, blockZ, w, h, stride, outTerrainY);
        stageNanos[SampleStage.ANCH.ordinal()] += System.nanoTime() - start;
        return ok;
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
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.surfaceBiomes(blockX, blockZ, w, h, stride, terrainY, outBiomeIds);
        stageNanos[SampleStage.B.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.endHeights(x4, z4, w, h, outY);
        stageNanos[SampleStage.END.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public boolean endHeightsStrided(
        final int x4, final int z4, final int w, final int h, final int stride, final int[] outY
    ) {
        points += (long) w * h;
        final long start = System.nanoTime();
        final boolean ok = delegate.endHeightsStrided(x4, z4, w, h, stride, outY);
        stageNanos[SampleStage.END.ordinal()] += System.nanoTime() - start;
        return ok;
    }

    @Override
    public int treeCandidates(final int chunkX, final int chunkZ, final TreeCandidate[] out) {
        final long start = System.nanoTime();
        final int count = delegate.treeCandidates(chunkX, chunkZ, out);
        stageNanos[SampleStage.TREE.ordinal()] += System.nanoTime() - start;
        return count;
    }
}
