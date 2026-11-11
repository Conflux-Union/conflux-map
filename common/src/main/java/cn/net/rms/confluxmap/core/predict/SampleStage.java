package cn.net.rms.confluxmap.core.predict;

/**
 * Native sampling stages a tile's composition can spend time in, as seen from the Java
 * sampler seam: one bucket per shim entry-point family, named for what the shim actually does
 * there. {@link SampleStage#H} and {@link SampleStage#B} are the two GPU-attempted hotspots the
 * shim's own stage counters track; the rest are the stages {@code cfxGenerateBiomesStrided} and
 * its siblings run that have no GPU path (or, for the scale-4 row dispatch counted under {@link
 * SampleStage#SCALE}, one the shim's packed counter now covers too).
 */
enum SampleStage {
    /** Full-detail overview height solve ({@code cfxOverviewHeights}). */
    H,
    /** Anchor-column lattice sweeps ({@code cfxOverviewHeightsCoarse}, LOD2-4). */
    ANCH,
    /** Surface biome solve ({@code cfxSurfaceBiomes}). */
    B,
    /** Scale-layer biome generation ({@code cfxGenerateBiomesStrided} family, incl. Nether 3D biomes). */
    SCALE,
    /** Exact surface-column residuals ({@code cfxSurfaceColumns}). */
    COL,
    /** End height solve ({@code cfxEndHeights} family). */
    END,
    /** Canopy tree candidates ({@code cfxTreeCandidates}). */
    TREE,
    /** Legacy pre-1.18 height solve ({@code cfxHeightsStrided}/{@code mapApproxHeight}). */
    LEG;

    /** Number of buckets; {@code long[]} stage arrays are indexed by {@link #ordinal()}. */
    static final int COUNT = values().length;

    /** Sum of a per-tile stage array (null-safe: a non-counting composition passes null). */
    static long total(final long[] stageNanos) {
        if (stageNanos == null) {
            return 0L;
        }
        long total = 0L;
        for (final long nanos : stageNanos) {
            total += nanos;
        }
        return total;
    }
}
