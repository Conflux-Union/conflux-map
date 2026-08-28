package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.radar.RadarCategory;
import org.junit.jupiter.api.Test;

final class RadarMarkerPresentationTest {
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
