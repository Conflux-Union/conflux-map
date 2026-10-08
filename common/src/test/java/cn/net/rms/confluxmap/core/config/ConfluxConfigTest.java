package cn.net.rms.confluxmap.core.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.UUID;
import cn.net.rms.confluxmap.core.portal.PortalKind;
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
    void playerTrailDefaultsToFourSecondsWithinRange() {
        final ConfluxConfig config = new ConfluxConfig();

        assertEquals(4, config.playerTrailDurationSeconds);
        assertTrue(config.playerTrailDurationSeconds >= ConfluxConfig.MIN_PLAYER_TRAIL_DURATION_SECONDS);
        assertTrue(config.playerTrailDurationSeconds <= ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS);
    }

    @Test
    void normalizeMigratesOldDefaultTrailDurationToFourSeconds() {
        final ConfluxConfig config = new ConfluxConfig();
        config.schemaVersion = 9;
        config.playerTrailDurationSeconds = 120;

        config.normalize();

        assertEquals(4, config.playerTrailDurationSeconds);
    }

    @Test
    void normalizeKeepsExplicitTrailDurationsFromOldSchemas() {
        final ConfluxConfig explicit = new ConfluxConfig();
        explicit.schemaVersion = 9;
        explicit.playerTrailDurationSeconds = 30;

        explicit.normalize();

        assertEquals(30, explicit.playerTrailDurationSeconds);

        final ConfluxConfig overMax = new ConfluxConfig();
        overMax.schemaVersion = 3;
        overMax.playerTrailDurationSeconds = 1000;

        overMax.normalize();

        assertEquals(ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS, overMax.playerTrailDurationSeconds);

        final ConfluxConfig underMin = new ConfluxConfig();
        underMin.schemaVersion = 3;
        underMin.playerTrailDurationSeconds = 0;

        underMin.normalize();

        assertEquals(ConfluxConfig.MIN_PLAYER_TRAIL_DURATION_SECONDS, underMin.playerTrailDurationSeconds);
    }

    @Test
    void normalizeCollapsesLegacyMinuteDefaultToFourSeconds() {
        final ConfluxConfig config = new ConfluxConfig();
        config.schemaVersion = 2;
        config.playerTrailDurationMinutes = 2;

        config.normalize();

        assertEquals(4, config.playerTrailDurationSeconds);
        assertNull(config.playerTrailDurationMinutes);
    }

    @Test
    void normalizeStillClampsTrailDurationOnCurrentSchema() {
        final ConfluxConfig overMax = new ConfluxConfig();
        overMax.playerTrailDurationSeconds = 1000;

        overMax.normalize();

        assertEquals(ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS, overMax.playerTrailDurationSeconds);

        final ConfluxConfig underMin = new ConfluxConfig();
        underMin.playerTrailDurationSeconds = 0;

        underMin.normalize();

        assertEquals(ConfluxConfig.MIN_PLAYER_TRAIL_DURATION_SECONDS, underMin.playerTrailDurationSeconds);

        final ConfluxConfig explicitMax = new ConfluxConfig();
        explicitMax.playerTrailDurationSeconds = ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS;

        explicitMax.normalize();

        assertEquals(ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS, explicitMax.playerTrailDurationSeconds);
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

    @Test
    void portalIconTexturesDefaultEmptyAndMapPerKind() {
        final ConfluxConfig config = new ConfluxConfig();
        for (final PortalKind kind : PortalKind.values()) {
            assertEquals("", config.portalIconTexture(kind));
        }

        config.setPortalIconTexture(
            PortalKind.NETHER_PORTAL, " minecraft:textures/block/crying_obsidian.png ");
        config.setPortalIconTexture(PortalKind.END_PORTAL, "custompack:textures/portal/end.png");
        config.setPortalIconTexture(PortalKind.END_GATEWAY, null);

        assertEquals("minecraft:textures/block/crying_obsidian.png",
            config.portalIconTextureNether);
        assertEquals("custompack:textures/portal/end.png",
            config.portalIconTexture(PortalKind.END_PORTAL));
        assertEquals("", config.portalIconTextureGateway);
    }

    @Test
    void portalSettingsSurviveConfigCopy() {
        final ConfluxConfig config = new ConfluxConfig();
        config.portalMarkersEnabled = false;
        config.portalIconsEnabled = false;
        config.setPortalIconTexture(PortalKind.NETHER_PORTAL, "minecraft:textures/block/obsidian.png");
        config.setPortalIconTexture(PortalKind.END_PORTAL, "pack:textures/end.png");
        config.setPortalIconTexture(PortalKind.END_GATEWAY, "pack:textures/gateway.png");
        config.portalIconSize = 24;
        config.portalIconOpacity = 50;
        config.portalChunkHighlightEnabled = false;
        config.portalHighlightColor = ConfluxConfig.PortalHighlightColor.GOLD;
        config.portalIconHideZoom = 1.0;

        final ConfluxConfig copy = config.copy();

        assertFalse(copy.portalMarkersEnabled);
        assertFalse(copy.portalIconsEnabled);
        assertEquals("minecraft:textures/block/obsidian.png",
            copy.portalIconTexture(PortalKind.NETHER_PORTAL));
        assertEquals("pack:textures/end.png", copy.portalIconTexture(PortalKind.END_PORTAL));
        assertEquals("pack:textures/gateway.png", copy.portalIconTexture(PortalKind.END_GATEWAY));
        assertEquals(24, copy.portalIconSize);
        assertEquals(50, copy.portalIconOpacity);
        assertFalse(copy.portalChunkHighlightEnabled);
        assertEquals(ConfluxConfig.PortalHighlightColor.GOLD, copy.portalHighlightColor);
        assertEquals(1.0, copy.portalIconHideZoom);
    }

    @Test
    void normalizeClampsPortalValuesAndDefaultsEmptyTextures() {
        final ConfluxConfig config = new ConfluxConfig();
        config.portalIconSize = 9999;
        config.portalIconOpacity = -5;
        config.portalIconHideZoom = Double.NaN;
        config.portalHighlightColor = null;
        config.portalIconTextureNether = "  pack:textures/nether.png  ";
        config.portalIconTextureEnd = null;

        config.normalize();

        assertEquals(ConfluxConfig.MAX_PORTAL_ICON_SIZE, config.portalIconSize);
        assertEquals(ConfluxConfig.MIN_PORTAL_ICON_OPACITY, config.portalIconOpacity);
        assertEquals(ConfluxConfig.MIN_PORTAL_ICON_HIDE_ZOOM, config.portalIconHideZoom);
        assertEquals(ConfluxConfig.PortalHighlightColor.PURPLE, config.portalHighlightColor);
        assertEquals("pack:textures/nether.png", config.portalIconTextureNether);
        assertEquals("", config.portalIconTextureEnd);
    }
}
