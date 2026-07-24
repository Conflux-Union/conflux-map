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
    public static final String CUBIOMES_COMMIT_12 = "e97dcf959585";

    /** Must match {@code CFX_ABI} in {@code native/shim/confluxnative.c}. */
    public static final int CFX_ABI = 4;

    /** Bumped whenever baseline sampling or derivation (LOD expansion, canopy, kind rules) changes. */
    public static final int BASELINE_ALGO = 10;

    private PredictorVersion() {
    }

    /** Wire/cache format for {@code predictorVersion}, e.g. {@code "cb:e97dcf959585|shim:4|base:10"}. */
    public static String full() {
        return "cb:" + CUBIOMES_COMMIT_12 + "|shim:" + CFX_ABI + "|base:" + BASELINE_ALGO;
    }
}
