package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;

/**
 * Maps Minecraft dimensions to the cubiomes {@code Dimension} enum ints the native shim expects.
 */
public final class PredictionDimensions {
    public static final int NETHER = -1;
    public static final int OVERWORLD = 0;
    public static final int END = 1;
    /** Highest natural bedrock-roof block in the vanilla Nether. */
    public static final int NETHER_ROOF_Y = 127;
    /** Vanilla map color used by bedrock in companion summaries. */
    public static final int NETHER_ROOF_MAP_COLOR_ID = 11;

    private PredictionDimensions() {
    }

    public static boolean supported(final DimensionId dimension) {
        return dimension.equals(DimensionId.OVERWORLD)
            || dimension.equals(DimensionId.NETHER)
            || dimension.equals(DimensionId.END);
    }

    public static boolean isEnd(final DimensionId dimension) {
        return dimension.equals(DimensionId.END);
    }

    /** Persistent map layer owned by the predicted underlay, or {@code null} when unsupported. */
    public static MapLayer layer(final DimensionId dimension) {
        if (dimension.equals(DimensionId.NETHER)) {
            return MapLayer.NETHER_CEILING;
        }
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return MapLayer.SURFACE;
        }
        if (dimension.equals(DimensionId.END)) {
            return MapLayer.END_SURFACE;
        }
        return null;
    }

    public static boolean structuresSupported(final DimensionId dimension) {
        return dimension.equals(DimensionId.OVERWORLD)
            || dimension.equals(DimensionId.NETHER)
            || dimension.equals(DimensionId.END);
    }

    /** The native {@code dim} value for {@code dimension}, or {@link Integer#MIN_VALUE} when unsupported. */
    public static int nativeDim(final DimensionId dimension) {
        if (dimension.equals(DimensionId.NETHER)) {
            return NETHER;
        }
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return OVERWORLD;
        }
        if (dimension.equals(DimensionId.END)) {
            return END;
        }
        return Integer.MIN_VALUE;
    }

    /** Native dimension id for structure lookup, or {@link Integer#MIN_VALUE} when unsupported. */
    public static int nativeStructureDim(final DimensionId dimension) {
        if (dimension.equals(DimensionId.NETHER)) {
            return NETHER;
        }
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return OVERWORLD;
        }
        if (dimension.equals(DimensionId.END)) {
            return END;
        }
        return Integer.MIN_VALUE;
    }
}
