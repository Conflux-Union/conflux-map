package cn.net.rms.confluxmap.mc.ui.hud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudRect;
import cn.net.rms.confluxmap.core.config.HudTransform;
import org.junit.jupiter.api.Test;

class ScoreboardHudBoundsTest {
    @Test
    void publishesOnlyCompletedBoundsForTheMatchingViewport() {
        ScoreboardHudBounds.beginFrame(701, 401);
        ScoreboardHudBounds.beginFrame(640, 360);
        assertNull(ScoreboardHudBounds.previousFrame(640, 360));

        final HudTransform transform = new HudTransform(0f, 76f, 1f);
        ScoreboardHudBounds.recordAppliedTransform(transform);
        ScoreboardHudBounds.include(600, 116, 639, 176);
        ScoreboardHudBounds.include(500, 136, 620, 236);
        assertNull(ScoreboardHudBounds.previousFrame(640, 360));

        ScoreboardHudBounds.beginFrame(640, 360);
        final HudRect completed = new HudRect(500, 40, 639, 160);
        assertEquals(completed, ScoreboardHudBounds.previousFrame(640, 360));
        assertEquals(transform, ScoreboardHudBounds.previousAppliedTransform(640, 360));
        assertNull(ScoreboardHudBounds.previousFrame(800, 600));
        assertEquals(
            HudTransform.IDENTITY,
            ScoreboardHudBounds.previousAppliedTransform(800, 600)
        );

        ScoreboardHudBounds.include(610, 1, 639, 25);
        assertEquals(completed, ScoreboardHudBounds.previousFrame(640, 360));

        ScoreboardHudBounds.beginFrame(640, 360);
        assertEquals(
            new HudRect(610, 1, 639, 25),
            ScoreboardHudBounds.previousFrame(640, 360)
        );
    }

    @Test
    void reportsBoundsWithoutTheTransformItAlreadyApplied() {
        ScoreboardHudBounds.beginFrame(640, 360);
        ScoreboardHudBounds.recordAppliedTransform(new HudTransform(70f, 100f, 0.5f));
        ScoreboardHudBounds.include(320f, 120f, 390f, 180f);

        ScoreboardHudBounds.beginFrame(640, 360);
        assertEquals(
            new HudRect(500, 40, 640, 160),
            ScoreboardHudBounds.previousFrame(640, 360)
        );
    }

    @Test
    void measuresTheSidebarAtTheSizeAnExternalScaleGaveIt() {
        final HudAmbient halved = new HudAmbient(320f, 0f, 0.5f, 0.5f);
        ScoreboardHudBounds.beginFrame(640, 360);
        ScoreboardHudBounds.include(
            halved.applyX(500), halved.applyY(40), halved.applyX(640), halved.applyY(160)
        );

        ScoreboardHudBounds.beginFrame(640, 360);
        assertEquals(
            new HudRect(570, 20, 640, 80),
            ScoreboardHudBounds.previousFrame(640, 360)
        );
    }
}
