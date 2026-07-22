package cn.net.rms.confluxmap.core.util;

/** Packed 0xAARRGGBB color math used by the map pipeline. */
public final class Argb {
    public static final int OPAQUE_BLACK = 0xFF000000;
    public static final int TRANSPARENT = 0x00000000;

    private Argb() {
    }

    public static int pack(final int a, final int r, final int g, final int b) {
        return (a & 0xFF) << 24 | (r & 0xFF) << 16 | (g & 0xFF) << 8 | (b & 0xFF);
    }

    public static int alpha(final int argb) {
        return argb >>> 24;
    }

    public static int red(final int argb) {
        return (argb >> 16) & 0xFF;
    }

    public static int green(final int argb) {
        return (argb >> 8) & 0xFF;
    }

    public static int blue(final int argb) {
        return argb & 0xFF;
    }

    /** Per-channel multiply of two colors, alpha taken from {@code base}. */
    public static int multiply(final int base, final int tint) {
        final int r = red(base) * red(tint) / 255;
        final int g = green(base) * green(tint) / 255;
        final int b = blue(base) * blue(tint) / 255;
        return (base & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** Scale RGB channels by {@code factor} in [0, 1], keeping alpha. */
    public static int scale(final int argb, final float factor) {
        final int r = clampChannel((int) (red(argb) * factor));
        final int g = clampChannel((int) (green(argb) * factor));
        final int b = clampChannel((int) (blue(argb) * factor));
        return (argb & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /** Scale the alpha channel by {@code factor} in [0, 1], keeping RGB. */
    public static int scaleAlpha(final int argb, final float factor) {
        final int a = clampChannel((int) (alpha(argb) * factor));
        return a << 24 | (argb & 0x00FFFFFF);
    }

    /** Blend {@code over} onto {@code under} using {@code over}'s alpha, keeping {@code under}'s alpha. */
    public static int blendOver(final int under, final int over) {
        final int a = alpha(over);
        if (a == 255) {
            return over;
        }
        if (a == 0) {
            return under;
        }
        final int inv = 255 - a;
        final int r = (red(over) * a + red(under) * inv) / 255;
        final int g = (green(over) * a + green(under) * inv) / 255;
        final int b = (blue(over) * a + blue(under) * inv) / 255;
        return (under & 0xFF000000) | r << 16 | g << 8 | b;
    }

    /**
     * Standard Porter-Duff "over" composite: {@code resultAlpha = topAlpha + bottomAlpha*(1-topAlpha)},
     * RGB weighted accordingly. Unlike {@link #blendOver}, the result alpha is genuinely computed rather
     * than inherited from the bottom layer, matching the color spec's water/overlay composite rule.
     */
    public static int over(final int top, final int bottom) {
        final int topA = alpha(top);
        if (topA == 255) {
            return top;
        }
        if (topA == 0) {
            return bottom;
        }
        final int bottomA = alpha(bottom);
        final int outA = topA + bottomA * (255 - topA) / 255;
        if (outA == 0) {
            return TRANSPARENT;
        }
        final int topWeight = topA * 255;
        final int bottomWeight = bottomA * (255 - topA);
        final int r = (red(top) * topWeight + red(bottom) * bottomWeight) / (outA * 255);
        final int g = (green(top) * topWeight + green(bottom) * bottomWeight) / (outA * 255);
        final int b = (blue(top) * topWeight + blue(bottom) * bottomWeight) / (outA * 255);
        return pack(outA, r, g, b);
    }

    /** Convert to the ABGR byte order used by native image buffers. */
    public static int toAbgr(final int argb) {
        return (argb & 0xFF00FF00) | (argb & 0x00FF0000) >>> 16 | (argb & 0x000000FF) << 16;
    }

    /** Rec. 709 relative luminance of the RGB channels in [0, 255]; alpha is ignored. */
    public static int luminance(final int argb) {
        return (2126 * red(argb) + 7152 * green(argb) + 722 * blue(argb)) / 10000;
    }

    /** Average of 4 colors, used by LOD downsampling. */
    public static int average4(final int c0, final int c1, final int c2, final int c3) {
        final int a = (alpha(c0) + alpha(c1) + alpha(c2) + alpha(c3)) >> 2;
        final int r = (red(c0) + red(c1) + red(c2) + red(c3)) >> 2;
        final int g = (green(c0) + green(c1) + green(c2) + green(c3)) >> 2;
        final int b = (blue(c0) + blue(c1) + blue(c2) + blue(c3)) >> 2;
        return pack(a, r, g, b);
    }

    /**
     * Alpha-weighted average of 4 colors for LOD mipmap downsampling: a fully-transparent
     * (unexplored) pixel contributes its alpha to the result's coverage but not its (zero) RGB,
     * so a region that is only partly explored downsamples to a clean translucent value instead
     * of darkening toward black. {@link #average4} would average the transparent pixel's RGB=0
     * in, producing a half-transparent muddy edge that muddies the predicted underlay beneath.
     */
    public static int average4Weighted(final int c0, final int c1, final int c2, final int c3) {
        final int a0 = alpha(c0);
        final int a1 = alpha(c1);
        final int a2 = alpha(c2);
        final int a3 = alpha(c3);
        final int outA = (a0 + a1 + a2 + a3) >> 2;
        final int w = a0 + a1 + a2 + a3;
        if (w == 0) {
            return TRANSPARENT;
        }
        final int r = (red(c0) * a0 + red(c1) * a1 + red(c2) * a2 + red(c3) * a3) / w;
        final int g = (green(c0) * a0 + green(c1) * a1 + green(c2) * a2 + green(c3) * a3) / w;
        final int b = (blue(c0) * a0 + blue(c1) * a1 + blue(c2) * a2 + blue(c3) * a3) / w;
        return pack(outA, r, g, b);
    }

    private static int clampChannel(final int v) {
        return v < 0 ? 0 : Math.min(v, 255);
    }
}
