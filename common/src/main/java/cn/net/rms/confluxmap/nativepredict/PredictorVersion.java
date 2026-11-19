package cn.net.rms.confluxmap.nativepredict;

/**
 * Identity of the whole prediction pipeline, as far as residual coding cares: the vendored
 * cubiomes commit, the C shim's ABI number, and the Java-side baseline derivation revision.
 * Two peers can only diff-code against each other's predictions when all three match exactly
 * (see the M2 determinism spec in the plan) - a mismatch must fall back to absolute mode
 * rather than trust a comparison that might not actually be bit-identical.
 */
public final class PredictorVersion {
    /** First 12 hex characters of the pinned cubiomes commit (see {@code native/CUBIOMES_COMMIT}). */
    public static final String CUBIOMES_COMMIT_12 = "954000e106d1";

    /** Must match {@code CFX_ABI} in {@code native/shim/confluxnative.c}. */
    public static final int CFX_ABI = 17;

    /**
     * Bumped whenever baseline sampling or derivation (LOD expansion, canopy, kind rules) changes.
     * 27: 1.18+ exact terrain columns start the surface search at an iterated slopedCheese fixed
     * point (two noise rounds, parameters shared with the cell solver) instead of the noise-free
     * crossing, and clear a 3-cell band instead of 16. A measured 0.009% of columns (0.0003%
     * water-land flips, concentrated on steep coasts) reads a lower solid segment, within the
     * 1% drift budget for this seam; pre-1.18 output stays bit-identical.
     * 26: merged PR #154/#158 CPU sampling optimizations with the GPU FP32 backend; fused
     * LodSampling changes prediction output.
     * 25: pre-1.18 quart-corner depth-noise solves with memoized biome windows; small measured
     * pixel changes, 1.18+ output unchanged (24 is taken by the CPU macro-grid acceleration).
     * 24: 1.18+ adaptive macro-grid terrain sampling; symmetric waterline; guarded-RLE cache.
     * 23: coarse LODs dropped the 2x2 biome sub-sample sweep; LOD3-4 biomes from the LOD2 lattice.
     * 22: 1.18+ coarse-LOD octave skipping; waterline band resolves after the sub-biome anchors.
     * 21: lazy exact columns, banded surface-search start, memoized voronoi nuclei (value-identical).
     * 20: LOD3-4 sub-biome sweep on an every-second-pixel lattice; uniform cells copy.
     * 19: LOD2-4 overview heights on an every-second-pixel lattice; waterline pixels stay exact.
     * 18: dappled forest and sulfur caves gained real BiomeTable entries (26.2+ worlds).
     */
    public static final int BASELINE_ALGO = 27;

    private PredictorVersion() {
    }

    /** Wire/cache format for {@code predictorVersion}, e.g. {@code "cb:ee426009a596|shim:10|base:16"}. */
    public static String full() {
        return "cb:" + CUBIOMES_COMMIT_12 + "|shim:" + CFX_ABI + "|base:" + BASELINE_ALGO;
    }
}
