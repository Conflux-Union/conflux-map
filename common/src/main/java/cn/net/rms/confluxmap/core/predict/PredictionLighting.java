package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;

/** Render-time lighting shared by every tile in the predicted map plane. */
public final class PredictionLighting {
    private static final int OPAQUE_WHITE = 0xFFFFFFFF;

    private PredictionLighting() {
    }

    /**
     * Returns the vertex tint for predicted textures. Applying this once per render pass keeps
     * day/night changes uniform without baking the composition time into each cached tile.
     */
    public static int renderTint(final boolean dynamicLighting, final float daylightFactor) {
        return dynamicLighting
            ? ShadingPipeline.applyDaylight(OPAQUE_WHITE, daylightFactor, 0)
            : OPAQUE_WHITE;
    }
}
