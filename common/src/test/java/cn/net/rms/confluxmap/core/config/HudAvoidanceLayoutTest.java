package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudAvoidanceLayoutTest {
    @Test
    void keepsTheConfiguredMinimapFixedWhileVanillaHudAvoidsIt() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new HudAvoidanceLayout.Decision(
                configured,
                new ScoreboardHudAvoidance.Transform(0f, 76f, 1f),
                -136
            ),
            HudAvoidanceLayout.resolve(
                true,
                640,
                360,
                configured,
                0,
                1,
                1,
                1,
                new ScoreboardHudAvoidance.Bounds(500, 60, 639, 160)
            )
        );
    }

    @Test
    void disablesBothAutomaticMovementsTogether() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new HudAvoidanceLayout.Decision(
                configured,
                ScoreboardHudAvoidance.Transform.IDENTITY,
                0
            ),
            HudAvoidanceLayout.resolve(
                false,
                640,
                360,
                configured,
                0,
                1,
                1,
                1,
                new ScoreboardHudAvoidance.Bounds(500, 60, 639, 160)
            )
        );
    }
}
