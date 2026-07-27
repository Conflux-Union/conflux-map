package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TileMathTest {
    @Test
    void zoomStepsKeepFullResolutionUntilTheNextPowerOfTwo() {
        assertEquals(0, TileMath.lodForScale(0.25));
        assertEquals(0, TileMath.lodForScale(1.0));
        assertEquals(0, TileMath.lodForScale(1.26));
        assertEquals(1, TileMath.lodForScale(2.0));
        assertEquals(1, TileMath.lodForScale(2.52));
        assertEquals(2, TileMath.lodForScale(4.0));
        assertEquals(2, TileMath.lodForScale(6.0));
        assertEquals(3, TileMath.lodForScale(8.0));
        assertEquals(4, TileMath.lodForScale(16.0));
    }

    @Test
    void selectedTexelStaysWithinAConstantScreenDensity() {
        for (double scale = 1.0; scale <= 16.0; scale *= 1.01) {
            final int lod = TileMath.lodForScale(scale);
            final double screenPixelsPerTexel = TileMath.blocksPerPixel(lod) / scale;
            assertTrue(
                screenPixelsPerTexel <= 1.0 + 1.0e-9,
                "LOD " + lod + " expands one texel over " + screenPixelsPerTexel + " screen pixels"
            );
            assertTrue(
                screenPixelsPerTexel >= 0.5 - 1.0e-9,
                "LOD " + lod + " drops below half-pixel detail at " + screenPixelsPerTexel
            );
        }
    }
}
