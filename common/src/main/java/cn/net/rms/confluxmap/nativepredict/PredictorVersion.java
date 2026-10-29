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
    public static final String CUBIOMES_COMMIT_12 = "f75f0a360e9b";

    /** Must match {@code CFX_ABI} in {@code native/shim/confluxnative.c}. */
    public static final int CFX_ABI = 14;

    /**
     * Bumped whenever baseline sampling or derivation (LOD expansion, canopy, kind rules) changes.
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
    public static final int BASELINE_ALGO = 21;

    private PredictorVersion() {
    }

    /** Wire/cache format for {@code predictorVersion}, e.g. {@code "cb:ee426009a596|shim:10|base:16"}. */
    public static String full() {
        return "cb:" + CUBIOMES_COMMIT_12 + "|shim:" + CFX_ABI + "|base:" + BASELINE_ALGO;
    }
}
