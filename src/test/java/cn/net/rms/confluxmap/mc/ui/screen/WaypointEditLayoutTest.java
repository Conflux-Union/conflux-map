package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

final class WaypointEditLayoutTest {
    /** The narrowest supported GUI size; the full form bottoms out its rhythm here. */
    private static final int MIN_GUI_HEIGHT = 240;
    private static final int MAX_GUI_HEIGHT = 480;

    @Test
    void fullFormAtMinimumSizeStaysSeparateAndClearsTheFooter() {
        final WaypointEditLayout.Rows rows = WaypointEditLayout.rows(MIN_GUI_HEIGHT, true);

        assertEquals(1, rows.gap());
        assertEquals(34, rows.nameFieldY());
        assertEquals(65, rows.coordsY());
        assertEquals(96, rows.groupY());
        assertEquals(127, rows.swatchesY());
        assertEquals(148, rows.optionsY());
        assertEquals(179, rows.markerY());
        assertEquals(201, rows.statusY());
        assertTrue(rows.markerY() + WaypointEditLayout.FIELD_HEIGHT
            <= WaypointEditLayout.footerY(MIN_GUI_HEIGHT) - WaypointEditLayout.FOOTER_CLEARANCE);
    }

    @Test
    void fullFormAtComfortableSizeSpreadsToACappedRhythm() {
        final WaypointEditLayout.Rows rows = WaypointEditLayout.rows(300, true);

        assertEquals(11, rows.gap());
        assertEquals(34, rows.nameFieldY());
        assertEquals(75, rows.coordsY());
        assertEquals(116, rows.groupY());
        assertEquals(157, rows.swatchesY());
        assertEquals(188, rows.optionsY());
        assertEquals(229, rows.markerY());
        assertEquals(256, rows.statusY());
    }

    @Test
    void chatFormTightensOverTheDroppedRowsInsteadOfKeepingHoles() {
        final WaypointEditLayout.Rows rows = WaypointEditLayout.rows(MIN_GUI_HEIGHT, false);

        assertEquals(12, rows.gap());
        assertEquals(34, rows.nameFieldY());
        assertEquals(76, rows.coordsY());
        assertEquals(118, rows.swatchesY());
        assertEquals(150, rows.optionsY());
        assertEquals(196, rows.statusY());
    }

    @Test
    void everySupportedHeightKeepsVisibleRowsApartAndInsideTheFooterClearance() {
        for (int height = MIN_GUI_HEIGHT; height <= MAX_GUI_HEIGHT; height++) {
            for (final boolean fullForm : new boolean[] {true, false}) {
                final WaypointEditLayout.Rows rows = WaypointEditLayout.rows(height, fullForm);
                final List<int[]> spans = visibleSpans(rows, fullForm);
                for (int i = 1; i < spans.size(); i++) {
                    assertTrue(spans.get(i)[0] > spans.get(i - 1)[1],
                        "elements touch or overlap at height " + height + ", fullForm " + fullForm);
                }
                final int contentBottom = spans.get(spans.size() - 1)[1];
                final int footerTop = WaypointEditLayout.footerY(height);
                assertTrue(contentBottom <= footerTop - WaypointEditLayout.FOOTER_CLEARANCE,
                    "footer clearance broken at height " + height + ", fullForm " + fullForm);
                assertTrue(rows.statusY() >= contentBottom + WaypointEditLayout.STATUS_GAP,
                    "status line covers the form at height " + height + ", fullForm " + fullForm);
                // Below 242 the form consumes the reserved slot; the line then crowds
                // the footer edge by at most two pixels instead of the form itself.
                if (height >= 242) {
                    assertTrue(rows.statusY() + WaypointEditLayout.LABEL_HEIGHT <= footerTop,
                        "status line covers the footer at height " + height + ", fullForm " + fullForm);
                }
            }
        }
    }

    @Test
    void rowRhythmIsUniformAndGrowsWithTheAvailableHeight() {
        int previousGap = 0;
        for (int height = MIN_GUI_HEIGHT; height <= MAX_GUI_HEIGHT; height++) {
            final WaypointEditLayout.Rows rows = WaypointEditLayout.rows(height, true);
            final int labeledPitch =
                WaypointEditLayout.LABEL_HEIGHT + WaypointEditLayout.LABEL_CONTROL_GAP
                    + WaypointEditLayout.FIELD_HEIGHT + rows.gap();
            assertEquals(labeledPitch, rows.coordsY() - rows.nameFieldY(), "name to coords pitch");
            assertEquals(labeledPitch, rows.groupY() - rows.coordsY(), "coords to group pitch");
            assertEquals(labeledPitch, rows.swatchesY() - rows.groupY(), "group to swatches pitch");
            assertEquals(
                WaypointEditLayout.FIELD_HEIGHT + rows.gap(),
                rows.optionsY() - rows.swatchesY(),
                "swatches to options pitch"
            );
            assertEquals(labeledPitch, rows.markerY() - rows.optionsY(), "options to marker pitch");
            assertTrue(rows.gap() >= previousGap, "gap shrank at height " + height);
            assertTrue(rows.gap() <= WaypointEditLayout.MAX_ROW_GAP);
            previousGap = rows.gap();
        }
        assertEquals(8, WaypointEditLayout.rows(285, true).gap());
        assertEquals(WaypointEditLayout.MAX_ROW_GAP, WaypointEditLayout.rows(305, true).gap());
    }

