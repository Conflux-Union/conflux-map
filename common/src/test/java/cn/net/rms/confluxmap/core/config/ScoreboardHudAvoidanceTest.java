package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScoreboardHudAvoidanceTest {
    @Test
    void movesAnOverlappingScoreboardBelowTheMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(
            new ScoreboardHudAvoidance.Transform(0f, 88f, 1f),
            ScoreboardHudAvoidance.resolve(
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
            ScoreboardHudAvoidance.Transform.IDENTITY,
            ScoreboardHudAvoidance.resolve(
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
            new ScoreboardHudAvoidance.Transform(0f, 31f, 1f),
            ScoreboardHudAvoidance.resolve(
                300,
                minimap,
                33,
                new ScoreboardHudAvoidance.Bounds(220, 110, 300, 160)
            )
        );
    }

    @Test
    void scalesTheScoreboardWhenThereIsNotEnoughSpaceBelowTheMinimap() {
        final MinimapPlacement.Layout minimap = new MinimapPlacement.Layout(196, 4, 100);

        assertEquals(
            new ScoreboardHudAvoidance.Transform(150f, 98f, 0.5f),
            ScoreboardHudAvoidance.resolve(
                162,
                minimap,
                0,
                new ScoreboardHudAvoidance.Bounds(220, 20, 300, 120)
            )
        );
    }
}
