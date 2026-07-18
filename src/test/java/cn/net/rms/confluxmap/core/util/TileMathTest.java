package cn.net.rms.confluxmap.core.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class TileMathTest {
    @Test
    void zoomStepsNeverMinifyTheSelectedLodTexture() {
        assertEquals(0, TileMath.lodForScale(0.25));
        assertEquals(0, TileMath.lodForScale(1.0));
        assertEquals(1, TileMath.lodForScale(1.01));
        assertEquals(1, TileMath.lodForScale(1.26));
        assertEquals(1, TileMath.lodForScale(2.0));
        assertEquals(2, TileMath.lodForScale(2.01));
        assertEquals(2, TileMath.lodForScale(2.52));
        assertEquals(4, TileMath.lodForScale(16.0));
    }

    @Test
    void selectedTexelCoversAtLeastOneScreenPixelUntilMaximumLod() {
        for (double scale = 0.25; scale <= 16.0; scale *= 1.01) {
            final int lod = TileMath.lodForScale(scale);
            assertTrue(TileMath.blocksPerPixel(lod) + 1.0e-9 >= scale);
        }
    }
}
