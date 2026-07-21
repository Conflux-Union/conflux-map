package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WaypointMarkerRendererTest {
    @Test
    void extractsOneCompleteCodePointFromTheTrimmedName() {
        assertEquals("B", WaypointMarkerRenderer.initial("Base"));
        assertEquals("\u77ff", WaypointMarkerRenderer.initial("\u77ff\u6d1e"));
        assertEquals("\uD83D\uDE80", WaypointMarkerRenderer.initial("\uD83D\uDE80 Launch"));
        assertEquals("B", WaypointMarkerRenderer.initial(" \tBase \n"));
    }

    @Test
    void fallsBackForAnEmptyName() {
        assertEquals("?", WaypointMarkerRenderer.initial(""));
        assertEquals("?", WaypointMarkerRenderer.initial(" \t\n"));
    }
}
