package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class MinimapContentViewportTest {
    @Test
    void xaeroSquareFrameInsetsTheMapWithoutMovingItsCenter() {
        final MinimapContentViewport viewport = MinimapContentViewport.resolve(100, 50, 120, 4);

        assertEquals(104, viewport.x());
        assertEquals(54, viewport.y());
        assertEquals(112, viewport.size());
        assertEquals(160f, viewport.centerX());
        assertEquals(110f, viewport.centerY());
    }

    @Test
    void noFrameInsetPreservesTheExistingViewport() {
        final MinimapContentViewport viewport = MinimapContentViewport.resolve(100, 50, 120, 0);

        assertEquals(100, viewport.x());
        assertEquals(50, viewport.y());
        assertEquals(120, viewport.size());
    }

    @Test
    void excessiveInsetCannotInvertTheContentArea() {
        final MinimapContentViewport viewport = MinimapContentViewport.resolve(10, 20, 7, 20);

        assertEquals(13, viewport.x());
        assertEquals(23, viewport.y());
        assertEquals(1, viewport.size());
    }
}
