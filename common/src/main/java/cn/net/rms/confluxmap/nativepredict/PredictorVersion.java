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
     * point (two noise rounds, column parameters shared with the cell solver) instead of the
     * noise-free crossing, and clear a 3-cell band instead of 16; the noodle selector-first
     * sampling and the noodle lower-bound shortcut are value-identical. A measured 0.009% of
     * columns (0.0003% water-land flips, concentrated on steep coasts) now reads a lower solid
     * segment instead of the first one, against the 1% drift budget for this seam. Pre-1.18
     * output stays bit-identical.
     * 26: merged PR #154/#158 CPU sampling optimizations with the GPU FP32 backend; fused
     * LodSampling changes prediction output.
     * 25: pre-1.18 overview heights solve the depth noise once per world-aligned quart corner
     * (vanilla itself evaluates it per quart column) and bilinearly interpolate the
     * biome-independent base per pixel, skipping depth-noise octaves shorter than half the
     * sampling stride on coarse windows; scale-4 biome generation runs densely once per window
     * and the overview-sized biome windows are memoized across the overview and surface passes,
     * so pre-1.18 predicted pixels change by small measured amounts while 1.18+ output stays
     * unchanged (24 is taken by the CPU macro-grid acceleration).
     * 24: 1.18+ adaptive macro-grid terrain sampling and symmetric waterline reconstruction;
     * baseline cache with zero-allocation guarded RLE compression and viewport-aware dynamic capacity.
     * 23: the coarse LODs dropped the 2x2 biome sub-sample sweep; LOD3-4 Overworld biomes are
     * now answered by the LOD2 per-pixel anchor-lattice cell sweep at the coarse stride (the
     * LOD2-4 overview lattice itself is unchanged, and Nether roof and End were never
     * sub-sampled), so LOD3-4 predicted pixels change and the per-pixel sub-sample planes are
     * gone.
     * 22: 1.18+ LOD2-4 coarse-LOD sampling: anchor-lattice overview solves skip terrain-noise
     * octaves shorter than half the anchor stride and the jaggedness term where the jaggedness
     * spline is zero; the waterline band resolves after the sub-biome anchors and leaves
     * inland mid-band quads (no water-biome anchor nearby, anchors all above sea level +2)
     * at their reconstruction; LOD2 per-pixel biomes moved onto the anchor-lattice copy/refine
     * sweep, and cells whose corners are all water biomes with all-deep pixels copy without
     * refining. LOD0-1 and pre-1.18 output stay bit-identical.
     * 21: 1.18+ exact terrain columns are solved lazily (cells below the deepest reader are
     * never computed - value-identical), the surface search starts one band above the noise-free
     * density-zero crossing instead of at the build limit (a solid floating above crossing+band
     * could in principle shift a rare column's answer; the visual corpus measures the tile
     * output unchanged), and fine-stride surface-biome sweeps memoize voronoi nuclei per call
     * (value-identical).
     * 20: the 1.18+ LOD3-4 sub-biome sweep is solved on an every-second-pixel anchor lattice;
     * biome-uniform cells whose pixels all sit outside the biome-decides-fluid band copy their
     * corner biome instead of sampling, every other cell samples exactly.
     * 19: 1.18+ LOD2-4 overview heights are solved on an every-second-pixel anchor lattice and
     * bilinearly reconstructed (waterline-adjacent pixels keep exact solves), instead of one
     * solve per output position; the supersampled LOD3-4 biome sweep became the pixel biome's
     * source, so the separate centre pass no longer runs. Pre-1.18 output is unchanged.
     * 18: dappled forest and sulfur caves gained real BiomeTable entries instead of the neutral
     * land fallback, changing predicted pixels for 26.2+ worlds.
     */
    public static final int BASELINE_ALGO = 27;

    private PredictorVersion() {
    }

    /** Wire/cache format for {@code predictorVersion}, e.g. {@code "cb:ee426009a596|shim:10|base:16"}. */
    public static String full() {
        return "cb:" + CUBIOMES_COMMIT_12 + "|shim:" + CFX_ABI + "|base:" + BASELINE_ALGO;
    }
}
