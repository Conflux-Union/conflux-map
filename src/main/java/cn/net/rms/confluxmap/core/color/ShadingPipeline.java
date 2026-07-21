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
 * <p>{@link #applyDaylight} is a deliberately simplified slice of §4's "Light-based
 * (day/night) shading": a single global day/night factor (see {@link DaylightModel})
 * blended with per-column block-light, applied only to the live SURFACE layer. It does
 * not reproduce the full reference curve (per-pixel torch flicker, gamma-setting
 * response, night-vision ramp, the ice-specific extra seafloor darkening, or the
 * cached-pass "recolor with today's palette" rule) - those remain unimplemented. Cave/
 * Nether/End layers never call this; they keep baking light into their colors at
 * snapshot time via {@link LightTint} instead (see {@code McChunkSnapshotFactory}).
 */
public final class ShadingPipeline {
    /** §4: the cached/world-map pass's fixed height-shading reference. */
    public static final int REFERENCE_HEIGHT = 80;
    /**
     * Map-readability floor for {@link #applyDaylight}: even at the darkest night with no
     * block light, the SURFACE layer never scales below this fraction of its lit color -
     * the same "never invisible" rationale as {@link LightTint}'s readability floor for the
     * baked cave/nether curve, kept as a separate constant since the two layers' color
     * pipelines are otherwise independent.
     */
    private static final float DAYLIGHT_FLOOR = 0.3f;

    private static final double HEIGHT_STEP = 8.0;
    private static final double K_ALONE = 1.8;
    private static final double K_WITH_SLOPE = 3.0;
    private static final double SLOPE_STEP = 1.0 / 8.0;
    private static final double BLOCKS_PER_FULL_SLOPE = 8.0;

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

    /**
     * Continuous form of {@link #slopeShade} for interpolated or aggregated height fields.
     * The centered difference between the lit-side and dark-side diagonal samples is normalized
     * by their two-pixel LOD distance. A normalized rise of {@value #BLOCKS_PER_FULL_SLOPE}
     * reaches the same maximum shade as the discrete map slope; smaller differences remain
     * proportional instead of turning every one-block quantization boundary into a full-strength
     * contour line.
     */
    public static double continuousSlopeShade(
        final Integer litSideHeight,
        final Integer darkSideHeight,
        final int blocksPerPixel
    ) {
        if (litSideHeight == null || darkSideHeight == null) {
            return 0.0;
        }
        if (blocksPerPixel <= 0) {
            throw new IllegalArgumentException("blocksPerPixel must be positive");
        }
        final double risePerBlock = (litSideHeight - darkSideHeight) / (2.0 * blocksPerPixel);
        final double normalized = Math.max(-1.0, Math.min(1.0, risePerBlock / BLOCKS_PER_FULL_SLOPE));
        return normalized * SLOPE_STEP;
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

    /** Multiplies RGB brightness while preserving hue and alpha. */
    public static int applyBrightnessMultiplier(final int argb, final double factor) {
        if (factor == 1.0) {
            return argb;
        }
        final int r = clampChannel((int) Math.round(Argb.red(argb) * factor));
        final int g = clampChannel((int) Math.round(Argb.green(argb) * factor));
        final int b = clampChannel((int) Math.round(Argb.blue(argb) * factor));
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

    /**
     * Darkens a fully-composed SURFACE-layer pixel for the current point in the day/night
     * cycle, except where block light (torches, glowstone, ...) keeps that column bright.
     * {@code blockLevel} is the raw 0-15 block-light reading (see {@link
     * cn.net.rms.confluxmap.core.model.ChunkSnapshot#light}); {@code daylightFactor} is
     * {@link DaylightModel#factor()} at compose time.
     *
     * <p>{@code b = max(daylightFactor, blockLevel/15)} is the column's effective
     * brightness, then the result is scaled by {@code DAYLIGHT_FLOOR + (1 - DAYLIGHT_FLOOR) * b}
     * so a fully unlit column at night still reads at {@link #DAYLIGHT_FLOOR}, never pure
     * black. Alpha is untouched (see {@link Argb#scale}). At {@code daylightFactor == 1.0}
     * (full daylight, or dynamic lighting disabled entirely at the call site) {@code b} is
     * always 1 regardless of block light, so the scale factor is exactly 1 and this is a
     * bit-identical no-op - today's undarkened rendering.
     */
    public static int applyDaylight(final int argb, final float daylightFactor, final int blockLevel) {
        final float b = Math.max(daylightFactor, blockLevel / 15f);
        final float scale = DAYLIGHT_FLOOR + (1f - DAYLIGHT_FLOOR) * b;
        return Argb.scale(argb, scale);
    }
}
