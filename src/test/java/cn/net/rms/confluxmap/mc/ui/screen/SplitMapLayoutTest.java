package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class SplitMapLayoutTest {
    @Test
    void sizesThePanelToItsContentAndKeepsTheMapFullWidth() {
        final SplitMapLayout layout = new SplitMapLayout(600, 240, 180);

        assertEquals(404, layout.mapWidth());
        assertEquals(600, layout.mapRenderWidth());
        assertEquals(404, layout.panelLeft());
        assertEquals(196, layout.panelWidth());
        assertEquals(202.0, layout.mapCenterX());
        assertEquals(502, layout.panelCenterX());
        assertEquals(412, layout.panelContentLeft());
        assertEquals(180, layout.panelContentWidth());
    }

    @Test
    void capsThePanelAtHalfTheScreenWidth() {
        final SplitMapLayout layout = new SplitMapLayout(600, 240, 500);

        assertEquals(300, layout.mapWidth());
        assertEquals(300, layout.panelWidth());
        assertEquals(284, layout.panelContentWidth());
    }

    @Test
    void routesPointerInputAtTheDividerToThePanel() {
        final SplitMapLayout layout = new SplitMapLayout(600, 240, 180);

        assertTrue(layout.containsMap(403.9, 120));
        assertFalse(layout.containsMap(404, 120));
        assertTrue(layout.containsPanel(404, 120));
        assertFalse(layout.containsPanel(403.9, 120));
    }

    @Test
    void shiftsTheFullMapSoItsViewpointStaysCenteredInTheLeftRegion() {
        final SplitMapLayout layout = new SplitMapLayout(600, 240, 180);

        assertEquals(246.0, layout.renderCenterX(50.0, 2.0));
    }

    @Test
    void placesTheScaleLabelAtTheTopRightOfTheLeftMapRegion() {
        final SplitMapLayout layout = new SplitMapLayout(600, 240, 180);

        assertEquals(354, layout.mapRightAlignedX(40, 10));
    }
}
