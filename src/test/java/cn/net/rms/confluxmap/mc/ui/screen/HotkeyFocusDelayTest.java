package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class HotkeyFocusDelayTest {
    @Test
    void openingHotkeyCharacterCannotEnterSearchBeforeTheNextTick() {
        final HotkeyFocusDelay focus = new HotkeyFocusDelay();
        final StringBuilder search = new StringBuilder();
        focus.defer();

        appendIfFocused(search, focus, 'u');

        assertEquals("", search.toString());
        focus.advanceTick();
        assertTrue(focus.shouldFocus());
        appendIfFocused(search, focus, 'h');
        assertEquals("h", search.toString());
    }

    @Test
    void nonHotkeyNavigationKeepsImmediateSearchFocus() {
        final HotkeyFocusDelay focus = new HotkeyFocusDelay();

        assertTrue(focus.shouldFocus());
        focus.advanceTick();
        assertTrue(focus.shouldFocus());
    }

    private static void appendIfFocused(
        final StringBuilder search,
        final HotkeyFocusDelay focus,
        final char character
    ) {
        if (focus.shouldFocus()) {
            search.append(character);
        }
    }
}
