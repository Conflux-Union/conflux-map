package cn.net.rms.confluxmap.core.net;

/** Released compatibility constants and client-facing map-correction states. */
public final class MapSyncCompatibility {
    public static final int NEGOTIATION_VERSION = 1;
    public static final String STABLE_PREDICTOR = "cb:9afc1038ea5a|shim:9|base:14";

    public enum ClientMode {
        OPTIMAL_RESIDUAL,
        COMPATIBLE_ABSOLUTE,
        LEGACY_RESIDUAL,
        INCOMPATIBLE,
        SERVER_DISABLED
    }

    private MapSyncCompatibility() {
    }
}
