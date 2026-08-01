package cn.net.rms.confluxmap.core.net;

/** Explicit map-sync profile selected before HELLO_POLICY for negotiation-aware clients. */
public record MapCompatibilityS2C(
    int negotiationVersion,
    String serverModVersion,
    int protocolMajor,
    int protocolMinor,
    int patchCodecVersion,
    int regionCodecVersion,
    String serverPredictorVersion,
    int correctionMode,
    int reasonCode
) implements Message {
    public static final int MODE_RESIDUAL = 0;
    public static final int MODE_ABSOLUTE = 1;
    public static final int MODE_DISABLED = 2;

    public static final int REASON_NONE = 0;
    public static final int REASON_BASELINE_MISMATCH = 1;
    public static final int REASON_NO_COMMON_WIRE = 2;

    @Override
    public int typeId() {
        return Proto.MSG_MAP_COMPATIBILITY_S2C;
    }
}
