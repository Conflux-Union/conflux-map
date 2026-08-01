package cn.net.rms.confluxmap.core.net.shared;

import java.util.Objects;

/** Server capability and world status returned during the handshake. */
public record StatusS2C(
    int major,
    int minor,
    boolean supported,
    boolean enabled,
    boolean operator,
    String worldId,
    long revision,
    int maxWorld,
    int maxPlayer
) implements SharedWaypointMessage {
    public StatusS2C {
        Objects.requireNonNull(worldId, "worldId");
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_STATUS_S2C;
    }
}
