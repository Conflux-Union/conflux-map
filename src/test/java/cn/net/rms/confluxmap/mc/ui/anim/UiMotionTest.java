package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

final class UiMotionTest {
    @Test
    void easeOutCubicMatchesOneMinusCubeAndClamps() {
        assertEquals(0.0f, UiMotion.easeOutCubic(0.0f));
        assertEquals(0.5f, UiMotion.easeOutCubic(0.20629947f), 1e-5f);
        assertEquals(1.0f, UiMotion.easeOutCubic(1.0f));
        assertEquals(0.0f, UiMotion.easeOutCubic(-0.5f));
        assertEquals(1.0f, UiMotion.easeOutCubic(1.5f));
    }

    @Test
    void advanceMovesTowardOneAndClamps() {
        assertEquals(0.5f, UiMotion.advance(0.0f, 0.09f, UiMotion.STANDARD), 1e-5f);
        assertEquals(1.0f, UiMotion.advance(0.9f, 0.09f, UiMotion.STANDARD));
        assertEquals(0.25f, UiMotion.advance(0.25f, -1.0f, UiMotion.STANDARD));
        assertEquals(1.0f, UiMotion.advance(0.0f, 1.0f, 0.0f));
        assertEquals(1.0f, UiMotion.advance(0.0f, 1.0f, -0.5f));
    }

    @Test
    void tierDurationsMatchConstantsAndUnknownFallsBackToStandard() {
        assertEquals(UiMotion.FAST, UiMotion.durationSeconds("FAST"));
        assertEquals(UiMotion.STANDARD, UiMotion.durationSeconds("STANDARD"));
        assertEquals(UiMotion.RELAXED, UiMotion.durationSeconds("RELAXED"));
        assertEquals(UiMotion.STANDARD, UiMotion.durationSeconds("fast"));
        assertEquals(UiMotion.STANDARD, UiMotion.durationSeconds("nonsense"));
        assertEquals(UiMotion.STANDARD, UiMotion.durationSeconds(null));
    }
}
