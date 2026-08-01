package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;

/** One complete authoritative cropped summary-region correction snapshot. */
public record MapRegionPatchS2C(
    int reqId,
    int dimIndex,
    int lod,
    int regionX,
    int regionZ,
    int minLocalChunkX,
    int minLocalChunkZ,
    int maxLocalChunkX,
    int maxLocalChunkZ,
    int mode,
    long regionRevision,
    byte[] body
) implements Message {
    public MapRegionPatchS2C {
        new ChunkRegionSlice(
            regionX, regionZ, minLocalChunkX, minLocalChunkZ, maxLocalChunkX, maxLocalChunkZ
        );
        body = body == null ? null : body.clone();
    }

    public ChunkRegionSlice slice() {
        return new ChunkRegionSlice(
            regionX, regionZ, minLocalChunkX, minLocalChunkZ, maxLocalChunkX, maxLocalChunkZ
        );
    }

    @Override
    public byte[] body() {
        return body == null ? null : body.clone();
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_REGION_PATCH_S2C;
    }
}
