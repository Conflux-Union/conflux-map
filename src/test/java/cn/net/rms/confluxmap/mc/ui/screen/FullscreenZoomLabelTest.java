package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class FullscreenZoomLabelTest {
    @Test
    void displaysTheInverseBlocksPerPixelAsAZoomMultiplier() {
        assertEquals("1.00x", FullscreenZoomLabel.format(1.0));
        assertEquals("0.25x", FullscreenZoomLabel.format(4.0));
        assertEquals("0.0625x", FullscreenZoomLabel.format(16.0));
    }
}
