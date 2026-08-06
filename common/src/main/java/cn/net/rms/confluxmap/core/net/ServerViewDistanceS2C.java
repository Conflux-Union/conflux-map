package cn.net.rms.confluxmap.core.net;

/** Server-to-client chunk send radius, advertised only to capability-aware clients. */
public record ServerViewDistanceS2C(int chunks) implements Message {
    public ServerViewDistanceS2C {
        if (chunks < 0 || chunks > Proto.MAX_SERVER_VIEW_DISTANCE) {
            throw new IllegalArgumentException("server view distance outside protocol cap: " + chunks);
        }
    }

    public static ServerViewDistanceS2C bounded(final int chunks) {
        return new ServerViewDistanceS2C(Math.max(
            0, Math.min(Proto.MAX_SERVER_VIEW_DISTANCE, chunks)
        ));
    }

    @Override
    public int typeId() {
        return Proto.MSG_SERVER_VIEW_DISTANCE_S2C;
    }
}