    @Test
    void centeredRowsStayInsideThe320pxMinimumWidth() {
        final int centerX = 160;
        assertEquals(228, WaypointEditLayout.swatchRowWidth(8));
        assertEquals(228, WaypointEditLayout.optionsRowWidth(true));
        assertEquals(68, WaypointEditLayout.optionsRowWidth(false));
        final int[] rowWidths = {
            WaypointEditLayout.FORM_WIDTH,
            WaypointEditLayout.swatchRowWidth(8),
            WaypointEditLayout.optionsRowWidth(true),
            WaypointEditLayout.optionsRowWidth(false)
        };
        for (final int rowWidth : rowWidths) {
            final int left = centerX - rowWidth / 2;
            assertTrue(left > centerX - 160, "row leaves the left edge: " + rowWidth);
            assertTrue(left + rowWidth < centerX + 160, "row leaves the right edge: " + rowWidth);
        }
    }

    @Test
    void localSetRowSplitsIntoCyclerAndTemporaryToggle() {
        assertEquals(WaypointEditLayout.FORM_WIDTH,
            WaypointEditLayout.SET_SELECTOR_WIDTH + WaypointEditLayout.OPTION_GAP
                + WaypointEditLayout.TEMPORARY_WIDTH,
            "local set row");
        assertEquals(154, WaypointEditLayout.SET_SELECTOR_WIDTH);
        assertEquals(68, WaypointEditLayout.TEMPORARY_WIDTH);
    }

    @Test
    void everyRowSpansTheSameFormWidth() {
        final int w = WaypointEditLayout.FORM_WIDTH;
        assertEquals(w, WaypointEditLayout.COORD_WIDTH * 3 + WaypointEditLayout.COORD_GAP * 2,
            "coordinate triple");
        assertEquals(w, WaypointEditLayout.swatchRowWidth(8), "swatch grid");
        assertEquals(w, WaypointEditLayout.optionsRowWidth(true), "options row");
        assertEquals(w, WaypointEditLayout.MARKER_MODE_WIDTH + WaypointEditLayout.MARKER_GAP
                + WaypointEditLayout.MARKER_CONTROL_WIDTH, "marker row");
        assertEquals(WaypointEditLayout.MARKER_CONTROL_WIDTH,
            WaypointEditLayout.MARKER_ICON_WIDTH + WaypointEditLayout.MARKER_GAP
                + WaypointEditLayout.MARKER_CLEAR_WIDTH, "marker icon pair fills the control slot");
        assertEquals(w, WaypointEditLayout.FOOTER_EDIT_WIDTH * 3 + WaypointEditLayout.FOOTER_GAP * 2,
            "editing footer");
        assertEquals(w, WaypointEditLayout.FOOTER_CREATE_WIDTH * 2 + WaypointEditLayout.FOOTER_CREATE_GAP,
            "creating footer");
    }

    @Test
    void captionsLeadTheirControlRow() {
        assertEquals(24, WaypointEditLayout.labelY(34));
    }

    /** Inclusive-top, exclusive-bottom spans of every drawn element, in draw order. */
    private static List<int[]> visibleSpans(final WaypointEditLayout.Rows rows, final boolean fullForm) {
        final List<int[]> spans = new ArrayList<>();
        spans.add(new int[] {WaypointEditLayout.TITLE_Y, WaypointEditLayout.TITLE_Y + WaypointEditLayout.LABEL_HEIGHT});
        final int[] controlTops = fullForm
            ? new int[] {
                rows.nameFieldY(), rows.coordsY(), rows.groupY(),
                rows.swatchesY(), rows.optionsY(), rows.markerY()
            }
            : new int[] {rows.nameFieldY(), rows.coordsY(), rows.swatchesY(), rows.optionsY()};
        final boolean[] labeled = fullForm
            ? new boolean[] {true, true, true, true, false, true}
            : new boolean[] {true, true, true, false};
        for (int i = 0; i < controlTops.length; i++) {
            if (labeled[i]) {
                spans.add(new int[] {
                    WaypointEditLayout.labelY(controlTops[i]),
                    WaypointEditLayout.labelY(controlTops[i]) + WaypointEditLayout.LABEL_HEIGHT
                });
            }
            spans.add(new int[] {controlTops[i], controlTops[i] + WaypointEditLayout.FIELD_HEIGHT});
        }
        return spans;
    }
}
