package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import org.junit.jupiter.api.Test;

final class RadarMarkerPresentationTest {
    @Test
    void minimapUsesConfiguredDefaultAndPlayerListKeyCanExpandDots() {
        final RadarMarkerRenderer.Presentation portraits =
            RadarMarkerRenderer.Presentation.minimap(
                ConfluxConfig.RadarDisplayMode.PORTRAITS, false, true
            );
        final RadarMarkerRenderer.Presentation dots =
            RadarMarkerRenderer.Presentation.minimap(
                ConfluxConfig.RadarDisplayMode.DOTS, false, true
            );
        final RadarMarkerRenderer.Presentation expandedDots =
            RadarMarkerRenderer.Presentation.minimap(
                ConfluxConfig.RadarDisplayMode.DOTS, true, true
            );

        assertTrue(portraits.detailedIcons());
        assertFalse(portraits.showPlayerNames());
        assertFalse(dots.detailedIcons());
        assertFalse(dots.showPlayerNames());
        assertTrue(expandedDots.detailedIcons());
        assertTrue(expandedDots.showPlayerNames());
    }

    @Test
    void compactPresentationKeepsPlayerPortraitsButCollapsesOtherEntities() {
        final RadarMarkerRenderer.Presentation compact =
            RadarMarkerRenderer.Presentation.compact();

        assertTrue(RadarMarkerRenderer.usesDetailedIcon(RadarCategory.PLAYER, compact));
        assertFalse(RadarMarkerRenderer.usesDetailedIcon(RadarCategory.HOSTILE, compact));
        assertFalse(RadarMarkerRenderer.usesDetailedIcon(RadarCategory.PASSIVE, compact));
        assertFalse(RadarMarkerRenderer.usesDetailedIcon(RadarCategory.OTHER, compact));
    }

    @Test
    void detailedPresentationExpandsEveryCategory() {
        final RadarMarkerRenderer.Presentation detailed =
            RadarMarkerRenderer.Presentation.detailed(true);

        for (final RadarCategory category : RadarCategory.values()) {
            assertTrue(RadarMarkerRenderer.usesDetailedIcon(category, detailed), category.name());
        }
        assertTrue(detailed.showPlayerNames());
    }
}
