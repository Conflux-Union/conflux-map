package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class StructureSearchScrollBarTest {
    @Test
    void thumbShowsTheVisibleFractionAndTracksTheFinalPage() {
        assertEquals(25, StructureSearchScrollBar.thumbHeight(100, 20, 5));
        assertEquals(10, StructureSearchScrollBar.thumbTop(10, 100, 20, 5, 0));
        assertEquals(85, StructureSearchScrollBar.thumbTop(10, 100, 20, 5, 15));
    }

    @Test
    void thumbFillsTheTrackWhenThereIsNothingToScroll() {
        assertEquals(100, StructureSearchScrollBar.thumbHeight(100, 5, 10));
        assertEquals(10, StructureSearchScrollBar.thumbTop(10, 100, 5, 10, 99));
    }

    @Test
    void trackIsWideEnoughToRemainVisibleBesideTheList() {
        assertEquals(7, StructureSearchScrollBar.trackWidth());
    }
}
