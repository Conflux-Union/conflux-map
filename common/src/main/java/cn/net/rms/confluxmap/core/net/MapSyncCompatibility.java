package cn.net.rms.confluxmap.core.net;

/** Backward-compatible capability advertisement and stable map-sync profile selection. */
public final class MapSyncCompatibility {
    public static final int NEGOTIATION_VERSION = 1;
    public static final String STABLE_PREDICTOR = "cb:9afc1038ea5a|shim:9|base:14";
    private static final String ADVERTISEMENT = "|sync:1|wire:4.0|patch:3|region:1";

    public enum ClientMode {
        OPTIMAL_RESIDUAL,
        COMPATIBLE_ABSOLUTE,
        LEGACY_RESIDUAL,
        INCOMPATIBLE,
        SERVER_DISABLED
    }

    public record ClientHello(
        String predictorVersion,
        boolean negotiationSupported,
        boolean supportsCurrentWire
    ) {
    }

    public record ServerSelection(
        boolean correctionsEnabled,
        boolean forceAbsolute,
        boolean sendCompatibility,
        String baselinePredictorVersion
    ) {
    }

    private MapSyncCompatibility() {
    }

    /** Appends optional fields without changing HELLO_C2S's released binary shape. */
    public static String advertise(final String predictorVersion) {
        if (predictorVersion == null || predictorVersion.isEmpty()) {
            throw new IllegalArgumentException("predictor version is empty");
        }
        return predictorVersion + ADVERTISEMENT;
    }

    /** Parses both the released plain predictor and the negotiation-aware extension. */
    public static ClientHello parseClientHello(final String field) {
        if (field == null) {
            return new ClientHello("", false, false);
        }
        final int marker = field.indexOf("|sync:");
        if (marker < 0) {
            return new ClientHello(field, false, false);
        }
        final String predictor = field.substring(0, marker);
        final String suffix = field.substring(marker + 1);
        boolean sync = false;
        boolean wire = false;
        boolean patch = false;
        boolean region = false;
        for (final String token : suffix.split("\\|")) {
            if (token.equals("sync:" + NEGOTIATION_VERSION)) {
                sync = true;
            } else if (token.equals("wire:" + Proto.PROTO_MAJOR + "." + Proto.PROTO_MINOR)) {
                wire = true;
            } else if (token.equals("patch:" + PatchCodec.FORMAT_VERSION)) {
                patch = true;
            } else if (token.equals("region:" + ChunkPatchCodec.FORMAT_VERSION)) {
                region = true;
            }
        }
        return new ClientHello(predictor, sync, sync && wire && patch && region);
    }

    public static boolean isStableLegacyPredictor(final String predictorVersion) {
        return STABLE_PREDICTOR.equals(predictorVersion);
    }

    /** Infers only the one released profile whose policy fingerprint is unambiguous. */
    public static ClientMode fallbackClientMode(
        final boolean correctionsEnabled,
        final boolean chunkRangeCorrectionEnabled
    ) {
        if (!correctionsEnabled) {
            return ClientMode.SERVER_DISABLED;
        }
        return chunkRangeCorrectionEnabled ? ClientMode.LEGACY_RESIDUAL : ClientMode.INCOMPATIBLE;
    }

    /** Keeps a legacy residual only while its released baseline is still locally reproducible. */
    public static ClientMode fallbackClientMode(
        final boolean correctionsEnabled,
        final boolean chunkRangeCorrectionEnabled,
        final String currentPredictorVersion
    ) {
        final ClientMode inferred = fallbackClientMode(
            correctionsEnabled, chunkRangeCorrectionEnabled
        );
        if (inferred != ClientMode.LEGACY_RESIDUAL) {
            return inferred;
        }
        return STABLE_PREDICTOR.equals(currentPredictorVersion)
            ? ClientMode.LEGACY_RESIDUAL : ClientMode.INCOMPATIBLE;
    }

    /** Selects a safe server response without inferring capabilities from an unknown old build. */
    public static ServerSelection selectServer(
        final ClientHello hello,
        final String currentPredictorVersion
    ) {
        if (hello == null || currentPredictorVersion == null) {
            return new ServerSelection(false, false, false, "");
        }
        if (hello.negotiationSupported()) {
            if (!hello.supportsCurrentWire()) {
                return new ServerSelection(false, false, true, "");
            }
            final boolean sameBaseline = currentPredictorVersion.equals(hello.predictorVersion());
            return new ServerSelection(
                true,
                !sameBaseline,
                true,
                sameBaseline ? currentPredictorVersion : ""
            );
        }
        if (isStableLegacyPredictor(hello.predictorVersion())
            && STABLE_PREDICTOR.equals(currentPredictorVersion)) {
            return new ServerSelection(true, false, false, STABLE_PREDICTOR);
        }
        return new ServerSelection(false, false, false, "");
    }
}
