package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MinimapStatusEffectAvoidanceTest {
    @Test
    void movesAnOverlappingMapByTheSmallestPossibleDistance() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(578, 628, 456, 1.0, 0.0);

        assertEquals(
            new MinimapPlacement.Layout(95, 4, 456),
            MinimapStatusEffectAvoidance.resolve(578, 628, configured, 1, 1)
        );
    }

    @Test
    void preservesTheUserConfiguredPositionWhenEffectsDoNotOverlap() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(578, 628, 90, 1.0, 1.0);

        assertEquals(
            configured,
            MinimapStatusEffectAvoidance.resolve(578, 628, configured, 2, 1)
        );
    }

    @Test
    void movesTheMapBelowEffectsWhenHorizontalAvoidanceDoesNotFit() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(200, 300, 190, 1.0, 0.0);

        assertEquals(
            new MinimapPlacement.Layout(6, 53, 190),
            MinimapStatusEffectAvoidance.resolve(200, 300, configured, 1, 1)
        );
    }

    @Test
    void keepsTheConfiguredHorizontalAlignmentWhenTwoAvoidanceDirectionsAreEquallyNear() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(578, 628, 90, 1.0, 0.0);

        assertEquals(
            new MinimapPlacement.Layout(484, 27, 90),
            MinimapStatusEffectAvoidance.resolve(578, 628, configured, 1, 0)
        );
    }

    @Test
    void recalculatesAvoidanceFromTheCurrentScaledViewport() {
        final MinimapPlacement.Layout largeViewport = MinimapPlacement.resolve(640, 360, 128, 1.0, 0.0);
        final MinimapPlacement.Layout smallViewport = MinimapPlacement.resolve(320, 180, 128, 1.0, 0.0);

        assertEquals(
            new MinimapPlacement.Layout(485, 4, 128),
            MinimapStatusEffectAvoidance.resolve(640, 360, largeViewport, 1, 1)
        );
        assertEquals(
            new MinimapPlacement.Layout(165, 4, 128),
            MinimapStatusEffectAvoidance.resolve(320, 180, smallViewport, 1, 1)
        );
    }

    @Test
    void retainsTheConfiguredLayoutWhenNoVisibleAlternativeExists() {
        final MinimapPlacement.Layout configured = MinimapPlacement.resolve(200, 240, 190, 1.0, 0.0);

        assertEquals(
            configured,
            MinimapStatusEffectAvoidance.resolve(200, 240, configured, 1, 1)
        );
    }
}
