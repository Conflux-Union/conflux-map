package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class PopupRevealTest {
    @Test
    void freshPopupIsHiddenAndNotAnimating() {
        final PopupReveal popup = new PopupReveal();

        assertFalse(popup.animating());
        assertEquals(0.0f, popup.eased());
    }

    @Test
    void openingAdvancesTowardOneAndSettles() {
        final PopupReveal popup = new PopupReveal();
        popup.setOpen(true);
        assertTrue(popup.animating());

        popup.tick(0.09f, UiMotion.STANDARD);
        assertEquals(0.875f, popup.eased(), 1e-5f);
        assertTrue(popup.animating());

        popup.tick(UiMotion.STANDARD, UiMotion.STANDARD);
        assertFalse(popup.animating());
        assertEquals(1.0f, popup.eased(), 1e-6f);
    }

    @Test
    void reversingMidFlightTurnsThePopupAroundFromWhereItStands() {
        final PopupReveal popup = new PopupReveal();
        popup.setOpen(true);
        popup.tick(0.09f, UiMotion.STANDARD);
        popup.setOpen(false);

        assertEquals(0.875f, popup.eased(), 1e-5f);
        assertTrue(popup.animating());

        popup.tick(0.045f, UiMotion.STANDARD);
        assertEquals(0.578125f, popup.eased(), 1e-4f);

        popup.tick(UiMotion.STANDARD, UiMotion.STANDARD);
        assertFalse(popup.animating());
        assertEquals(0.0f, popup.eased(), 1e-6f);
    }

    @Test
    void disabledTransitionsSnapSettledAndNeverReportAFade() {
        final PopupReveal popup = new PopupReveal();
        popup.setOpen(true);
        popup.tick(false, UiMotion.STANDARD);

        assertEquals(1.0f, popup.eased(), 1e-6f);
        assertFalse(popup.animating());
        assertFalse(popup.fadeVisible(false));
    }

    @Test
    void fadeVisibleCombinesTransitionsEnabledWithAnimation() {
        final PopupReveal popup = new PopupReveal();
        popup.setOpen(true);
        popup.tick(0.01f, UiMotion.STANDARD);

        assertTrue(popup.animating());
        assertTrue(popup.fadeVisible(true));
        assertFalse(popup.fadeVisible(false));

        popup.tick(UiMotion.STANDARD, UiMotion.STANDARD);
        assertFalse(popup.fadeVisible(true));
    }
}
