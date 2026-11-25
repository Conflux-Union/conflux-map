package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class SliderInputTest {
    @Test
    void inputWidthIsAQuarterBetweenFortyAndFiftySix() {
        assertEquals(50, SliderInput.inputWidth(200));
        assertEquals(56, SliderInput.inputWidth(400));
        assertEquals(40, SliderInput.inputWidth(120));
    }

    @Test
    void inputWidthNeverLeavesNoRoomForTheSlider() {
        assertEquals(15, SliderInput.inputWidth(20));
        assertEquals(1, SliderInput.inputWidth(5));
    }

    @Test
    void sliderWidthLeavesAGapOfFour() {
        assertEquals(146, SliderInput.sliderWidth(200));
        assertEquals(1, SliderInput.sliderWidth(5));
    }
}
