package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Pure-int implementation of surface-color-sampling.md §4 (height/slope shading)
 * and §5 (water/ice compositing). No Minecraft types; every input is a plain
 * color, height, or neighbor height already resolved by the caller.
 *
 * <p>This slice only implements the cached/world-map pass's fixed height
 * reference (§4: "cached/world-map pass ... always uses the fixed constant 80").
 * The live pass's viewer-relative reference is a later slice; the tile service
 * only tracks a 2D viewpoint for tile selection, not a Y for shading.
 *
 * <p>The ice-specific extra darkening multiply in §5 is gated behind the
 * "dynamic lighting" toggle, which this slice does not implement at all (no
 * day/night light table exists yet) - so it is always a no-op here.
 */
public final class ShadingPipeline {
    /** §4: the cached/world-map pass's fixed height-shading reference. */
    public static final int REFERENCE_HEIGHT = 80;

    private static final double HEIGHT_STEP = 8.0;
    private static final double K_ALONE = 1.8;
    private static final double K_WITH_SLOPE = 3.0;
    private static final double SLOPE_STEP = 1.0 / 8.0;

    private ShadingPipeline() {
    }

    /** §4 absolute-height term. {@code slopeAlsoActive} selects the gentler K used when combined with slope. */
    public static double heightShade(final int columnHeight, final int referenceHeight, final boolean slopeAlsoActive) {
        final int diff = columnHeight - referenceHeight;
        final double k = slopeAlsoActive ? K_WITH_SLOPE : K_ALONE;
        final double shade = Math.log10(Math.abs(diff) / HEIGHT_STEP + 1.0) / k;
        return diff < 0 ? -shade : shade;
    }

    /**
     * §4 slope term: a fixed +-1/8 step depending on whether the fixed diagonal
     * neighbor (x-1, z+1) is higher, lower, or level. {@code neighborHeight} is
     * null when that neighbor is unavailable (missing region/edge of loaded data),
     * in which case the term is flat/zero.
     */
    public static double slopeShade(final int columnHeight, final Integer neighborHeight) {
        if (neighborHeight == null) {
            return 0.0;
        }
        if (neighborHeight > columnHeight) {
            return SLOPE_STEP;
        }
        if (neighborHeight < columnHeight) {
            return -SLOPE_STEP;
        }
        return 0.0;
    }

    /** Sum of the active height/slope terms, per §4's "layered on top of" combination. */
    public static double combinedShade(
        final boolean heightEnabled,
        final boolean slopeEnabled,
        final int columnHeight,
        final int referenceHeight,
        final Integer neighborHeight
    ) {
        final double slope = slopeEnabled ? slopeShade(columnHeight, neighborHeight) : 0.0;
        final double height = heightEnabled ? heightShade(columnHeight, referenceHeight, slopeEnabled) : 0.0;
        return slope + height;
    }

    /**
     * Applies a shade value to a color's R/G/B channels (alpha untouched), per §4's
     * "Applying the combined shade value": blend toward white when positive, toward
     * black when negative. The spec calls out that there is no explicit clamp in the
     * reference formula; channels are clamped only where unavoidable, at the final
     * pack into a byte-per-channel color.
     */
    public static int applyShade(final int argb, final double shade) {
        if (shade == 0.0) {
            return argb;
        }
        final int r = shadeChannel(Argb.red(argb), shade);
        final int g = shadeChannel(Argb.green(argb), shade);
        final int b = shadeChannel(Argb.blue(argb), shade);
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    private static int shadeChannel(final int c, final double shade) {
        final double shaded = shade > 0 ? c + shade * (255 - c) : c - (-shade) * c;
        return clampChannel((int) Math.round(shaded));
    }

    private static int clampChannel(final int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }

    /**
     * §5 composite: a fully-shaded, fully-lit overlay layer (water/ice surface,
     * or a land column's transparent/foliage overlay) drawn over its floor/base
     * layer with a single one-shot "over" alpha blend. No depth-based math - the
     * top layer's own alpha (from its sampled texture/tint) is all that decides
     * how much of the layer beneath shows through. {@code Argb.TRANSPARENT}
     * bottom (e.g. §1's bottomless-water rule: no seafloor recorded) leaves the
     * top layer untouched.
     */
    public static int compositeOver(final int shadedTopArgb, final int shadedBottomArgb) {
        if (shadedBottomArgb == Argb.TRANSPARENT) {
            return shadedTopArgb;
        }
        return Argb.over(shadedTopArgb, shadedBottomArgb);
    }
}
