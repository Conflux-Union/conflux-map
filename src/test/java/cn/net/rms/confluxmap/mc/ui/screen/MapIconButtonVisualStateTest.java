package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.mc.ui.UiIcon;
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

    @Test
    void selectedFullColorIconKeepsItsTextureColorsOnADarkBackground() {
        final MapIconButtonVisualState state = MapIconButtonVisualState.of(
            true, true, false, UiIcon.ColorMode.FULL_COLOR
        );

        assertEquals(0xE0181818, state.background());
        assertEquals(0xFFFFFFFF, state.iconTint());
        assertNotEquals(
            MapIconButtonVisualState.of(true, false, false, UiIcon.ColorMode.FULL_COLOR),
            state
        );
    }

    @Test
    void vanillaResourcePackStyleKeepsTheButtonTextureVisible() {
        final MapIconButtonVisualState idle = MapIconButtonVisualState.of(
            true, false, false, UiIcon.ColorMode.MONOCHROME_MASK, true
        );
        final MapIconButtonVisualState selected = MapIconButtonVisualState.of(
            true, true, false, UiIcon.ColorMode.MONOCHROME_MASK, true
        );

        assertEquals(0, idle.background());
        assertEquals(0, idle.border());
        assertEquals(0, selected.background());
        assertEquals(0xFFFFFFFF, selected.border());
        assertEquals(0xFFFFFFFF, selected.iconTint());
    }
}
