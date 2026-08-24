package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MinimapHudVisibilityTest {
    @Test
    void hidesTheHudForFullscreenContainerAndDebugOverlays() {
        assertTrue(MinimapHudVisibility.shouldRender(true, true, false, false, false));
        assertFalse(MinimapHudVisibility.shouldRender(true, true, true, false, false));
        assertFalse(MinimapHudVisibility.shouldRender(true, true, false, true, false));
        assertFalse(MinimapHudVisibility.shouldRender(true, true, false, false, true));
        assertFalse(MinimapHudVisibility.shouldRender(false, true, false, false, false));
        assertFalse(MinimapHudVisibility.shouldRender(true, false, false, false, false));
    }
}
