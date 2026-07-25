package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

class ShadingPipelineRelightTest {
    private static final int LIT = 0xFF64A05A;
    private static final float[] FACTORS = {0f, 0.2f, 0.45f, 0.8f, 1f};

    /**
     * Re-lighting a tile darkened at one factor must land on what a fresh compose at the
     * target produces, up to quantization: the bake truncates up to one count per channel
     * ({@code Argb.scale}), the ratio amplifies that loss by up to {@code scale(to)/scale(from)}
     * (at most 1/DAYLIGHT_FLOOR = 3.34x), and the re-light rounds once more - so the exact
     * bound per channel is {@code ratio + 1.5}.
     */
    @Test
    void relightMatchesDirectComposeWithinQuantizationBound() {
        for (final float from : FACTORS) {
            for (final float to : FACTORS) {
                final float[] ratios = ShadingPipeline.relightRatios(from, to);
                for (int level = 0; level <= 15; level++) {
                    final int baked = ShadingPipeline.applyDaylight(LIT, from, level);
                    final int relit = ShadingPipeline.applyBrightnessMultiplier(baked, ratios[level]);
                    final int direct = ShadingPipeline.applyDaylight(LIT, to, level);
                    final int tolerance = (int) Math.floor(ratios[level] + 1.5f);
                    assertChannelsClose(direct, relit, tolerance, from + "->" + to + " @L" + level);
                }
            }
        }
    }

    /** A fully torch-lit pixel never changes with the sky: its ratio must be exactly 1. */
    @Test
    void fullBlockLightIsExactIdentity() {
        for (final float from : FACTORS) {
            for (final float to : FACTORS) {
                assertEquals(1f, ShadingPipeline.relightRatios(from, to)[15]);
            }
        }
    }

    @Test
    void sameFactorIsIdentityAtEveryLevel() {
        for (final float factor : FACTORS) {
            final float[] ratios = ShadingPipeline.relightRatios(factor, factor);
            for (int level = 0; level <= 15; level++) {
                assertEquals(1f, ratios[level]);
            }
        }
    }

    private static void assertChannelsClose(final int expected, final int actual, final int tolerance, final String context) {
        assertEquals(Argb.alpha(expected), Argb.alpha(actual), "alpha " + context);
        assertTrue(
            Math.abs(Argb.red(expected) - Argb.red(actual)) <= tolerance
                && Math.abs(Argb.green(expected) - Argb.green(actual)) <= tolerance
                && Math.abs(Argb.blue(expected) - Argb.blue(actual)) <= tolerance,
            () -> String.format("%s: expected %08x, got %08x (tolerance %d)", context, expected, actual, tolerance)
        );
    }
}
