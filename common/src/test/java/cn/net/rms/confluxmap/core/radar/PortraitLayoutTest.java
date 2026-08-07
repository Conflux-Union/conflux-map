package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortraitLayoutTest {
    private static final float EPSILON = 0.001f;

    @Test
    void normalizesVisualAreaInsteadOfStretchingEveryLongestEdgeToTheCellLimit() {
        final PortraitLayout.Fit square = PortraitLayout.fit(10f, 10f, 32f, 3f, 22f);
        final PortraitLayout.Fit portrait = PortraitLayout.fit(9f, 12f, 32f, 3f, 22f);

        assertEquals(22f * 22f, square.width() * square.height(), EPSILON);
        assertEquals(square.width() * square.height(), portrait.width() * portrait.height(), EPSILON);
        assertTrue(portrait.width() <= 26f);
        assertTrue(portrait.height() <= 26f);
    }

    @Test
    void givesLongHeadedMobsAStableThreeQuarterView() {
        assertEquals(35f, PortraitLayout.viewYawDegrees("minecraft:horse"), EPSILON);
        assertEquals(35f, PortraitLayout.viewYawDegrees("minecraft:donkey"), EPSILON);
        assertEquals(35f, PortraitLayout.viewYawDegrees("minecraft:camel"), EPSILON);
        assertEquals(90f, PortraitLayout.viewYawDegrees("minecraft:salmon"), EPSILON);
        assertEquals(0f, PortraitLayout.viewYawDegrees("minecraft:creeper"), EPSILON);
    }
}
