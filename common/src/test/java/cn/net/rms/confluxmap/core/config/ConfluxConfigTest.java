package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ConfluxConfigTest {
    @Test
    void playerFacingFeaturesAreEnabledByDefault() {
        final ConfluxConfig config = new ConfluxConfig();

        assertTrue(config.radarShowPassive);
        assertTrue(config.radarShowOther);
        assertTrue(config.waypointCrossDimensionEnabled);
    }

    @Test
    void playerIconOutlineIsEnabledByDefaultAndSurvivesConfigCopy() {
        final ConfluxConfig config = new ConfluxConfig();

        assertTrue(config.radarPlayerIconOutlineEnabled);
        assertEquals(ConfluxConfig.DEFAULT_RADAR_ICON_OUTLINE_THICKNESS,
            config.radarIconOutlineThickness);
        config.radarPlayerIconOutlineEnabled = false;
        config.radarIconOutlineThickness = 4;
        assertFalse(config.copy().radarPlayerIconOutlineEnabled);
        assertEquals(4, config.copy().radarIconOutlineThickness);
    }

    @Test
    void waypointRenderDistanceIsFiniteByDefault() {
        assertEquals(1_000, new ConfluxConfig().waypointRenderDistance);
    }

    @Test
    void unsupportedPlatformWarningDismissalSurvivesConfigCopy() {
        final ConfluxConfig config = new ConfluxConfig();
        assertFalse(config.unsupportedPlatformWarningDismissed);

        config.unsupportedPlatformWarningDismissed = true;

        assertTrue(config.copy().unsupportedPlatformWarningDismissed);
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
