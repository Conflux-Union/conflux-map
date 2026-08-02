package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
