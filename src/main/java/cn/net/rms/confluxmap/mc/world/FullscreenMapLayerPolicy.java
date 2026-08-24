package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;

/** Selects the top-level plane for a temporary fullscreen-map target. */
public final class FullscreenMapLayerPolicy {
    private FullscreenMapLayerPolicy() {
    }

    public static MapLayer select(
        final DimensionId dimension,
        final boolean liveSession,
        final MapLayer liveLayer
    ) {
        if (!liveSession) {
            if (dimension.equals(DimensionId.NETHER)) {
                return MapLayer.NETHER_CEILING;
            }
            return dimension.equals(DimensionId.END) ? MapLayer.END_SURFACE : MapLayer.SURFACE;
        }
        return liveLayer;
    }
}
