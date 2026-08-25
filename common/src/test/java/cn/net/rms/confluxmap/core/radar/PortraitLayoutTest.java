package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortraitLayoutTest {
    private static final float EPSILON = 0.001f;

    @Test
    void fillsTheLongestAxisWithoutChangingAspectRatio() {
        final float cell = 32f;
        final float padding = 1f;

        for (final float[] raw : new float[][] {
            {10f, 10f}, {9f, 12f}, {15f, 10f}, {40f, 41f}
        }) {
            final PortraitLayout.Fit fit = PortraitLayout.fit(raw[0], raw[1], cell, padding);

            assertEquals(raw[0] / raw[1], fit.width() / fit.height(), EPSILON);
            assertEquals(30f, Math.max(fit.width(), fit.height()), EPSILON);
        }
    }

    @Test
    void keepsAnElongatedSubjectWide() {
        final PortraitLayout.Fit fit = PortraitLayout.fit(24f, 6f, 32f, 1f);

        assertEquals(30f, fit.width(), EPSILON);
        assertEquals(7.5f, fit.height(), EPSILON);
        assertEquals(4f, fit.width() / fit.height(), EPSILON);
    }

    @Test
    void centersTheSubjectInsideItsCell() {
        final PortraitLayout.Fit fit = PortraitLayout.fit(10f, 10f, 32f, 1f);

        assertEquals(fit.left(), fit.top(), EPSILON);
        assertEquals(1f, fit.left(), EPSILON);
    }

    @Test
    void rejectsCellsThatPaddingLeavesNoRoomIn() {
        assertThrows(IllegalArgumentException.class, () -> PortraitLayout.fit(8f, 8f, 32f, 16f));
        assertThrows(IllegalArgumentException.class, () -> PortraitLayout.fit(0f, 8f, 32f, 1f));
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
