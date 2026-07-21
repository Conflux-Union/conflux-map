package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class DropdownScrollTest {
    @Test
    void wheelScrollingIsClampedToAvailableOptions() {
        assertEquals(0, DropdownScroll.afterWheel(0, 1.0, 10, 4));
        assertEquals(1, DropdownScroll.afterWheel(0, -1.0, 10, 4));
        assertEquals(6, DropdownScroll.afterWheel(6, -1.0, 10, 4));
    }

    @Test
    void selectedOptionIsVisibleWhenDropdownOpens() {
        assertEquals(0, DropdownScroll.ensureVisible(2, 10, 4));
        assertEquals(4, DropdownScroll.ensureVisible(7, 10, 4));
        assertEquals(6, DropdownScroll.ensureVisible(9, 10, 4));
    }

    @Test
    void keyboardNavigationKeepsTheFocusedOptionVisible() {
        assertEquals(2, DropdownScroll.keepVisible(3, 2, 10, 4));
        assertEquals(4, DropdownScroll.keepVisible(2, 7, 10, 4));
        assertEquals(3, DropdownScroll.keepVisible(3, 5, 10, 4));
    }

    @Test
    void thumbPositionMapsAcrossTheFullScrollRange() {
        assertEquals(0, DropdownScroll.fromThumbPosition(5, 0, 100, 20, 10, 4));
        assertEquals(3, DropdownScroll.fromThumbPosition(50, 0, 100, 20, 10, 4));
        assertEquals(6, DropdownScroll.fromThumbPosition(95, 0, 100, 20, 10, 4));
    }
}
