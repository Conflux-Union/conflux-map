package cn.net.rms.confluxmap.core.net;

/** C2S subscription for invalidations affecting the current predicted-map viewport. */
public record MapSyncSubscribeC2S(
    int dimIndex,
    int lod,
    boolean active,
    int minTileX,
    int maxTileX,
    int minTileZ,
    int maxTileZ
) implements Message {
    @Override
    public int typeId() {
        return Proto.MSG_MAP_SYNC_SUBSCRIBE_C2S;
    }
}
