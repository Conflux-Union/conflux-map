package cn.net.rms.confluxmap.core.net;

import java.util.List;

/** Changed 16x16-chunk summary regions intersecting the active chunk viewport. */
public record MapRegionInvalidateS2C(
    int dimIndex,
    int lod,
    List<Region> regions
) implements Message {
    public record Region(int regionX, int regionZ) {
    }

    public MapRegionInvalidateS2C {
        regions = regions == null ? List.of() : List.copyOf(regions);
    }

    @Override
    public int typeId() {
        return Proto.MSG_MAP_REGION_INVALIDATE_S2C;
    }
}
