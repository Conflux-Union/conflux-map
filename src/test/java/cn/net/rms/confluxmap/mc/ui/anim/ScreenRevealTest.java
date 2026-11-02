package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ScreenRevealTest {
    @Test
    void freshRevealIsSteadilyOpen() {
        final ScreenReveal reveal = new ScreenReveal();

        assertEquals(ScreenReveal.Phase.OPEN, reveal.phase());
        assertFalse(reveal.animating());
        assertEquals(1.0f, reveal.eased());
    }

    @Test
    void openLifecycleAdvancesToSteadilyOpen() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginOpen();

        assertEquals(ScreenReveal.Phase.OPENING, reveal.phase());
        assertTrue(reveal.animating());
        assertEquals(0.0f, reveal.progress());

        reveal.tick(UiMotion.FAST, UiMotion.FAST);
        assertEquals(ScreenReveal.Phase.OPEN, reveal.phase());
        assertFalse(reveal.animating());
        assertEquals(1.0f, reveal.eased(), 1e-6f);
    }

    @Test
    void partialOpenEasesProgress() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginOpen();
        reveal.tick(0.09f, UiMotion.STANDARD);

        assertEquals(0.5f, reveal.progress(), 1e-5f);
        assertEquals(0.875f, reveal.eased(), 1e-5f);
        assertTrue(reveal.animating());
    }

    @Test
    void finishNowJumpsToTheOpenTerminalState() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginOpen();
        reveal.tick(0.05f, UiMotion.STANDARD);
        reveal.finishNow();

        assertEquals(ScreenReveal.Phase.OPEN, reveal.phase());
        assertFalse(reveal.animating());
        assertEquals(1.0f, reveal.eased(), 1e-6f);
    }

    @Test
    void closeLifecycleFallsBackFromCurrentProgressToClosed() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginOpen();
        reveal.tick(0.09f, UiMotion.STANDARD);
        reveal.beginClose();

        assertEquals(ScreenReveal.Phase.CLOSING, reveal.phase());
        assertTrue(reveal.animating());

        reveal.tick(0.036f, UiMotion.STANDARD);
        assertEquals(0.3f, reveal.progress(), 1e-5f);
        assertTrue(reveal.animating());

        reveal.tick(0.36f, UiMotion.STANDARD);
        assertEquals(ScreenReveal.Phase.CLOSED, reveal.phase());
        assertFalse(reveal.animating());
        assertEquals(0.0f, reveal.eased(), 1e-6f);
    }

    @Test
    void beginCloseWhileOpeningReversesFromCurrentProgress() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginOpen();
        reveal.tick(0.045f, UiMotion.STANDARD);
        reveal.beginClose();

        assertEquals(0.25f, reveal.progress(), 1e-5f);
    }

    @Test
    void closeFinishesImmediatelyAndStaysClosedWhenTicked() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginClose();
        reveal.finishNow();

        assertEquals(ScreenReveal.Phase.CLOSED, reveal.phase());
        reveal.tick(UiMotion.STANDARD, UiMotion.STANDARD);
        assertEquals(ScreenReveal.Phase.CLOSED, reveal.phase());
        assertEquals(0.0f, reveal.progress(), 1e-6f);
    }

    @Test
    void disablingTransitionsMeansFinishNowLeavesNothingAnimating() {
        final ScreenReveal reveal = new ScreenReveal();
        reveal.beginOpen();
        // The disabled-config path calls finishNow on the first frame instead of ticking.
        reveal.finishNow();

        assertFalse(reveal.animating());
        assertEquals(1.0f, reveal.eased(), 1e-6f);
    }
}
