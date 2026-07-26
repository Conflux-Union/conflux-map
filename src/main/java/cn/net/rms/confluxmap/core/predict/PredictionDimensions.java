package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * Maps Minecraft dimensions to the cubiomes {@code Dimension} enum ints the native shim expects.
 * The terrain underlay remains Overworld/End-only, while structure lookup also supports Nether.
 */
public final class PredictionDimensions {
    public static final int NETHER = -1;
    public static final int OVERWORLD = 0;
    public static final int END = 1;

    private PredictionDimensions() {
    }

    public static boolean supported(final DimensionId dimension) {
        return dimension.equals(DimensionId.OVERWORLD) || dimension.equals(DimensionId.END);
    }

    public static boolean isEnd(final DimensionId dimension) {
        return dimension.equals(DimensionId.END);
    }

    public static boolean structuresSupported(final DimensionId dimension) {
        return dimension.equals(DimensionId.OVERWORLD)
            || dimension.equals(DimensionId.NETHER)
            || dimension.equals(DimensionId.END);
    }

    /** The native {@code dim} value for {@code dimension}, or {@code -1} if {@link #supported} is false. */
    public static int nativeDim(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return OVERWORLD;
        }
        if (dimension.equals(DimensionId.END)) {
            return END;
        }
        return -1;
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
