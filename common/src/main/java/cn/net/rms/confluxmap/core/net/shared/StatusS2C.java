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
    int maxPlayer,
    boolean ownerManagementAllowed
) implements SharedWaypointMessage {
    public StatusS2C {
        Objects.requireNonNull(worldId, "worldId");
    }

    /** Source-compatible constructor for protocol minors before owner-management policy existed. */
    public StatusS2C(
        final int major,
        final int minor,
        final boolean supported,
        final boolean enabled,
        final boolean operator,
        final String worldId,
        final long revision,
        final int maxWorld,
        final int maxPlayer
    ) {
        this(
            major, minor, supported, enabled, operator, worldId, revision,
            maxWorld, maxPlayer, minor < 2
        );
    }

    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_STATUS_S2C;
    }
}
