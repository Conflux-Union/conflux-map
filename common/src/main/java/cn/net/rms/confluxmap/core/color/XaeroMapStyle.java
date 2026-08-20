package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * The terrain-colour operators used by Xaero's default accurate-colour, 3D-slope style.
 * All inputs are plain sampled colours and heights so authoritative and predicted tiles can
 * share the same final rendering step.
 */
public final class XaeroMapStyle {
    private static final float AMBIENT_COLORED = 0.2f;
    private static final float AMBIENT_WHITE = 0.5f;
    private static final float MAX_DIRECT_LIGHT = 2f / 3f;
    private static final float PARTIAL_DIRECT_LIGHT = 0.88388f;
    private static final float MIN_TERRAIN_DEPTH = 0.9f;
    private static final float MAX_TERRAIN_DEPTH = 1.0f;
    private static final float TERRAIN_DEPTH_HEIGHT = 63f;
    private static final float NIGHT_AMBIENT = 0.375f;
    private static final float DAYLIGHT_CURVE_START = 0.24f;
    private static final float DAYLIGHT_CURVE_RANGE = 0.76f;
    private static final float LIGHT_DENOMINATOR = 24f;
    private static final int LIGHT_BASE = 9;
    private static final int MAX_LIGHT_LEVEL = 15;

    public enum Shadow {
        OVERWORLD(0.518f, 0.678f, 1.0f),
        NETHER(1.0f, 0.0f, 0.0f),
        END(1.0f, 1.0f, 1.0f);

        private final float red;
        private final float green;
        private final float blue;

        Shadow(final float red, final float green, final float blue) {
            this.red = red;
            this.green = green;
            this.blue = blue;
        }
    }

    private XaeroMapStyle() {
    }

    public static Shadow shadowFor(final DimensionId dimension) {
        if (DimensionId.NETHER.equals(dimension)) {
            return Shadow.NETHER;
        }
        if (DimensionId.END.equals(dimension)) {
            return Shadow.END;
        }
        return Shadow.OVERWORLD;
    }

    /**
     * Applies the default terrain-depth and directional-slope colour multipliers. North and
     * north-west are the two preceding map pixels used to reconstruct the surface normal.
     */
    public static int applyTerrain(
        final int argb,
        final int height,
        final Integer northHeight,
        final Integer northWestHeight,
        final int blocksPerPixel,
        final boolean terrainDepth,
        final Shadow shadow
    ) {
        if (blocksPerPixel <= 0) {
            throw new IllegalArgumentException("blocksPerPixel must be positive");
        }
        if (northHeight == null || northWestHeight == null) {
            return terrainDepth
                ? multiplyRgb(argb, terrainDepthMultiplier(height), terrainDepthMultiplier(height),
                    terrainDepthMultiplier(height))
                : argb;
        }
        final float verticalSlope = (height - northHeight) / (float) blocksPerPixel;
        final float diagonalSlope = (height - northWestHeight) / (float) blocksPerPixel;
        final float directLight = directLight(verticalSlope, diagonalSlope);
        final float whiteLight = AMBIENT_WHITE + directLight;
        final float depth = terrainDepth ? terrainDepthMultiplier(height) : 1f;
        return multiplyRgb(
            argb,
            (shadow.red * AMBIENT_COLORED + whiteLight) * depth,
            (shadow.green * AMBIENT_COLORED + whiteLight) * depth,
            (shadow.blue * AMBIENT_COLORED + whiteLight) * depth
        );
    }

    /** The default terrain-depth term: only low terrain is darkened, within 0.9..1.0. */
    public static float terrainDepthMultiplier(final int height) {
        return clamp(height / TERRAIN_DEPTH_HEIGHT, MIN_TERRAIN_DEPTH, MAX_TERRAIN_DEPTH);
    }

    /**
     * Brightness retained by the opaque floor below a transparent column. Transparency blocks
     * at most fifteen light levels and the map-light curve has a nine-level readability base.
     */
    public static float transparentFloorBrightness(final int transparencyDepth) {
        final int blocked = clampLevel(transparencyDepth);
        return (LIGHT_BASE + MAX_LIGHT_LEVEL - blocked) / LIGHT_DENOMINATOR;
    }

    /** Global day/night brightness combined with the encoded per-pixel block-light level. */
    public static float daylightScale(final float daylightFactor, final int blockLevel) {
        final float normalizedDaylight = clamp(
            (daylightFactor - DAYLIGHT_CURVE_START) / DAYLIGHT_CURVE_RANGE, 0f, 1f
        );
        final float globalBrightness = NIGHT_AMBIENT + (1f - NIGHT_AMBIENT) * normalizedDaylight;
        final int level = clampLevel(blockLevel);
        final float pixelLight = level == 0 ? 0f : (LIGHT_BASE + level) / LIGHT_DENOMINATOR;
        return Math.max(globalBrightness, pixelLight);
    }

    private static float directLight(final float verticalSlope, final float diagonalSlope) {
        final float crossZ = -verticalSlope;
        float cosine = 0f;
        if (crossZ < 1f) {
            if (verticalSlope == 1f && diagonalSlope == 1f) {
                cosine = 1f;
            } else {
                final float crossX = verticalSlope - diagonalSlope;
                final float cast = 1f - crossZ;
                final float magnitude = (float) Math.sqrt(crossX * crossX + 1f + crossZ * crossZ);
                cosine = (float) ((cast / magnitude) / Math.sqrt(2.0));
            }
        }
        if (cosine == 1f) {
            return MAX_DIRECT_LIGHT;
        }
        if (cosine <= 0f) {
            return 0f;
        }
        return (float) Math.ceil(cosine * 10f) / 10f * MAX_DIRECT_LIGHT * PARTIAL_DIRECT_LIGHT;
    }

    private static int multiplyRgb(
        final int argb,
        final float redMultiplier,
        final float greenMultiplier,
        final float blueMultiplier
    ) {
        return Argb.pack(
            Argb.alpha(argb),
            clampChannel((int) (Argb.red(argb) * redMultiplier)),
            clampChannel((int) (Argb.green(argb) * greenMultiplier)),
            clampChannel((int) (Argb.blue(argb) * blueMultiplier))
        );
    }

    private static int clampLevel(final int level) {
        return level < 0 ? 0 : Math.min(level, MAX_LIGHT_LEVEL);
    }

    private static int clampChannel(final int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }

    private static float clamp(final float value, final float min, final float max) {
        return value < min ? min : Math.min(value, max);
    }
}
