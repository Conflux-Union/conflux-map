package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.color.LightTint;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import cn.net.rms.confluxmap.core.color.XaeroMapStyle;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * The light-and-tint tail both predicted-tile composition paths run: the ambient tint baked in
 * before relief shading, and the per-layer light application ({@link #applyLayerLighting}) that
 * turns a composed pixel plane plus its block-light plane into the uploadable tile. Purely a
 * function of its arguments - the live {@link DaylightModel} and style are snapshotted by the
 * caller, so a re-shade tracks the current settings instead of stale ones.
 */
final class PredictionTileShading {
    private PredictionTileShading() {
    }

    record AppliedLighting(float daylight, float gamma) {
        static final AppliedLighting NONE = new AppliedLighting(Float.NaN, 0f);
    }

    /**
     * Applies the layer-appropriate light pass in place: NETHER_CEILING and END_SURFACE replace
     * their zero-light ambient bake with the block-light plane, and SURFACE scales by the
     * daylight factor. {@code model} is the caller's live snapshot; null means full daylight.
     * Returns the applied daylight/gamma for the upload's relight metadata, or {@code NONE}
     * when the layer composes without one.
     */
    static AppliedLighting applyLayerLighting(
        final TileKey key,
        final int[] pixels,
        final byte[] blockLight,
        final MapColorStyle style,
        final DaylightModel model
    ) {
        if (BiomeTileKeys.isBiome(key)) {
            return AppliedLighting.NONE;
        }
        final String realLayer = BiomeTileKeys.baseLayerId(key);
        final MapLayer.Type layer = MapLayer.parse(realLayer).type();
        final DaylightModel.State lighting = model == null
            ? new DaylightModel.State(1f, 0f)
            : model.state();
        final float gamma = lighting.gamma();
        if (layer == MapLayer.Type.NETHER_CEILING) {
            // Captured Xaero roof tiles keep their zero-light ambient bake untouched
            // (TileService's composeXaeroColumn applies no light replacement to
            // NETHER_CEILING), so the prediction must match instead of being re-lit.
            if (style != MapColorStyle.XAERO) {
                for (int pixel = 0; pixel < pixels.length; pixel++) {
                    pixels[pixel] = LightTint.applyBlockLightOverAmbient(
                        pixels[pixel], blockLight[pixel] & 0xFF, true, gamma
                    );
                }
            }
            return AppliedLighting.NONE;
        }
        if (layer == MapLayer.Type.END_SURFACE) {
            // The End plane composes with the zero-light ambient baked in (see composeTile);
            // the per-column block-light plane replaces it here, matching TileService's
            // raw END_SURFACE composition. The Xaero style composes without the bake (its
            // terrain shading adds light, which a ratio replace cannot invert) and takes the
            // curve applied over the shaded pixel, exactly like captured Xaero End tiles.
            for (int pixel = 0; pixel < pixels.length; pixel++) {
                pixels[pixel] = style == MapColorStyle.XAERO
                    ? LightTint.applyBlockLightTint(
                        pixels[pixel], blockLight[pixel] & 0xFF, false, gamma
                    )
                    : LightTint.applyBlockLightOverAmbient(
                        pixels[pixel], blockLight[pixel] & 0xFF, false, gamma
                    );
            }
            return AppliedLighting.NONE;
        }
        if (layer != MapLayer.Type.SURFACE) {
            return AppliedLighting.NONE;
        }
        if (model == null) {
            return AppliedLighting.NONE;
        }
        final float factor = lighting.daylight();
        for (int pixel = 0; pixel < pixels.length; pixel++) {
            pixels[pixel] = style == MapColorStyle.XAERO
                ? Argb.scale(
                    pixels[pixel],
                    ShadingPipeline.applyGamma(
                        XaeroMapStyle.daylightScale(factor, blockLight[pixel] & 0xFF), gamma
                    )
                )
                : ShadingPipeline.applyDaylight(
                    pixels[pixel], factor, blockLight[pixel] & 0xFF, gamma
                );
        }
        return new AppliedLighting(factor, gamma);
    }

    static TileUpdate tileUpdate(
        final TileKey key,
        final int[] pixels,
        final byte[] blockLight,
        final float composedDaylight,
        final float composedGamma,
        final MapColorStyle style
    ) {
        return Float.isNaN(composedDaylight)
            ? TileUpdate.fullTile(key, pixels)
            : TileUpdate.fullTile(
                key, pixels, new TileUpdate.Relight(
                    composedDaylight, composedGamma, blockLight, style
                )
            );
    }

    /** Ambient tint baked in before height and relief shading. */
    static int ambientLightTint(final TileKey key, final MapColorStyle style) {
        if (key.dimension().equals(DimensionId.NETHER)) {
            return LightTint.multiplier(0, 0, true);
        }
        if (key.dimension().equals(DimensionId.END) && style == MapColorStyle.CONFLUX) {
            // The End owes the cave dark-light contract too: bake the zero-light ambient in
            // here (baseline and corrections alike), then the per-column block-light plane
            // replaces it in applyLayerLighting. The Xaero style bakes nothing - its terrain
            // shading adds light, which the ambient ratio-replace cannot invert - and instead
            // takes the curve applied over the shaded pixel, exactly like the captured Xaero
            // End tiles.
            return LightTint.multiplier(0, 0, false);
        }
        return 0xFFFFFFFF;
    }
}
