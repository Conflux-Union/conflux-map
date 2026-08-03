package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SplitMapLayoutTest {
    @Test
    void dividesTheScreenIntoEqualMapAndPanelHalves() {
        final SplitMapLayout layout = new SplitMapLayout(320, 240);

        assertEquals(160, layout.mapWidth());
        assertEquals(160, layout.panelLeft());
        assertEquals(160, layout.panelWidth());
        assertEquals(80.0, layout.mapCenterX());
        assertEquals(240, layout.panelCenterX());
        assertEquals(168, layout.panelContentLeft());
        assertEquals(144, layout.panelContentWidth());
    }

    @Test
    void routesPointerInputAtTheDividerToThePanel() {
        final SplitMapLayout layout = new SplitMapLayout(321, 240);

        assertTrue(layout.containsMap(159.9, 120));
        assertFalse(layout.containsMap(160, 120));
        assertTrue(layout.containsPanel(160, 120));
        assertFalse(layout.containsPanel(159.9, 120));
    }
}
