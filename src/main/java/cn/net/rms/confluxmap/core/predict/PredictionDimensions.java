package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * Maps the two dimensions M2 predicts to the cubiomes {@code Dimension} enum ints the native
 * shim expects ({@code cfxCreate}'s {@code dim} parameter). Nether is out of scope this
 * milestone (see the plan's locked decisions) - {@link #supported} is the single gate every
 * predicted-underlay code path checks before doing any work for a dimension.
 */
public final class PredictionDimensions {
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
}
