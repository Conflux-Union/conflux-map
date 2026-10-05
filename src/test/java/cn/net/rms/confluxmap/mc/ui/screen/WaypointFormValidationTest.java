package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class WaypointFormValidationTest {
    @Test
    void rejectsIncompleteNonFiniteAndMissingValues() {
        assertEquals(
            WaypointFormValidation.Error.NAME_REQUIRED,
            WaypointFormValidation.error(" ", "1", "2", "3").orElseThrow()
        );
        assertEquals(
            WaypointFormValidation.Error.INVALID_COORDINATES,
            WaypointFormValidation.error("home", "-", "2", "3").orElseThrow()
        );
        assertEquals(
            WaypointFormValidation.Error.INVALID_COORDINATES,
            WaypointFormValidation.error("home", "NaN", "2", "3").orElseThrow()
        );
        assertEquals(
            WaypointFormValidation.Error.INVALID_COORDINATES,
            WaypointFormValidation.error("home", "1e999", "2", "3").orElseThrow()
        );
    }

    @Test
    void returnsTrimmedNameAndExactCoordinates() {
        final WaypointFormValidation.Values values = WaypointFormValidation.values(
            "  portal  ", "-12.5", "64", "3.25e2"
        );

        assertEquals("portal", values.name());
        assertEquals(-12.5, values.x());
        assertEquals(64.0, values.y());
        assertEquals(325.0, values.z());
        assertTrue(WaypointFormValidation.error("portal", "0", "0", "0").isEmpty());
    }

    @Test
    void optionalCoordinateMapsBlankToNullAndRejectsUnusableText() {
        assertNull(WaypointFormValidation.optionalCoordinate(null));
        assertNull(WaypointFormValidation.optionalCoordinate("  "));
        assertEquals(120.0, WaypointFormValidation.optionalCoordinate(" 120 "));
        assertEquals(-64.5, WaypointFormValidation.optionalCoordinate("-64.5"));

        assertThrows(
            NumberFormatException.class,
            () -> WaypointFormValidation.optionalCoordinate("1e")
        );
        assertThrows(
            NumberFormatException.class,
            () -> WaypointFormValidation.optionalCoordinate("NaN")
        );
        assertFalse(WaypointFormValidation.optionalCoordinateValid("1e"));
        assertFalse(WaypointFormValidation.optionalCoordinateValid("Infinity"));
        assertTrue(WaypointFormValidation.optionalCoordinateValid(""));
        assertTrue(WaypointFormValidation.optionalCoordinateValid("70"));
    }
}
