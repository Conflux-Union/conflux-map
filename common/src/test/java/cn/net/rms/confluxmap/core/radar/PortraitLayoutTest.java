package cn.net.rms.confluxmap.core.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
    void givesHorseFamilyTheVoxelMapProfileView() {
        for (final String entityType : new String[] {
            "minecraft:horse",
            "minecraft:donkey",
            "minecraft:mule",
            "minecraft:skeleton_horse",
            "minecraft:zombie_horse"
        }) {
            final PortraitLayout.Profile profile = PortraitLayout.profile(entityType);
            assertEquals(-90f, profile.yawDegrees(), EPSILON, entityType);
            assertEquals(35f, profile.pitchDegrees(), EPSILON, entityType);
            assertTrue(profile.resetPartRotation(), entityType);
            assertEquals(PortraitLayout.Framing.COMPLETE, profile.framing(), entityType);
            assertFalse(
                HeadPartSelector.usesFullModel(entityType),
                entityType + " must keep using head-only portrait geometry"
            );
        }
        assertEquals(90f, PortraitLayout.viewYawDegrees("minecraft:salmon"), EPSILON);
        final PortraitLayout.Profile creeperProfile = PortraitLayout.profile("minecraft:creeper");
        assertEquals(0f, creeperProfile.yawDegrees(), EPSILON);
        assertEquals(0f, creeperProfile.pitchDegrees(), EPSILON);
        assertFalse(creeperProfile.resetPartRotation());
        assertEquals(PortraitLayout.Framing.DOMINANT, creeperProfile.framing());
    }

    @Test
    void givesLlamasAnUpperProfileThatRemovesTheLongNeck() {
        for (final String entityType : new String[] {
            "minecraft:llama",
            "minecraft:trader_llama"
        }) {
            final PortraitLayout.Profile profile = PortraitLayout.profile(entityType);
            assertEquals(-90f, profile.yawDegrees(), EPSILON, entityType);
            assertEquals(35f, profile.pitchDegrees(), EPSILON, entityType);
            assertTrue(profile.resetPartRotation(), entityType);
            assertEquals(
                PortraitLayout.Framing.UPPER_SILHOUETTE,
                profile.framing(),
                entityType
            );
            assertFalse(
                HeadPartSelector.usesFullModel(entityType),
                entityType + " must keep using head-only portrait geometry"
            );
        }
        for (final String entityType : new String[] {
            "minecraft:camel",
            "minecraft:camel_husk"
        }) {
            final PortraitLayout.Profile camel = PortraitLayout.profile(entityType);
            assertEquals(-90f, camel.yawDegrees(), EPSILON, entityType);
            assertEquals(0f, camel.pitchDegrees(), EPSILON, entityType);
            assertTrue(camel.resetPartRotation(), entityType);
            assertEquals(
                PortraitLayout.Framing.COMPLETE,
                camel.framing(),
                entityType
            );
            assertFalse(HeadPartSelector.usesFullModel(entityType), entityType);
        }
        assertEquals(
            PortraitLayout.Framing.COMPLETE,
            PortraitLayout.profile("minecraft:rabbit").framing()
        );
    }

    @Test
    void matchesVoxelMapConfiguredViewsForOtherHeadOnlyMobs() {
        assertProfile("minecraft:parrot", -90f, 0f, PortraitLayout.Framing.COMPLETE);
        assertProfile("minecraft:turtle", 90f, 0f, PortraitLayout.Framing.COMPLETE);
        assertProfile("minecraft:goat", 0f, 30f, PortraitLayout.Framing.DOMINANT);
        assertProfile("minecraft:hoglin", 0f, 60f, PortraitLayout.Framing.DOMINANT);
        assertProfile("minecraft:zoglin", 0f, 60f, PortraitLayout.Framing.DOMINANT);
        assertProfile("minecraft:nautilus", -90f, 0f, PortraitLayout.Framing.DOMINANT);
        assertProfile(
            "minecraft:zombie_nautilus", -90f, 0f, PortraitLayout.Framing.DOMINANT
        );

        for (final String entityType : new String[] {
            "minecraft:cod",
            "minecraft:salmon",
            "minecraft:tropical_fish"
        }) {
            final PortraitLayout.Profile profile = PortraitLayout.profile(entityType);
            assertEquals(90f, profile.yawDegrees(), EPSILON, entityType);
            assertEquals(0f, profile.pitchDegrees(), EPSILON, entityType);
            assertTrue(profile.resetPartRotation(), entityType);
            assertEquals(PortraitLayout.Framing.DOMINANT, profile.framing(), entityType);
        }

        final PortraitLayout.Profile happyGhast = PortraitLayout.profile(
            "minecraft:happy_ghast"
        );
        assertEquals(PortraitLayout.Framing.UPPER_SILHOUETTE, happyGhast.framing());
        assertTrue(happyGhast.resetPartRotation());
    }

    private static void assertProfile(
        final String entityType,
        final float expectedYaw,
        final float expectedPitch,
        final PortraitLayout.Framing expectedFraming
    ) {
        final PortraitLayout.Profile profile = PortraitLayout.profile(entityType);
        assertEquals(expectedYaw, profile.yawDegrees(), EPSILON, entityType);
        assertEquals(expectedPitch, profile.pitchDegrees(), EPSILON, entityType);
        assertTrue(profile.resetPartRotation(), entityType);
        assertEquals(expectedFraming, profile.framing(), entityType);
        assertFalse(
            HeadPartSelector.usesFullModel(entityType),
            entityType + " must keep using head-only portrait geometry"
        );
    }
}
