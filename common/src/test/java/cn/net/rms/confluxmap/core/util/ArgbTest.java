package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ArgbTest {
    @Test
    void average4WeightedKeepsOpaqueRgbWhenSomePixelsAreTransparent() {
        // A 2x2 LOD downsample block where three of four columns were never explored (transparent,
        // RGB 0) and one was captured (opaque green). Plain average4 would drag that green toward
        // black and leave the result half-transparent; the weighted variant keeps the opaque
        // pixel's RGB intact and only drops alpha to reflect the partial coverage, so the predicted
        // underlay beneath a partly-explored LOD tile isn't muddied.
        final int transparent = Argb.TRANSPARENT;
        final int opaque = 0xFF20A030;
        final int result = Argb.average4Weighted(transparent, opaque, transparent, transparent);
        assertEquals(Argb.red(opaque), Argb.red(result));
        assertEquals(Argb.green(opaque), Argb.green(result));
        assertEquals(Argb.blue(opaque), Argb.blue(result));
        assertEquals(63, Argb.alpha(result)); // (255 + 0 + 0 + 0) >> 2
    }

    @Test
    void average4WeightedAllTransparentStaysTransparent() {
        assertEquals(Argb.TRANSPARENT, Argb.average4Weighted(Argb.TRANSPARENT, Argb.TRANSPARENT, Argb.TRANSPARENT, Argb.TRANSPARENT));
    }

    @Test
    void scaleAlphaScalesAlphaOnlyAndKeepsRgb() {
        // Used by the radar to ghost spectator-mode players at half opacity: RGB must stay
        // untouched so category colors keep their hue, only coverage drops.
        final int halved = Argb.scaleAlpha(0xFF20A030, 0.5f);
        assertEquals(127, Argb.alpha(halved));
        assertEquals(0x20A030, halved & 0x00FFFFFF);
        assertEquals(0xFF20A030, Argb.scaleAlpha(0xFF20A030, 1f));
    }

    @Test
    void average4WeightedAllOpaqueMatchesPlainAverage() {
        // With full coverage everywhere the weighted variant is identical to the plain average.
        final int a = 0xFF102030, b = 0xFF405060, c = 0xFF708090, d = 0xFFA0B0C0;
        assertEquals(Argb.average4(a, b, c, d), Argb.average4Weighted(a, b, c, d));
    }

    @Test
    void luminanceWeighsGreenHeaviestAndIgnoresAlpha() {
        // Rec. 709: the radar contour flips on this, so green terrain must read brighter
        // than equally-intense blue (water), and alpha must not skew the result.
        assertEquals(0, Argb.luminance(0xFF000000));
        assertEquals(255, Argb.luminance(0x00FFFFFF));
        assertEquals(Argb.luminance(0xFF00FF00), Argb.luminance(0x0000FF00));
        assertEquals(182, Argb.luminance(0xFF00FF00)); // 7152 * 255 / 10000
        assertEquals(18, Argb.luminance(0xFF0000FF)); // 722 * 255 / 10000
    }
}
