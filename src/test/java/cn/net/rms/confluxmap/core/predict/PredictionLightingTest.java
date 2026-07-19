package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;
import org.junit.jupiter.api.Test;

class PredictionLightingTest {
    @Test
    void renderTintTracksDaylightWithoutDependingOnTileCompositionTime() {
        final int day = PredictionLighting.renderTint(true, 1f);
        final int night = PredictionLighting.renderTint(true, 0f);

        assertEquals(0xFFFFFFFF, day);
        assertEquals(ShadingPipeline.applyDaylight(0xFFFFFFFF, 0f, 0), night);
        assertNotEquals(day, night);
    }

    @Test
    void disabledDynamicLightingAlwaysUsesFullBrightness() {
        assertEquals(0xFFFFFFFF, PredictionLighting.renderTint(false, 0f));
    }
}
