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

    @Test
    void waypointIconsUseNarrowSlotsWhileTextActionsStayWide() {
        final WaypointRowActionLayout layout = WaypointRowActionLayout.create(
            10, 890, 6, 4,
            new int[] {20, 20, 48, 48, 48, 48}
        );

        assertEquals(20, layout.width(0));
        assertEquals(20, layout.width(1));
        assertEquals(48, layout.width(2));
        assertEquals(632, layout.x(0));
        assertEquals(656, layout.x(1));
        assertEquals(680, layout.x(2));
        assertEquals(836, layout.x(5));
        assertEquals(890 - 6, layout.right());
    }

    @Test
    void narrowRowsShrinkTextActionsBeforeIconSlots() {
        final WaypointRowActionLayout layout = WaypointRowActionLayout.create(
            10, 144, 6, 4,
            new int[] {20, 20, 48, 48, 48}
        );

        assertEquals(20, layout.width(0));
        assertEquals(20, layout.width(1));
        assertEquals(22, layout.width(2));
        assertEquals(22, layout.width(3));
        assertEquals(22, layout.width(4));
        assertEquals(16, layout.x(0));
        assertEquals(144 - 6, layout.right());
    }
}
