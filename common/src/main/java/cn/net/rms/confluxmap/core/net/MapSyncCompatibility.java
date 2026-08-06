package cn.net.rms.confluxmap.core.net;

/** Backward-compatible capability advertisement and stable map-sync profile selection. */
public final class MapSyncCompatibility {
    public static final int NEGOTIATION_VERSION = 1;
    public static final String STABLE_PREDICTOR = "cb:9afc1038ea5a|shim:9|base:14";
    public static final int LEGACY_PATCH_VERSION = 3;
    public static final int LEGACY_REGION_VERSION = 1;
    private static final String ADVERTISEMENT =
        "|sync:1|wire:4.0|patch:3|region:1|patch:4|region:2|source-light:1";

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
        boolean supportsCurrentWire,
        boolean supportsLegacyWire
    ) {
        public ClientHello(
            final String predictorVersion,
            final boolean negotiationSupported,
            final boolean supportsCurrentWire
        ) {
            this(predictorVersion, negotiationSupported, supportsCurrentWire, supportsCurrentWire);
        }
    }

    public record ServerSelection(
        boolean correctionsEnabled,
        boolean forceAbsolute,
        boolean sendCompatibility,
        String baselinePredictorVersion,
        int patchCodecVersion,
        int regionCodecVersion
    ) {
        public ServerSelection(
            final boolean correctionsEnabled,
            final boolean forceAbsolute,
            final boolean sendCompatibility,
            final String baselinePredictorVersion
        ) {
            this(
                correctionsEnabled, forceAbsolute, sendCompatibility, baselinePredictorVersion,
                FORMAT_PATCH, FORMAT_REGION
            );
        }

        public boolean enhancedProfile() {
            return patchCodecVersion == FORMAT_PATCH && regionCodecVersion == FORMAT_REGION;
        }
    }

    private static final int FORMAT_PATCH = PatchCodec.FORMAT_VERSION;
    private static final int FORMAT_REGION = ChunkPatchCodec.FORMAT_VERSION;

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
            return new ClientHello("", false, false, false);
        }
        final int marker = field.indexOf("|sync:");
        if (marker < 0) {
            return new ClientHello(field, false, false, false);
        }
        final String predictor = field.substring(0, marker);
        final String suffix = field.substring(marker + 1);
        boolean sync = false;
        boolean wire = false;
        boolean patchCurrent = false;
        boolean regionCurrent = false;
        boolean patchLegacy = false;
        boolean regionLegacy = false;
        boolean sourceLight = false;
        for (final String token : suffix.split("\\|")) {
            if (token.equals("sync:" + NEGOTIATION_VERSION)) {
                sync = true;
            } else if (token.equals("wire:" + Proto.PROTO_MAJOR + "." + Proto.PROTO_MINOR)) {
                wire = true;
            } else if (token.equals("patch:" + FORMAT_PATCH)) {
                patchCurrent = true;
            } else if (token.equals("region:" + FORMAT_REGION)) {
                regionCurrent = true;
            } else if (token.equals("patch:" + LEGACY_PATCH_VERSION)) {
                patchLegacy = true;
            } else if (token.equals("region:" + LEGACY_REGION_VERSION)) {
                regionLegacy = true;
            } else if (token.equals("source-light:1")) {
                sourceLight = true;
            }
        }
        return new ClientHello(
            predictor,
            sync,
            sync && wire && patchCurrent && regionCurrent && sourceLight,
            sync && wire && patchLegacy && regionLegacy
        );
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
            return new ServerSelection(false, false, false, "", FORMAT_PATCH, FORMAT_REGION);
        }
        if (hello.negotiationSupported()) {
            final int patchVersion;
            final int regionVersion;
            if (hello.supportsCurrentWire()) {
                patchVersion = FORMAT_PATCH;
                regionVersion = FORMAT_REGION;
            } else if (hello.supportsLegacyWire()) {
                patchVersion = LEGACY_PATCH_VERSION;
                regionVersion = LEGACY_REGION_VERSION;
            } else {
                return new ServerSelection(false, false, true, "", FORMAT_PATCH, FORMAT_REGION);
            }
            final boolean sameBaseline = currentPredictorVersion.equals(hello.predictorVersion());
            return new ServerSelection(
                true,
                !sameBaseline,
                true,
                sameBaseline ? currentPredictorVersion : "",
                patchVersion,
                regionVersion
            );
        }
        if (isStableLegacyPredictor(hello.predictorVersion())
            && STABLE_PREDICTOR.equals(currentPredictorVersion)) {
            return new ServerSelection(
                true, false, false, STABLE_PREDICTOR,
                LEGACY_PATCH_VERSION, LEGACY_REGION_VERSION
            );
        }
        return new ServerSelection(false, false, false, "", FORMAT_PATCH, FORMAT_REGION);
    }

    public static boolean supportedProfile(final int patchVersion, final int regionVersion) {
        return patchVersion == FORMAT_PATCH && regionVersion == FORMAT_REGION
            || patchVersion == LEGACY_PATCH_VERSION && regionVersion == LEGACY_REGION_VERSION;
    }
}
