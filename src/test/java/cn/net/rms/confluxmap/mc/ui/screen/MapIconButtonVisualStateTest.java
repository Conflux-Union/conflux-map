package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

final class MapIconButtonVisualStateTest {
    @Test
    void disabledButtonIgnoresSelectedState() {
        assertEquals(
            MapIconButtonVisualState.of(false, false, false),
            MapIconButtonVisualState.of(false, true, false)
        );
    }

    @Test
    void activeButtonStillShowsSelectedState() {
        assertNotEquals(
            MapIconButtonVisualState.of(true, false, false),
            MapIconButtonVisualState.of(true, true, false)
        );
    }
}
