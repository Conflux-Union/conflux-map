package cn.net.rms.confluxmap.core.net;

import java.util.List;

/** Extensible profile and capability selection sent only to clients advertising caps2. */
public record MapCapabilitiesS2C(
    int negotiationVersion,
    String serverModVersion,
    String serverPredictorVersion,
    int correctionMode,
    int reasonCode,
    int correctionProfileId,
    List<Entry> capabilities
) implements Message {
    public record Entry(int capabilityId, int version) {
    }

    public MapCapabilitiesS2C {
        capabilities = List.copyOf(capabilities);
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_CAPABILITIES_S2C;
    }
}
