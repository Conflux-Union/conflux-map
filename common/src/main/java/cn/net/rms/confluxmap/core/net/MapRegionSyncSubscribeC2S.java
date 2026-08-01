package cn.net.rms.confluxmap.core.net;

/** Subscribes to source-region changes intersecting one exact chunk viewport. */
public record MapRegionSyncSubscribeC2S(
    int dimIndex,
    int lod,
    boolean active,
    int minChunkX,
    int maxChunkX,
    int minChunkZ,
    int maxChunkZ
) implements Message {
    @Override
    public int typeId() {
        return Proto.MSG_MAP_REGION_SYNC_SUBSCRIBE_C2S;
    }
}
