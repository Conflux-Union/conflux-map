package cn.net.rms.confluxmap.core.waypoint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

final class WaypointMarkerStyleTest {
    @Test
    void markerLabelAllowsAtMostThreeUnicodeCodePoints() {
        assertEquals("", WaypointMarkerStyle.markerLabel(null));
        assertEquals("", WaypointMarkerStyle.markerLabel("  "));
        assertEquals("\u5bb6\uD83D\uDE80A", WaypointMarkerStyle.markerLabel(" \u5bb6\uD83D\uDE80A "));
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointMarkerStyle.markerLabel("\u5bb6\uD83D\uDE80AB")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointMarkerStyle.markerLabel("\u00a7k")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointMarkerStyle.markerLabel("A\u202EB")
        );
    }

    @Test
    void iconItemIdAcceptsOnlyVanillaResourceIdentifiers() {
        assertEquals("", WaypointMarkerStyle.iconItemId(null));
        assertEquals("", WaypointMarkerStyle.iconItemId("  "));
        assertEquals("minecraft:diamond", WaypointMarkerStyle.iconItemId("minecraft:diamond"));
        assertEquals(
            "minecraft:music_disc/cat",
            WaypointMarkerStyle.iconItemId("minecraft:music_disc/cat")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointMarkerStyle.iconItemId("example:diamond")
        );
        assertThrows(
            IllegalArgumentException.class,
            () -> WaypointMarkerStyle.iconItemId("minecraft:Diamond")
        );
    }
}
