package cn.net.rms.confluxmap.core.net;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/** Immutable per-connection map-sync contract used by callers after negotiation. */
public final class NegotiatedMapSync {
    public enum Endpoint { CLIENT, SERVER }
    public enum CorrectionMode { RESIDUAL, ABSOLUTE, DISABLED }

    private final Endpoint endpoint;
    private final CorrectionProfile correctionProfile;
    private final CorrectionMode correctionMode;
    private final String baselinePredictorVersion;
    private final Map<MapSyncCapability, Integer> capabilities;

    private NegotiatedMapSync(
        final Endpoint endpoint,
        final CorrectionProfile correctionProfile,
        final CorrectionMode correctionMode,
        final String baselinePredictorVersion,
        final Map<MapSyncCapability, Integer> capabilities
    ) {
        this.endpoint = Objects.requireNonNull(endpoint, "endpoint");
        this.correctionProfile = Objects.requireNonNull(
            correctionProfile, "correctionProfile"
        );
        this.correctionMode = Objects.requireNonNull(correctionMode, "correctionMode");
        this.baselinePredictorVersion = baselinePredictorVersion == null
            ? "" : baselinePredictorVersion;
        final EnumMap<MapSyncCapability, Integer> copy =
            new EnumMap<>(MapSyncCapability.class);
        copy.putAll(Objects.requireNonNull(capabilities, "capabilities"));
        this.capabilities = Map.copyOf(copy);
    }

    public static NegotiatedMapSync server(
        final CorrectionProfile profile,
        final CorrectionMode mode,
        final String baselinePredictorVersion,
        final Map<MapSyncCapability, Integer> capabilities
    ) {
        return new NegotiatedMapSync(
            Endpoint.SERVER, profile, mode, baselinePredictorVersion, capabilities
        );
    }

    public static NegotiatedMapSync client(
        final CorrectionProfile profile,
        final CorrectionMode mode,
        final String baselinePredictorVersion,
        final Map<MapSyncCapability, Integer> capabilities
    ) {
        return new NegotiatedMapSync(
            Endpoint.CLIENT, profile, mode, baselinePredictorVersion, capabilities
        );
    }

    public CorrectionProfile correctionProfile() {
        return correctionProfile;
    }

    public CorrectionMode correctionMode() {
        return correctionMode;
    }

    public boolean correctionsEnabled() {
        return correctionMode != CorrectionMode.DISABLED;
    }

    public boolean forceAbsolute() {
        return correctionMode == CorrectionMode.ABSOLUTE;
    }

    public String baselinePredictorVersion() {
        return baselinePredictorVersion;
    }

    public boolean supports(final MapSyncCapability capability) {
        return capabilities.containsKey(capability);
    }

    public int capabilityVersion(final MapSyncCapability capability) {
        return capabilities.getOrDefault(capability, 0);
    }

    public Map<MapSyncCapability, Integer> capabilities() {
        return capabilities;
    }

    public byte[] encodeOutbound(final Message message) throws ProtoException {
        final boolean clientbound = MapSyncProtocol.isClientbound(message.typeId());
        if ((endpoint == Endpoint.SERVER && !clientbound)
            || (endpoint == Endpoint.CLIENT && clientbound)) {
            throw new ProtoException("message direction does not match negotiated endpoint");
        }
        requireCapability(message.typeId());
        return MsgCodec.encode(correctionProfile.prepareOutbound(message));
    }

    public Message decodeInbound(final byte[] payload) throws ProtoException {
        final Message message = MsgCodec.decode(payload);
        final boolean clientbound = MapSyncProtocol.isClientbound(message.typeId());
        if ((endpoint == Endpoint.SERVER && clientbound)
            || (endpoint == Endpoint.CLIENT && !clientbound)) {
            throw new ProtoException("message direction does not match negotiated endpoint");
        }
        requireCapability(message.typeId());
        return message;
    }

    private void requireCapability(final int typeId) throws ProtoException {
        final MapSyncCapability required = switch (typeId) {
            case Proto.MSG_FLAT_BASELINE_S2C -> MapSyncCapability.FLAT_BASELINE;
            case Proto.MSG_LOAD_STATE_SUBSCRIBE_C2S, Proto.MSG_LOAD_STATE_DELTA_S2C ->
                MapSyncCapability.LOAD_STATE;
            case Proto.MSG_MAP_SYNC_SUBSCRIBE_C2S, Proto.MSG_MAP_INVALIDATE_S2C ->
                MapSyncCapability.MAP_INVALIDATION;
            case Proto.MSG_MAP_REGION_VIEW_REQ_C2S, Proto.MSG_MAP_REGION_PATCH_S2C ->
                MapSyncCapability.REGION_CORRECTION;
            case Proto.MSG_MAP_REGION_SYNC_SUBSCRIBE_C2S,
                 Proto.MSG_MAP_REGION_INVALIDATE_S2C -> MapSyncCapability.REGION_INVALIDATION;
            case Proto.MSG_SERVER_VIEW_DISTANCE_S2C -> MapSyncCapability.SERVER_VIEW_DISTANCE;
            default -> null;
        };
        if (required != null && !supports(required)) {
            throw new ProtoException(
                "message requires unselected capability " + required.name()
            );
        }
    }
}
