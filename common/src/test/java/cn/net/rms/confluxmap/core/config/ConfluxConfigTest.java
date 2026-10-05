package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import org.junit.jupiter.api.Test;

final class ConfluxConfigTest {
    @Test
    void playerFacingFeaturesAreEnabledByDefault() {
        final ConfluxConfig config = new ConfluxConfig();

        assertTrue(config.radarShowPassive);
        assertTrue(config.radarShowOther);
    }

    @Test
    void sharedWaypointCrossDimensionDisplayDefaultsOffAndSurvivesCopy() {
        final UUID waypointId = UUID.randomUUID();
        final ConfluxConfig config = new ConfluxConfig();

        assertFalse(config.isSharedWaypointCrossDimensionVisible(waypointId));
        config.setSharedWaypointCrossDimensionVisible(waypointId, true);

        final ConfluxConfig copy = config.copy();
        assertTrue(copy.isSharedWaypointCrossDimensionVisible(waypointId));
        copy.setSharedWaypointCrossDimensionVisible(waypointId, false);
        assertTrue(config.isSharedWaypointCrossDimensionVisible(waypointId));
    }

    @Test
    void sharedWaypointCrossDimensionYSurvivesCopyAndClearsWithNull() {
        final UUID waypointId = UUID.randomUUID();
        final ConfluxConfig config = new ConfluxConfig();

        assertNull(config.sharedWaypointCrossDimensionY(waypointId));
        config.setSharedWaypointCrossDimensionY(waypointId, 120.0);

        final ConfluxConfig copy = config.copy();
        assertEquals(120.0, copy.sharedWaypointCrossDimensionY(waypointId));
        copy.setSharedWaypointCrossDimensionY(waypointId, null);
        assertEquals(120.0, config.sharedWaypointCrossDimensionY(waypointId));

        config.setSharedWaypointCrossDimensionY(waypointId, null);
        assertNull(config.sharedWaypointCrossDimensionY(waypointId));
    }

    @Test
    void normalizeDropsNonFiniteSharedWaypointCrossDimensionY() {
        final ConfluxConfig config = new ConfluxConfig();
        config.sharedWaypointCrossDimensionYById.put("broken", Double.NaN);
        config.sharedWaypointCrossDimensionYById.put("kept", 64.0);

        config.normalize();

        assertNull(config.sharedWaypointCrossDimensionYById.get("broken"));
        assertEquals(64.0, config.sharedWaypointCrossDimensionYById.get("kept"));
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
    void playerHighlightGhostDefaultsToThirtySecondsAndSurvivesCopy() {
        final ConfluxConfig config = new ConfluxConfig();

        assertEquals(30, config.radarPlayerHighlightGhostSeconds);
        config.radarPlayerHighlightGhostSeconds = 45;

        assertEquals(45, config.copy().radarPlayerHighlightGhostSeconds);
    }

    @Test
    void locationHighlightCrossDimensionDisplayDefaultsOnAndSurvivesCopy() {
        final ConfluxConfig config = new ConfluxConfig();

        assertTrue(config.highlightCrossDimensionVisible);
        config.highlightCrossDimensionVisible = false;

        assertFalse(config.copy().highlightCrossDimensionVisible);
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
