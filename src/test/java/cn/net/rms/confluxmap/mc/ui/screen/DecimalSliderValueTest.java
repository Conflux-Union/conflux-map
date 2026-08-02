package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DecimalSliderValueTest {
    @Test
    void acceptsSubQuarterThresholdsAndQuantizesToTheConfiguredStep() {
        final DecimalSliderValue value = new DecimalSliderValue(0.0, 16.0, 0.0625, 0.125);

        assertTrue(value.updateFromText("0.125"));
        assertEquals(0.125, value.value());
        assertEquals("0.125", value.text());

        assertTrue(value.updateFromText("0.14"));
        assertEquals(0.125, value.value());
        assertEquals(0.0, value.updateFromPosition(0.0));
    }

    @Test
    void clampsValuesAndKeepsIncompleteTextOutOfTheConfiguration() {
        final DecimalSliderValue value = new DecimalSliderValue(0.0, 16.0, 0.0625, 0.125);

        assertTrue(value.updateFromText("0"));
        assertEquals(0.0, value.value());
        assertTrue(value.updateFromText("99"));
        assertEquals(16.0, value.value());
        assertFalse(value.updateFromText("."));
        assertFalse(value.updateFromText("not-a-scale"));
        assertEquals(16.0, value.value());
    }

    @Test
    void rejectsInvalidRanges() {
        assertThrows(IllegalArgumentException.class, () -> new DecimalSliderValue(1.0, 0.5, 0.25, 1.0));
        assertThrows(IllegalArgumentException.class, () -> new DecimalSliderValue(0.0, 1.0, 0.0, 1.0));
    }
}
