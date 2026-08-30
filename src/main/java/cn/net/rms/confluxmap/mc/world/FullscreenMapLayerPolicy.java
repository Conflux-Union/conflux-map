package cn.net.rms.confluxmap.mc.world;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;

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

    /** Whether seed prediction has a plane for this fullscreen layer and display mode. */
    public static boolean predictionAllowed(
        final DimensionId dimension,
        final MapLayer layer,
        final boolean biomeMode
    ) {
        if (layer.equals(PredictionDimensions.layer(dimension))) {
            return true;
        }
        return biomeMode && dimension.equals(DimensionId.NETHER)
            && layer.type().isNetherFloor();
    }

    /** Y used for biome lookup on a layer whose visible floor is tied to a debounced pivot. */
    public static int biomeSampleY(
        final MapLayer layer,
        final int playerY,
        final int layerPivotY
    ) {
        return switch (layer.type()) {
            case SURFACE, END_SURFACE -> playerY;
            default -> layerPivotY;
        };
    }
}
