package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WaypointMarkerRendererTest {
    @Test
    void extractsTheFirstVisibleCompleteCodePoint() {
        assertEquals("B", WaypointMarkerRenderer.initial("Base"));
        assertEquals("\u77ff", WaypointMarkerRenderer.initial("\u77ff\u6d1e"));
        assertEquals("\uD83D\uDE80", WaypointMarkerRenderer.initial("\uD83D\uDE80 Launch"));
        assertEquals("B", WaypointMarkerRenderer.initial(" \tBase \n"));
    }

    @Test
    void skipsUnicodeWhitespaceThatTrimWouldKeep() {
        assertEquals("B", WaypointMarkerRenderer.initial("\u00A0Base"));
        assertEquals("B", WaypointMarkerRenderer.initial("\u3000Base"));
    }

    @Test
    void fallsBackForAnEmptyName() {
        assertEquals("?", WaypointMarkerRenderer.initial(""));
        assertEquals("?", WaypointMarkerRenderer.initial(" \t\n"));
        assertEquals("?", WaypointMarkerRenderer.initial("\u00A0\u3000"));
        assertEquals("?", WaypointMarkerRenderer.initial(null));
    }
}
