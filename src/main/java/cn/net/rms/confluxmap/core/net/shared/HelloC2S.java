package cn.net.rms.confluxmap.core.net.shared;

/** Client protocol-version announcement. */
public record HelloC2S(int major, int minor) implements SharedWaypointMessage {
    @Override
    public int typeId() {
        return SharedWaypointProto.MSG_HELLO_C2S;
    }
}
