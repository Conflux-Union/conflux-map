package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WaypointListChromeTest {
    @Test void nativeControlsKeepOriginalPositionsInBothLayouts() {
        var chrome = WaypointListChrome.create(640, 360, false);
        assertEquals(16, chrome.left()); assertEquals(624, chrome.right());
        assertEquals(280, chrome.headerWidth()); assertEquals(116, chrome.createWidth());
        assertEquals(256, chrome.settingsX()); assertEquals(336, chrome.settingsY());
        assertTrue(chrome.settingsX() >= chrome.left() + 2 * (chrome.createWidth() + 4));
        assertTrue(chrome.settingsX() + chrome.settingsWidth() < chrome.right() - 80);
    }
    @Test void settingsRemainReachableWithoutMovingNativeButtonsAtSmallWidths() {
        for (int width : new int[]{320, 360, 427, 640, 840, 1280}) for (boolean shared : new boolean[]{false, true}) {
            var c = WaypointListChrome.create(width, 240, shared);
            assertTrue(c.settingsWidth() >= 36); assertTrue(c.settingsX() >= c.left());
            assertTrue(c.settingsX() + c.settingsWidth() <= c.right());
            assertTrue(c.settingsY() == 4 || c.settingsY() == 216);
            if (c.settingsY() == 4) assertEquals(c.right() - 64, c.settingsX());
        }
    }
}
