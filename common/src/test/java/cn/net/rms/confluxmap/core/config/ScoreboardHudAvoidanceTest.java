package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScoreboardHudAvoidanceTest {
    @Test
    void shiftsAnOverlappingScoreboardToTheLeftOfTheMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(
            -108,
            ScoreboardHudAvoidance.horizontalShift(
                300,
                minimap,
                0,
                new ScoreboardHudAvoidance.Bounds(220, 20, 300, 100)
            )
        );
    }

    @Test
    void leavesTheScoreboardInPlaceWhenItIsVerticallySeparate() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(
            0,
            ScoreboardHudAvoidance.horizontalShift(
                300,
                minimap,
                0,
                new ScoreboardHudAvoidance.Bounds(220, 150, 300, 220)
            )
        );
    }

    @Test
    void includesVisibleMinimapInformationInTheCollisionFootprint() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(
            -108,
            ScoreboardHudAvoidance.horizontalShift(
                300,
                minimap,
                33,
                new ScoreboardHudAvoidance.Bounds(220, 110, 300, 160)
            )
        );
    }

    @Test
    void leavesTheScoreboardVisibleWhenNoInBoundsShiftExists() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(4, 4, 292);

        assertEquals(
            0,
            ScoreboardHudAvoidance.horizontalShift(
                300,
                minimap,
                0,
                new ScoreboardHudAvoidance.Bounds(196, 20, 300, 100)
            )
        );
    }
}
