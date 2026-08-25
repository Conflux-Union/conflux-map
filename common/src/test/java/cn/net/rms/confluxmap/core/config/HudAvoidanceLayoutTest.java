package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudAvoidanceLayoutTest {
    private static final HudRect BENEFICIAL_ROW = new HudRect(615, 1, 640, 25);
    private static final HudRect HARMFUL_ROW = new HudRect(615, 27, 640, 51);
    private static final HudRect SCOREBOARD = new HudRect(500, 60, 639, 160);

    @Test
    void keepsTheConfiguredMinimapFixedWhileVanillaHudAvoidsIt() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new HudAvoidanceLayout.Decision(configured, new HudTransform(0f, 76f, 1f), -136),
            HudAvoidanceLayout.resolve(
                true, 360, configured, 0, BENEFICIAL_ROW, HARMFUL_ROW, SCOREBOARD
            )
        );
    }

    @Test
    void disablesBothAutomaticMovementsTogether() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new HudAvoidanceLayout.Decision(configured, HudTransform.IDENTITY, 0),
            HudAvoidanceLayout.resolve(
                false, 360, configured, 0, BENEFICIAL_ROW, HARMFUL_ROW, SCOREBOARD
            )
        );
    }

    @Test
    void disablingAvoidanceAlsoLeavesToastsAlone() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);
        final HudRect toasts = new HudRect(480, 0, 640, 32);

        assertEquals(136, HudAvoidanceLayout.toastShift(true, 360, configured, 0, toasts));
        assertEquals(0, HudAvoidanceLayout.toastShift(false, 360, configured, 0, toasts));
    }
}
