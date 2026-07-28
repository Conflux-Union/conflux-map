package cn.net.rms.confluxmap.core.net;

import cn.net.rms.confluxmap.core.util.ChunkRegionSlice;
import java.util.List;

/** Requests bounded cropped summary-region pages intersecting the exact visible chunk viewport. */
public record MapRegionViewReqC2S(
    int reqId,
    int dimIndex,
    int lod,
    List<RegionReq> regions
) implements Message {
    public record RegionReq(
        int regionX,
        int regionZ,
        int minLocalChunkX,
        int minLocalChunkZ,
        int maxLocalChunkX,
        int maxLocalChunkZ,
        long sinceRevision
    ) {
        public RegionReq {
            new ChunkRegionSlice(
                regionX, regionZ, minLocalChunkX, minLocalChunkZ, maxLocalChunkX, maxLocalChunkZ
            );
        }

        public RegionReq(final ChunkRegionSlice slice, final long sinceRevision) {
            this(
                slice.regionX(), slice.regionZ(),
                slice.minLocalChunkX(), slice.minLocalChunkZ(),
                slice.maxLocalChunkX(), slice.maxLocalChunkZ(),
                sinceRevision
            );
        }

        public ChunkRegionSlice slice() {
            return new ChunkRegionSlice(
                regionX, regionZ, minLocalChunkX, minLocalChunkZ, maxLocalChunkX, maxLocalChunkZ
            );
        }
    }

    public MapRegionViewReqC2S {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_REGION_VIEW_REQ_C2S;
    }
}
