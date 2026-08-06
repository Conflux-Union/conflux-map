package cn.net.rms.confluxmap.core.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Negotiates one reusable map-sync contract while isolating released legacy wire shapes. */
public final class MapSyncProtocol {
    public static final int NEGOTIATION_VERSION = 2;
    public static final int MAX_CORRECTION_PROFILES = 8;
    public static final int MAX_CAPABILITIES = 32;
    private static final String CAPS_MARKER = "|caps2:";
    private static final String LEGACY_ADVERTISEMENT =
        "|sync:1|wire:4.0|patch:3|region:1|patch:4|region:2|source-light:1";

    public record ServerHandshake(NegotiatedMapSync session, Message selection) {
    }

    private record Offer(
        String predictorVersion,
        List<CorrectionProfile> correctionProfiles,
        Map<MapSyncCapability, Integer> capabilities,
        boolean caps2
    ) {
    }

    private record LegacyOffer(
        String predictorVersion,
        boolean negotiationSupported,
        boolean supportsCurrentProfile,
        boolean supportsLegacyProfile
    ) {
    }

    private MapSyncProtocol() {
    }

    /** Builds an unchanged HELLO frame whose predictor field carries optional negotiation data. */
    public static HelloC2S clientHello(final String modVersion, final String predictorVersion) {
        if (predictorVersion == null || predictorVersion.isEmpty()) {
            throw new IllegalArgumentException("predictor version is empty");
        }
        return new HelloC2S(
            modVersion,
            predictorVersion + LEGACY_ADVERTISEMENT + CAPS_MARKER + encodeOffer()
        );
    }

    public static ServerHandshake acceptClient(
        final HelloC2S hello,
        final String serverModVersion,
        final String serverPredictorVersion
    ) {
        if (hello == null || serverPredictorVersion == null) {
            return disabledServerHandshake();
        }
        final Offer offer = parseOffer(hello.predictorVersion());
        if (offer.caps2()) {
            final CorrectionProfile profile = highestCommon(offer.correctionProfiles());
            if (profile == null) {
                final NegotiatedMapSync session = NegotiatedMapSync.server(
                    CorrectionProfile.SOURCE_LIGHT_V2,
                    NegotiatedMapSync.CorrectionMode.DISABLED,
                    "",
                    commonCapabilities(offer.capabilities())
                );
                return new ServerHandshake(
                    session,
                    capabilitiesSelection(serverModVersion, serverPredictorVersion, session)
                );
            }
            final boolean sameBaseline = serverPredictorVersion.equals(offer.predictorVersion());
            final NegotiatedMapSync session = NegotiatedMapSync.server(
                profile,
                sameBaseline
                    ? NegotiatedMapSync.CorrectionMode.RESIDUAL
                    : NegotiatedMapSync.CorrectionMode.ABSOLUTE,
                sameBaseline ? serverPredictorVersion : "",
                commonCapabilities(offer.capabilities())
            );
            return new ServerHandshake(
                session,
                capabilitiesSelection(serverModVersion, serverPredictorVersion, session)
            );
        }

        final LegacyOffer legacy = parseLegacyOffer(hello.predictorVersion());
        if (legacy.negotiationSupported()) {
            final CorrectionProfile profile = legacy.supportsCurrentProfile()
                ? CorrectionProfile.SOURCE_LIGHT_V2
                : legacy.supportsLegacyProfile() ? CorrectionProfile.LEGACY_V1 : null;
            final boolean sameBaseline = serverPredictorVersion.equals(
                legacy.predictorVersion()
            );
            final NegotiatedMapSync session = NegotiatedMapSync.server(
                profile == null ? CorrectionProfile.SOURCE_LIGHT_V2 : profile,
                profile == null
                    ? NegotiatedMapSync.CorrectionMode.DISABLED
                    : sameBaseline
                        ? NegotiatedMapSync.CorrectionMode.RESIDUAL
                        : NegotiatedMapSync.CorrectionMode.ABSOLUTE,
                profile != null && sameBaseline ? serverPredictorVersion : "",
                MapSyncCapability.all()
            );
            return new ServerHandshake(
                session, legacySelection(serverModVersion, serverPredictorVersion, session)
            );
        }
        final boolean stableLegacy = MapSyncCompatibility.STABLE_PREDICTOR.equals(
            legacy.predictorVersion()
        ) && MapSyncCompatibility.STABLE_PREDICTOR.equals(serverPredictorVersion);
        return new ServerHandshake(
            NegotiatedMapSync.server(
                stableLegacy ? CorrectionProfile.LEGACY_V1 : CorrectionProfile.SOURCE_LIGHT_V2,
                stableLegacy
                    ? NegotiatedMapSync.CorrectionMode.RESIDUAL
                    : NegotiatedMapSync.CorrectionMode.DISABLED,
                stableLegacy ? MapSyncCompatibility.STABLE_PREDICTOR : "",
                MapSyncCapability.all()
            ),
            null
        );
    }

