package cn.net.rms.confluxmap.core.waypoint.library;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class WaypointGridLayoutTest {
    @Test void renderingAndHitTestingShareClampedScrollAndGeometry() {
        var layout = WaypointGridLayout.create(100, 16, 102, 620, 106, true, 9, 10000);
        assertEquals(6, layout.columns()); assertEquals(layout.maxScroll(), layout.scroll());
        for (var cell : layout.cells()) {
            assertEquals(cell, layout.at(cell.x() + 1, cell.y() + 1));
            assertNull(layout.at(cell.x() + cell.width(), cell.y() + 1));
            assertTrue(cell.index() < 100); assertTrue(cell.y() + cell.height() <= 208);
        }
        assertTrue(WaypointGridLayout.create(-1, 0, 0, -1, -1, true, 9, -1).cells().isEmpty());
    }
    @Test void widthAndHeightPercentagesScaleIndependentlyWithinTheViewport() {
        var normal = WaypointGridLayout.create(100, 0, 0, 608, 198, true, 9, 0);
        var narrow = WaypointGridLayout.create(100, 0, 0, 608, 198, true, 9, 0, 30, 100);
        var tall = WaypointGridLayout.create(100, 0, 0, 608, 198, true, 9, 0, 100, 300);
        var small = WaypointGridLayout.create(100, 0, 0, 608, 198, true, 9, 0, 0, 0);
        var constrained = WaypointGridLayout.create(100, 0, 0, 160, 40, true, 9, 0, 300, 999);
        assertEquals(6, normal.columns());
        assertEquals(19, narrow.columns());
        assertEquals(22, narrow.cells().get(0).height());
        assertEquals(normal.columns(), tall.columns());
        assertEquals(66, tall.cells().get(0).height());
        assertEquals(narrow.columns(), small.columns());
        assertEquals(7, small.cells().get(0).height());
        assertEquals(1, constrained.columns());
        assertEquals(new WaypointGridLayout.Cell(0, 0, 0, 160, 40), constrained.at(159, 39));
    }

    @Test void narrowWindowUsesOneColumnAndNeverDrawsPartialRows() {
        var layout = WaypointGridLayout.create(20, 0, 0, 120, 51, true, 9, 0);
        assertEquals(1, layout.columns()); assertEquals(2, layout.cells().size());
        assertEquals(22, layout.cells().get(0).height());
        assertTrue(WaypointGridLayout.create(20, 0, 0, 120, 5, true, 9, 0).cells().isEmpty());
    }
}
