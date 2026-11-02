package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class MinimapHudFadeTest {
    @Test
    void startsHiddenAndSettled() {
        final MinimapHudFade fade = new MinimapHudFade();
        assertEquals(0.0f, fade.alpha());
        assertTrue(fade.settled());
    }

    @Test
    void fadesInOverTheConfiguredDuration() {
        final MinimapHudFade fade = new MinimapHudFade();
        fade.tick(true, false, 0.09f, 0.18f);
        // Halfway progress eases to 1 - 0.5^3, same curve as the screens' reveal.
        assertEquals(0.875f, fade.alpha(), 1e-5);
        assertFalse(fade.settled());
        fade.tick(true, false, 0.09f, 0.18f);
        assertEquals(1.0f, fade.alpha());
        assertTrue(fade.settled());
    }

    @Test
    void fadesBackOutFromWhereItStopped() {
        final MinimapHudFade fade = new MinimapHudFade();
        fade.tick(true, false, 1.0f, 0.18f);
        fade.tick(false, false, 0.09f, 0.18f);
        assertEquals(0.875f, fade.alpha(), 1e-5);
        fade.tick(false, false, 1.0f, 0.18f);
        assertEquals(0.0f, fade.alpha());
        assertTrue(fade.settled());
    }

    @Test
    void anInstantTickJumpsStraightToTheTarget() {
        final MinimapHudFade fade = new MinimapHudFade();
        fade.tick(true, true, 0.0f, 0.18f);
        assertEquals(1.0f, fade.alpha());
        assertTrue(fade.settled());
        fade.tick(false, true, 0.0f, 0.18f);
        assertEquals(0.0f, fade.alpha());
    }

    @Test
    void aNonPositiveDurationJumpsToo() {
        final MinimapHudFade fade = new MinimapHudFade();
        fade.tick(true, false, 0.09f, 0.0f);
        assertEquals(1.0f, fade.alpha());
        fade.tick(false, false, 0.09f, -1.0f);
        assertEquals(0.0f, fade.alpha());
    }

    @Test
    void aSettledTickIsANoOpRegardlessOfDelta() {
        final MinimapHudFade fade = new MinimapHudFade();
        fade.tick(true, false, 1.0f, 0.18f);
        fade.tick(true, false, 30.0f, 0.18f);
        assertEquals(1.0f, fade.alpha());
        fade.tick(true, false, -1.0f, 0.18f);
        assertEquals(1.0f, fade.alpha());
    }

    @Test
    void reversingMidFadeDoesNotOvershoot() {
        final MinimapHudFade fade = new MinimapHudFade();
        fade.tick(true, false, 0.09f, 0.18f);
        fade.tick(false, false, 1.0f, 0.18f);
        assertEquals(0.0f, fade.alpha());
        fade.tick(true, false, 0.045f, 0.18f);
        // Quarter progress eases to 1 - 0.75^3.
        assertEquals(0.578125f, fade.alpha(), 1e-5);
    }
}
