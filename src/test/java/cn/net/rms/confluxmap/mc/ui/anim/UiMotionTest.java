package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import org.junit.jupiter.api.Test;

final class UiMotionTest {
    @Test
    void advanceMovesTowardOneAndClamps() {
        // Deltas are tier fractions so tuning the constants keeps these assertions exact.
        assertEquals(0.5f, UiMotion.advance(0.0f, UiMotion.STANDARD / 2, UiMotion.STANDARD), 1e-5f);
        assertEquals(1.0f, UiMotion.advance(0.9f, UiMotion.STANDARD / 2, UiMotion.STANDARD));
        assertEquals(0.25f, UiMotion.advance(0.25f, -1.0f, UiMotion.STANDARD));
        assertEquals(1.0f, UiMotion.advance(0.0f, 1.0f, 0.0f));
        assertEquals(1.0f, UiMotion.advance(0.0f, 1.0f, -0.5f));
    }

    @Test
    void tierDurationsMatchConstantsAndNullFallsBackToStandard() {
        assertEquals(UiMotion.FAST, UiMotion.durationSeconds(ConfluxConfig.UiTransitionSpeed.FAST));
        assertEquals(
            UiMotion.STANDARD,
            UiMotion.durationSeconds(ConfluxConfig.UiTransitionSpeed.STANDARD)
        );
        assertEquals(
            UiMotion.RELAXED,
            UiMotion.durationSeconds(ConfluxConfig.UiTransitionSpeed.RELAXED)
        );
        assertEquals(UiMotion.STANDARD, UiMotion.durationSeconds(null));
    }
}