    /** Resolves a server selection and policy into the client's one immutable session contract. */
    public static NegotiatedMapSync acceptServer(
        final Message selection,
        final HelloPolicyS2C policy,
        final String clientPredictorVersion
    ) {
        if (selection instanceof final MapCapabilitiesS2C current) {
            final boolean compatibleEnvelope =
                current.negotiationVersion() == NEGOTIATION_VERSION;
            final CorrectionProfile profile = compatibleEnvelope
                ? CorrectionProfile.fromId(current.correctionProfileId()) : null;
            final Map<MapSyncCapability, Integer> capabilities = compatibleEnvelope
                ? decodeCapabilities(current.capabilities()) : Map.of();
            final boolean validResidual =
                current.correctionMode() != MapCompatibilityS2C.MODE_RESIDUAL
                || clientPredictorVersion != null
                    && clientPredictorVersion.equals(current.serverPredictorVersion());
            return NegotiatedMapSync.client(
                profile == null ? CorrectionProfile.SOURCE_LIGHT_V2 : profile,
                profile == null || !validResidual
                    ? NegotiatedMapSync.CorrectionMode.DISABLED
                    : selectedMode(current.correctionMode(), policy.flags().correctionsEnabled()),
                validResidual && current.correctionMode() == MapCompatibilityS2C.MODE_RESIDUAL
                    ? current.serverPredictorVersion() : "",
                capabilities
            );
        }
        if (selection instanceof final MapCompatibilityS2C legacy) {
            final boolean compatibleEnvelope =
                legacy.negotiationVersion() == MapSyncCompatibility.NEGOTIATION_VERSION
                && legacy.protocolMajor() == Proto.PROTO_MAJOR
                && legacy.protocolMinor() == Proto.PROTO_MINOR;
            final CorrectionProfile profile = compatibleEnvelope
                ? CorrectionProfile.fromCodecVersions(
                    legacy.patchCodecVersion(), legacy.regionCodecVersion()
                )
                : null;
            final boolean predictorMatches = clientPredictorVersion != null
                && clientPredictorVersion.equals(legacy.serverPredictorVersion());
            final boolean validResidual = legacy.correctionMode() != MapCompatibilityS2C.MODE_RESIDUAL
                || predictorMatches;
            return NegotiatedMapSync.client(
                profile == null ? CorrectionProfile.SOURCE_LIGHT_V2 : profile,
                profile == null || !validResidual
                    ? NegotiatedMapSync.CorrectionMode.DISABLED
                    : selectedMode(legacy.correctionMode(), policy.flags().correctionsEnabled()),
                validResidual && legacy.correctionMode() == MapCompatibilityS2C.MODE_RESIDUAL
                    ? legacy.serverPredictorVersion() : "",
                MapSyncCapability.all()
            );
        }
        final boolean released = policy.flags().correctionsEnabled()
            && policy.flags().chunkRangeCorrectionEnabled()
            && MapSyncCompatibility.STABLE_PREDICTOR.equals(clientPredictorVersion);
        return NegotiatedMapSync.client(
            CorrectionProfile.LEGACY_V1,
            !policy.flags().correctionsEnabled()
                ? NegotiatedMapSync.CorrectionMode.DISABLED
                : released
                    ? NegotiatedMapSync.CorrectionMode.RESIDUAL
                    : NegotiatedMapSync.CorrectionMode.DISABLED,
            released ? MapSyncCompatibility.STABLE_PREDICTOR : "",
            MapSyncCapability.all()
        );
    }

    public static boolean isClientbound(final int typeId) {
        return typeId == Proto.MSG_HELLO_POLICY_S2C
            || typeId == Proto.MSG_MAP_PATCH_S2C
            || typeId == Proto.MSG_POLICY_UPDATE_S2C
            || typeId == Proto.MSG_ERROR_S2C
            || typeId == Proto.MSG_FLAT_BASELINE_S2C
            || typeId == Proto.MSG_LOAD_STATE_DELTA_S2C
            || typeId == Proto.MSG_MAP_INVALIDATE_S2C
            || typeId == Proto.MSG_MAP_REGION_PATCH_S2C
            || typeId == Proto.MSG_MAP_REGION_INVALIDATE_S2C
            || typeId == Proto.MSG_MAP_COMPATIBILITY_S2C
            || typeId == Proto.MSG_MAP_CAPABILITIES_S2C;
    }

    public static Message decodeServerbound(final byte[] payload) throws ProtoException {
        final Message message = MsgCodec.decode(payload);
        if (isClientbound(message.typeId())) {
            throw new ProtoException("clientbound message received by server");
        }
        return message;
    }

