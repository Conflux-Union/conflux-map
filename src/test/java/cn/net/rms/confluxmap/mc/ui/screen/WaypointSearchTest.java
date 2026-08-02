package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WaypointSearchTest {
    @Test
    void matchesNamesSetsDimensionsAndCoordinatesCaseInsensitively() {
        assertTrue(matches("home"));
        assertTrue(matches("FAVORITES"));
        assertTrue(matches("nether"));
        assertTrue(matches("-12.5"));
        assertTrue(matches("  castle  "));
        assertFalse(matches("village"));
    }

    @Test
    void emptyQueryKeepsEveryWaypointVisible() {
        assertTrue(matches("   "));
    }

    private static boolean matches(final String query) {
        return WaypointSearch.matches(
            query,
            "Castle Home",
            "Favorites",
            "The Nether",
            -12.5,
            64.0,
            320.25
        );
    }
}
