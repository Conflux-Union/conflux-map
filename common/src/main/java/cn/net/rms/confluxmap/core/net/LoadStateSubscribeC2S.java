package cn.net.rms.confluxmap.core.net;

/** C2S activation, viewport replacement, or cancellation for the load-state overlay. */
public record LoadStateSubscribeC2S(
    int subscriptionId,
    int dimIndex,
    boolean active,
    int minChunkX,
    int minChunkZ,
    int maxChunkX,
    int maxChunkZ
) implements Message {
    public static LoadStateSubscribeC2S cancel(final int subscriptionId) {
        return new LoadStateSubscribeC2S(subscriptionId, 0, false, 0, 0, 0, 0);
    }

    @Override
    public int typeId() {
        return Proto.MSG_LOAD_STATE_SUBSCRIBE_C2S;
    }
}
