package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HudAvoidanceLayoutTest {
    @Test
    void coordinatesScoreboardAndStatusEffectsFromOneFinalMinimapPosition() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new HudAvoidanceLayout.Decision(new MinimapPlacement.Layout(370, 4, 128), 0),
            HudAvoidanceLayout.resolve(
                true,
                640,
                360,
                configured,
                0,
                1,
                1,
                1,
                new MinimapHudAvoidance.Bounds(500, 60, 639, 160)
            )
        );
    }

    @Test
    void disablesBothAutomaticMovementsTogether() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new HudAvoidanceLayout.Decision(configured, 0),
            HudAvoidanceLayout.resolve(
                false,
                640,
                360,
                configured,
                0,
                1,
                1,
                1,
                new MinimapHudAvoidance.Bounds(500, 60, 639, 160)
            )
        );
    }
}
