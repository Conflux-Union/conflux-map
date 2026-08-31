package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class WaypointMarkerModeTest {
    @Test
    void existingItemIconSelectsItemMode() {
        assertEquals(WaypointMarkerMode.ITEM, WaypointMarkerMode.initial("minecraft:diamond"));
        assertEquals(WaypointMarkerMode.TEXT, WaypointMarkerMode.initial(""));
        assertEquals(WaypointMarkerMode.TEXT, WaypointMarkerMode.initial(null));
    }

    @Test
    void savesOnlyTheSelectedMarkerRepresentation() {
        assertEquals("", WaypointMarkerMode.TEXT.iconItemId("minecraft:diamond"));
        assertEquals("HOME", WaypointMarkerMode.TEXT.markerLabel("HOME"));
        assertEquals("minecraft:diamond", WaypointMarkerMode.ITEM.iconItemId("minecraft:diamond"));
        assertEquals("", WaypointMarkerMode.ITEM.markerLabel("HOME"));
    }
}
