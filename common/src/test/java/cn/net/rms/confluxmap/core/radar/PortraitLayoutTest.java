package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PortraitLayoutTest {
    private static final float EPSILON = 0.001f;

    @Test
    void givesEveryAspectRatioTheSameLongestAxisSpan() {
        final float cell = 32f;
        final float padding = 1f;
        final float span = cell - 2f * padding;

        for (final float[] raw : new float[][] {
            {10f, 10f}, {9f, 12f}, {24f, 6f}, {3f, 19f}, {1f, 1f}, {40f, 41f}
        }) {
            final PortraitLayout.Fit fit = PortraitLayout.fit(raw[0], raw[1], cell, padding);

            assertEquals(
                span, Math.max(fit.width(), fit.height()), EPSILON,
                () -> "portrait " + raw[0] + "x" + raw[1] + " must span the same pixels as every other"
            );
            assertEquals(raw[0] / raw[1], fit.width() / fit.height(), EPSILON, "aspect ratio must survive");
        }
    }

    @Test
    void centersThePortraitInsideItsCell() {
        final PortraitLayout.Fit fit = PortraitLayout.fit(24f, 6f, 32f, 1f);

        assertEquals(30f, fit.width(), EPSILON);
        assertEquals(1f, fit.left(), EPSILON);
        assertEquals((32f - fit.height()) / 2f, fit.top(), EPSILON);
        assertTrue(fit.height() < fit.width());
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
