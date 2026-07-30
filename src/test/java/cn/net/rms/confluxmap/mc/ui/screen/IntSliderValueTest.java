package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class IntSliderValueTest {
    @Test
    void textChangesMoveTheSliderPositionAndClampToTheRange() {
        final IntSliderValue value = new IntSliderValue(64, 256, 128);

        assertEquals(1.0 / 3.0, value.position(), 0.000_001);

        assertTrue(value.updateFromText("200"));
        assertEquals(200, value.value());
        assertEquals(136.0 / 192.0, value.position(), 0.000_001);

        assertTrue(value.updateFromText("2"));
        assertEquals(64, value.value());
        assertEquals(0.0, value.position());

        assertTrue(value.updateFromText("999"));
        assertEquals(256, value.value());
        assertEquals(1.0, value.position());
    }

    @Test
    void sliderChangesProduceTheIntegerShownInTheInput() {
        final IntSliderValue value = new IntSliderValue(1, 500, 1);

        assertEquals(251, value.updateFromPosition(0.5));
        assertEquals("251", value.text());
        assertEquals(1, value.updateFromPosition(-1.0));
        assertEquals(500, value.updateFromPosition(2.0));
    }

    @Test
    void incompleteTextLeavesTheCurrentSliderValueAlone() {
        final IntSliderValue value = new IntSliderValue(0, 100, 40);

        assertFalse(value.updateFromText(""));
        assertFalse(value.updateFromText("+"));
        assertFalse(value.updateFromText("-"));
        assertFalse(value.updateFromText("not-a-number"));
        assertEquals(40, value.value());
        assertEquals("40", value.text());
    }

    @Test
    void constructorClampsInitialValueAndRejectsAnInvertedRange() {
        assertEquals(4, new IntSliderValue(4, 64, -1).value());
        assertEquals(64, new IntSliderValue(4, 64, 100).value());
        assertEquals(0.0, new IntSliderValue(7, 7, 7).position());
        assertThrows(IllegalArgumentException.class, () -> new IntSliderValue(8, 7, 7));
    }
}
