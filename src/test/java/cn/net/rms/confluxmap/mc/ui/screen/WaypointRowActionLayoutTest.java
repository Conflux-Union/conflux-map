package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WaypointRowActionLayoutTest {
    @Test
    void wideRowsKeepFiveActionsCompactAndRightAligned() {
        final WaypointRowActionLayout layout = WaypointRowActionLayout.create(
            10, 890, 6, 4, 5, 48
        );

        assertEquals(48, layout.width());
        assertEquals(628, layout.x(0));
        assertEquals(836, layout.x(4));
        assertEquals(890 - 6, layout.right());
    }

    @Test
    void narrowRowsShrinkAllFiveActionsToFit() {
        final WaypointRowActionLayout layout = WaypointRowActionLayout.create(
            10, 244, 6, 4, 5, 48
        );

        assertEquals(41, layout.width());
        assertEquals(17, layout.x(0));
        assertEquals(197, layout.x(4));
        assertEquals(244 - 6, layout.right());
    }
}
