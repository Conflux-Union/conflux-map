package cn.net.rms.confluxmap.core.export;

import cn.net.rms.confluxmap.core.util.Argb;

/** CPU equivalent of the fullscreen map's background, prediction, real, and load-state passes. */
public final class MapExportCompositor {
    private MapExportCompositor() {
    }

    public static int compose(
        final int background,
        final int predicted,
        final int real,
        final int predictionTint,
        final int overlay
    ) {
        int color = background;
        if (Argb.alpha(predicted) != 0) {
            color = Argb.over(Argb.multiply(predicted, predictionTint), color);
        }
        if (Argb.alpha(real) != 0) {
            color = Argb.over(real, color);
        }
        if (Argb.alpha(overlay) != 0) {
            color = Argb.over(overlay, color);
        }
        return color;
    }
}
