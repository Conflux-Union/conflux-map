package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.net.rms.confluxmap.core.config.ScoreboardHudAvoidance;
import org.junit.jupiter.api.Test;

class ScoreboardHudBoundsTest {
    @Test
    void publishesOnlyCompletedBoundsForTheMatchingViewport() {
        ScoreboardHudBounds.beginFrame(701, 401);
        ScoreboardHudBounds.beginFrame(640, 360);
        assertNull(ScoreboardHudBounds.previousFrame(640, 360));

        ScoreboardHudBounds.include(600, 40, 639, 100);
        ScoreboardHudBounds.include(500, 60, 620, 160);
        final ScoreboardHudAvoidance.Transform transform =
            new ScoreboardHudAvoidance.Transform(0f, 76f, 1f);
        ScoreboardHudBounds.recordAppliedTransform(transform);
        assertNull(ScoreboardHudBounds.previousFrame(640, 360));

        ScoreboardHudBounds.beginFrame(640, 360);
        final ScoreboardHudAvoidance.Bounds completed =
            new ScoreboardHudAvoidance.Bounds(500, 40, 639, 160);
        assertEquals(completed, ScoreboardHudBounds.previousFrame(640, 360));
        assertEquals(transform, ScoreboardHudBounds.previousAppliedTransform(640, 360));
        assertNull(ScoreboardHudBounds.previousFrame(800, 600));
        assertEquals(
            ScoreboardHudAvoidance.Transform.IDENTITY,
            ScoreboardHudBounds.previousAppliedTransform(800, 600)
        );

        ScoreboardHudBounds.include(610, 1, 639, 25);
        assertEquals(completed, ScoreboardHudBounds.previousFrame(640, 360));

        ScoreboardHudBounds.beginFrame(640, 360);
        assertEquals(
            new ScoreboardHudAvoidance.Bounds(610, 1, 639, 25),
            ScoreboardHudBounds.previousFrame(640, 360)
        );
    }
}
