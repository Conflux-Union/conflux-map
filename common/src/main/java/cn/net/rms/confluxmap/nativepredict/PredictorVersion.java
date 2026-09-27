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
    public static final int CFX_ABI = 12;

    /**
     * Bumped whenever baseline sampling or derivation (LOD expansion, canopy, kind rules) changes.
     * 18: dappled forest and sulfur caves gained real BiomeTable entries instead of the neutral
     * land fallback, changing predicted pixels for 26.2+ worlds.
     */
    public static final int BASELINE_ALGO = 18;

    private PredictorVersion() {
    }

    /** Wire/cache format for {@code predictorVersion}, e.g. {@code "cb:ee426009a596|shim:10|base:16"}. */
    public static String full() {
        return "cb:" + CUBIOMES_COMMIT_12 + "|shim:" + CFX_ABI + "|base:" + BASELINE_ALGO;
    }
}
