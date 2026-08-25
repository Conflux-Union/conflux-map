package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ConfluxConfigTest {
    @Test
    void waypointRenderDistanceIsFiniteByDefault() {
        assertEquals(1_000, new ConfluxConfig().waypointRenderDistance);
    }

    @Test
    void radarMarkerMergingIsEnabledByDefault() {
        assertTrue(new ConfluxConfig().radarMergeEnabled);
    }

    @Test
    void radarMarkerMergeChoiceSurvivesConfigCopy() {
        final ConfluxConfig config = new ConfluxConfig();
        config.radarMergeEnabled = false;

        assertFalse(config.copy().radarMergeEnabled);
    }

    @Test
    void minimapZoomCyclesThroughEveryLevelAndWraps() {
        final ConfluxConfig config = new ConfluxConfig();
        config.minimapZoomIndex = 0;

        config.cycleMinimapZoom();
        assertEquals(1, config.minimapZoomIndex);
        config.cycleMinimapZoom();
        assertEquals(2, config.minimapZoomIndex);
        config.cycleMinimapZoom();
        assertEquals(3, config.minimapZoomIndex);
        config.cycleMinimapZoom();
        assertEquals(0, config.minimapZoomIndex);
    }
}
