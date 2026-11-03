package cn.net.rms.confluxmap.mc.ui.anim;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.mc.ui.anim.ScreenCloseFlow.Decision;
import org.junit.jupiter.api.Test;

final class ScreenCloseFlowTest {
    @Test
    void disabledTransitionsAlwaysSwitchImmediately() {
        assertEquals(Decision.SWITCH_NOW, ScreenCloseFlow.decideClose(false, false, false));
        assertEquals(Decision.SWITCH_NOW, ScreenCloseFlow.decideClose(false, true, false));
        // A fade armed before the config flipped off still completes without animation.
        assertEquals(Decision.SWITCH_NOW, ScreenCloseFlow.decideClose(false, false, true));
    }

    @Test
    void parentTargetSwitchesInstantlyEvenWhileAFadeIsPending() {
        assertEquals(
            Decision.SWITCH_NOW_REOPEN_TARGET,
            ScreenCloseFlow.decideClose(true, true, false)
        );
        assertEquals(
            Decision.SWITCH_NOW_REOPEN_TARGET,
            ScreenCloseFlow.decideClose(true, true, true)
        );
    }

    @Test
    void worldTargetFadesAndDefersTheSwitch() {
        assertEquals(Decision.DEFER_FADE, ScreenCloseFlow.decideClose(true, false, false));
    }

    @Test
    void repeatedCloseRequestWhileFadingFinishesImmediately() {
        assertEquals(Decision.FINISH_PENDING_FADE, ScreenCloseFlow.decideClose(true, false, true));
    }
}
