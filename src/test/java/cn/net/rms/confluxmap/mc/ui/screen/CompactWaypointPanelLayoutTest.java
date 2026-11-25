package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.waypoint.library.WaypointGridLayout.Cell;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompactWaypointPanelLayoutTest {
    @Test void panelTouchesTheCellOnEitherHorizontalEdge() {
        var left = CompactWaypointPanelLayout.place(new Cell(0, 16, 128, 96, 15), 640, 360, 116);
        assertEquals(new CompactWaypointPanelLayout(112, 128, 168, 116), left);
        var right = CompactWaypointPanelLayout.place(new Cell(1, 528, 290, 96, 15), 640, 360, 116);
        assertEquals(new CompactWaypointPanelLayout(360, 216, 168, 116), right);
        assertTrue(right.contains(527, 290));
        assertFalse(right.contains(528, 290));
    }

    @Test void diagonalApproachDoesNotSwitchToAnUnderlyingCell() {
        var cell = new Cell(0, 16, 128, 96, 15);
        var panel = CompactWaypointPanelLayout.place(cell, 640, 360, 116);
        assertTrue(panel.onApproach(cell, 64, 135, 100, 170));
        assertFalse(panel.onApproach(cell, 64, 135, 64, 170));
        assertFalse(panel.onApproach(cell, 64, 135, 100, 120));
    }

    @Test void narrowWindowUsesAnAdjacentVerticalEdgeAndAvoidsFooter() {
        var bottom = CompactWaypointPanelLayout.place(new Cell(0, 100, 130, 96, 15), 320, 360, 104);
        assertEquals(new CompactWaypointPanelLayout(100, 145, 168, 104), bottom);
        var top = CompactWaypointPanelLayout.place(new Cell(1, 100, 184, 96, 15), 320, 240, 104);
        assertEquals(new CompactWaypointPanelLayout(100, 80, 168, 104), top);
        assertFalse(top.contains(110, 184));
    }
}
