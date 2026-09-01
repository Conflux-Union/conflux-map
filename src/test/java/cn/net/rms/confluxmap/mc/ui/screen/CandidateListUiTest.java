package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CandidateListUiTest {
    @Test
    void twoLineRowsShareOneLayoutForTextAndActions() {
        final CandidateListUi ui = new CandidateListUi(240, 20, 230, 10, 0);

        assertEquals(2, ui.visibleRows());
        assertEquals(44, ui.rowHeight());
        assertEquals(150, ui.textWidth());
        assertEquals(174, ui.actionX());
        assertEquals(76, ui.actionWidth());
        assertEquals(112, ui.mapButtonY(0));
        assertEquals(134, ui.waypointButtonY(0));
        assertEquals(155, ui.dividerY(0));
    }

    @Test
    void scrollOffsetIsClampedToTheSharedVisibleRowCount() {
        final CandidateListUi ui = new CandidateListUi(240, 20, 230, 10, 20);

        assertEquals(8, ui.scrollOffset());
        assertEquals(112, ui.rowY(8));
    }

    @Test
    void separatorsOnlyCoverRenderedCandidates() {
        assertEquals(1, new CandidateListUi(240, 20, 230, 1, 0).renderedRows());
        assertEquals(2, new CandidateListUi(240, 20, 230, 10, 0).renderedRows());
    }

    @Test
    void coordinateAndDistanceFormattingIsShared() {
        assertEquals("-120, 34", CandidateListUi.coordinateText(-120, 34));
        assertEquals(125, CandidateListUi.distanceInBlocks(-120, 34, 0, 0));
    }
}
