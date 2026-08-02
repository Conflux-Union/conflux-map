package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MinimapHudAvoidanceTest {
    @Test
    void movesTheMapLeftOfTheMeasuredScoreboard() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);

        assertEquals(
            new MinimapPlacement.Layout(370, 4, 128),
            MinimapHudAvoidance.resolve(
                640,
                360,
                configured,
                new MinimapHudAvoidance.Bounds(500, 60, 639, 160)
            )
        );
    }

    @Test
    void considersStatusEffectsAndScoreboardTogether() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);
        final MinimapHudAvoidance.Bounds effects = MinimapStatusEffectAvoidance.visibleBounds(640, 1, 1);

        assertEquals(
            new MinimapPlacement.Layout(370, 4, 128),
            MinimapHudAvoidance.resolve(
                640,
                360,
                configured,
                effects,
                new MinimapHudAvoidance.Bounds(500, 60, 639, 160)
            )
        );
    }

    @Test
    void movesBelowTheScoreboardWhenNeitherHorizontalSideFits() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(200, 400, 190, 1.0, 0.0);

        assertEquals(
            new MinimapPlacement.Layout(6, 162, 190),
            MinimapHudAvoidance.resolve(
                200,
                400,
                configured,
                new MinimapHudAvoidance.Bounds(4, 40, 199, 160)
            )
        );
    }
}
