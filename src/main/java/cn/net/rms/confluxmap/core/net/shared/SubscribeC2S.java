package cn.net.rms.confluxmap.core.net.shared;

/** Requests the current snapshot and subsequent shared-waypoint updates. */
public record SubscribeC2S() implements SharedWaypointMessage {
    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_SUBSCRIBE_C2S;
    }
}
