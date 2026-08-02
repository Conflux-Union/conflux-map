package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class FullscreenZoomLabelTest {
    @Test
    void displaysTheInverseBlocksPerPixelAsAZoomMultiplier() {
        assertEquals("1.00x", FullscreenZoomLabel.format(1.0));
        assertEquals("0.25x", FullscreenZoomLabel.format(4.0));
        assertEquals("0.0625x", FullscreenZoomLabel.format(16.0));
        assertEquals(0.125, FullscreenZoomLabel.multiplier(8.0));
        assertTrue(FullscreenZoomLabel.isAtOrBelow(8.0, 0.125));
        assertTrue(FullscreenZoomLabel.isAtOrBelow(16.0, 0.125));
        assertFalse(FullscreenZoomLabel.isAtOrBelow(4.0, 0.125));
        assertFalse(FullscreenZoomLabel.isAtOrBelow(16.0, 0.0));
    }
}
