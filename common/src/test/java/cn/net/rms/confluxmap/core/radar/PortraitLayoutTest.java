package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortraitLayoutTest {
    private static final float EPSILON = 0.001f;

    @Test
    void fillsTheAtlasCellWithoutInternalPadding() {
        final PortraitLayout.Fit square = PortraitLayout.fit(10f, 10f, 32f, 0f, 32f);
        final PortraitLayout.Fit portrait = PortraitLayout.fit(9f, 12f, 32f, 0f, 32f);

        assertEquals(32f, square.width(), EPSILON);
        assertEquals(32f, square.height(), EPSILON);
        assertEquals(0f, square.left(), EPSILON);
        assertEquals(0f, square.top(), EPSILON);
        assertTrue(portrait.width() <= 32f);
        assertEquals(32f, portrait.height(), EPSILON);
        assertEquals(0f, portrait.top(), EPSILON);
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