    public static Message decodeClientbound(final byte[] payload) throws ProtoException {
        final Message message = MsgCodec.decode(payload);
        if (!isClientbound(message.typeId())) {
            throw new ProtoException("serverbound message received by client");
        }
        return message;
    }

    private static String encodeOffer() {
        try {
            final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            final DataOutputStream out = new DataOutputStream(bytes);
            out.writeByte(NEGOTIATION_VERSION);
            final CorrectionProfile[] profiles = CorrectionProfile.values();
            out.writeByte(profiles.length);
            for (int i = profiles.length - 1; i >= 0; i--) {
                out.writeByte(profiles[i].id());
            }
            final Map<MapSyncCapability, Integer> capabilities = MapSyncCapability.all();
            out.writeByte(capabilities.size());
            for (final MapSyncCapability capability : MapSyncCapability.values()) {
                out.writeByte(capability.id());
                out.writeByte(capabilities.get(capability));
            }
            out.flush();
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray());
        } catch (final IOException e) {
            throw new IllegalStateException("in-memory capability offer failed", e);
        }
    }

    private static Offer parseOffer(final String field) {
        final LegacyOffer legacy = parseLegacyOffer(field);
        final int marker = field == null ? -1 : field.indexOf(CAPS_MARKER);
        if (marker < 0) {
            return new Offer(legacy.predictorVersion(), List.of(), Map.of(), false);
        }
        final int start = marker + CAPS_MARKER.length();
        final int endMarker = field.indexOf('|', start);
        final String encoded = field.substring(start, endMarker < 0 ? field.length() : endMarker);
        try {
            final byte[] bytes = Base64.getUrlDecoder().decode(encoded);
            final DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readUnsignedByte() != NEGOTIATION_VERSION) {
                return new Offer(legacy.predictorVersion(), List.of(), Map.of(), true);
            }
            final int profileCount = in.readUnsignedByte();
            if (profileCount < 1 || profileCount > MAX_CORRECTION_PROFILES) {
                return new Offer(legacy.predictorVersion(), List.of(), Map.of(), true);
            }
            final List<CorrectionProfile> profiles = new ArrayList<>(profileCount);
            for (int i = 0; i < profileCount; i++) {
                final CorrectionProfile profile = CorrectionProfile.fromId(in.readUnsignedByte());
                if (profile != null && !profiles.contains(profile)) {
                    profiles.add(profile);
                }
            }
            final int capabilityCount = in.readUnsignedByte();
            if (capabilityCount > MAX_CAPABILITIES) {
                return new Offer(legacy.predictorVersion(), List.of(), Map.of(), true);
            }
            final EnumMap<MapSyncCapability, Integer> capabilities =
                new EnumMap<>(MapSyncCapability.class);
            for (int i = 0; i < capabilityCount; i++) {
                final MapSyncCapability capability =
                    MapSyncCapability.fromId(in.readUnsignedByte());
                final int version = in.readUnsignedByte();
                if (capability != null && version > 0) {
                    capabilities.merge(capability, version, Math::max);
                }
            }
            if (in.available() != 0 || profiles.isEmpty()) {
                return new Offer(legacy.predictorVersion(), List.of(), Map.of(), true);
            }
            return new Offer(legacy.predictorVersion(), profiles, capabilities, true);
        } catch (final IllegalArgumentException | IOException e) {
            return new Offer(legacy.predictorVersion(), List.of(), Map.of(), true);
        }
    }

    private static LegacyOffer parseLegacyOffer(final String field) {
        if (field == null) {
            return new LegacyOffer("", false, false, false);
        }
        final int marker = field.indexOf("|sync:");
        if (marker < 0) {
            return new LegacyOffer(field, false, false, false);
        }
        final String predictor = field.substring(0, marker);
        boolean sync = false;
        boolean wire = false;
        boolean patchCurrent = false;
        boolean regionCurrent = false;
        boolean patchLegacy = false;
        boolean regionLegacy = false;
        boolean sourceLight = false;
        for (final String token : field.substring(marker + 1).split("\\|")) {
            if (token.equals("sync:" + MapSyncCompatibility.NEGOTIATION_VERSION)) {
                sync = true;
            } else if (token.equals("wire:" + Proto.PROTO_MAJOR + "." + Proto.PROTO_MINOR)) {
                wire = true;
            } else if (token.equals("patch:" + PatchCodec.FORMAT_VERSION)) {
                patchCurrent = true;
            } else if (token.equals("region:" + ChunkPatchCodec.FORMAT_VERSION)) {
                regionCurrent = true;
            } else if (token.equals("patch:" + PatchCodec.LEGACY_FORMAT_VERSION)) {
                patchLegacy = true;
            } else if (token.equals("region:" + ChunkPatchCodec.LEGACY_FORMAT_VERSION)) {
                regionLegacy = true;
            } else if (token.equals("source-light:1")) {
                sourceLight = true;
            }
        }
        return new LegacyOffer(
            predictor,
            sync,
            sync && wire && patchCurrent && regionCurrent && sourceLight,
            sync && wire && patchLegacy && regionLegacy
        );
    }

    private static CorrectionProfile highestCommon(final List<CorrectionProfile> offered) {
        for (int i = CorrectionProfile.values().length - 1; i >= 0; i--) {
            final CorrectionProfile candidate = CorrectionProfile.values()[i];
            if (offered.contains(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static Map<MapSyncCapability, Integer> commonCapabilities(
        final Map<MapSyncCapability, Integer> offered
    ) {
        final EnumMap<MapSyncCapability, Integer> selected =
            new EnumMap<>(MapSyncCapability.class);
        for (final MapSyncCapability capability : MapSyncCapability.values()) {
            final int peerVersion = offered.getOrDefault(capability, 0);
            if (peerVersion > 0) {
                selected.put(capability, Math.min(peerVersion, capability.version()));
            }
        }
        return selected;
    }

    private static MapCapabilitiesS2C capabilitiesSelection(
        final String serverModVersion,
        final String serverPredictorVersion,
        final NegotiatedMapSync session
    ) {
        final List<MapCapabilitiesS2C.Entry> entries = new ArrayList<>();
        for (final MapSyncCapability capability : MapSyncCapability.values()) {
            final int version = session.capabilityVersion(capability);
            if (version > 0) {
                entries.add(new MapCapabilitiesS2C.Entry(capability.id(), version));
            }
        }
        return new MapCapabilitiesS2C(
            NEGOTIATION_VERSION,
            serverModVersion,
            serverPredictorVersion,
            wireMode(session.correctionMode()),
            reason(session.correctionMode()),
            session.correctionProfile().id(),
            entries
        );
    }

    private static MapCompatibilityS2C legacySelection(
        final String serverModVersion,
        final String serverPredictorVersion,
        final NegotiatedMapSync session
    ) {
        return new MapCompatibilityS2C(
            MapSyncCompatibility.NEGOTIATION_VERSION,
            serverModVersion,
            Proto.PROTO_MAJOR,
            Proto.PROTO_MINOR,
            session.correctionProfile().patchCodecVersion(),
            session.correctionProfile().regionCodecVersion(),
            serverPredictorVersion,
            wireMode(session.correctionMode()),
            reason(session.correctionMode())
        );
    }

    private static Map<MapSyncCapability, Integer> decodeCapabilities(
        final List<MapCapabilitiesS2C.Entry> entries
    ) {
        final EnumMap<MapSyncCapability, Integer> capabilities =
            new EnumMap<>(MapSyncCapability.class);
        for (final MapCapabilitiesS2C.Entry entry : entries) {
            final MapSyncCapability capability = MapSyncCapability.fromId(entry.capabilityId());
            if (capability != null && entry.version() > 0
                && entry.version() <= capability.version()) {
                capabilities.put(capability, entry.version());
            }
        }
        return capabilities;
    }

    private static NegotiatedMapSync.CorrectionMode selectedMode(
        final int wireMode,
        final boolean policyEnabled
    ) {
        if (!policyEnabled || wireMode == MapCompatibilityS2C.MODE_DISABLED) {
            return NegotiatedMapSync.CorrectionMode.DISABLED;
        }
        return wireMode == MapCompatibilityS2C.MODE_RESIDUAL
            ? NegotiatedMapSync.CorrectionMode.RESIDUAL
            : NegotiatedMapSync.CorrectionMode.ABSOLUTE;
    }

    private static int wireMode(final NegotiatedMapSync.CorrectionMode mode) {
        return switch (mode) {
            case RESIDUAL -> MapCompatibilityS2C.MODE_RESIDUAL;
            case ABSOLUTE -> MapCompatibilityS2C.MODE_ABSOLUTE;
            case DISABLED -> MapCompatibilityS2C.MODE_DISABLED;
        };
    }

    private static int reason(final NegotiatedMapSync.CorrectionMode mode) {
        return switch (mode) {
            case RESIDUAL -> MapCompatibilityS2C.REASON_NONE;
            case ABSOLUTE -> MapCompatibilityS2C.REASON_BASELINE_MISMATCH;
            case DISABLED -> MapCompatibilityS2C.REASON_NO_COMMON_WIRE;
        };
    }

    private static ServerHandshake disabledServerHandshake() {
        return new ServerHandshake(
            NegotiatedMapSync.server(
                CorrectionProfile.SOURCE_LIGHT_V2,
                NegotiatedMapSync.CorrectionMode.DISABLED,
                "",
                Map.of()
            ),
            null
        );
    }
}
