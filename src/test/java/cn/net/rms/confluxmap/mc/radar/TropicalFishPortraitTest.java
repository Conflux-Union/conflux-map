package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.compat.Ids;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class TropicalFishPortraitTest {
    @Test
    void mapsEveryVanillaPatternToItsSecondaryTexture() {
        final Map<String, String> expected = Map.ofEntries(
            Map.entry("kob", "minecraft:textures/entity/fish/tropical_a_pattern_1.png"),
            Map.entry("sunstreak", "minecraft:textures/entity/fish/tropical_a_pattern_2.png"),
            Map.entry("snooper", "minecraft:textures/entity/fish/tropical_a_pattern_3.png"),
            Map.entry("dasher", "minecraft:textures/entity/fish/tropical_a_pattern_4.png"),
            Map.entry("brinely", "minecraft:textures/entity/fish/tropical_a_pattern_5.png"),
            Map.entry("spotty", "minecraft:textures/entity/fish/tropical_a_pattern_6.png"),
            Map.entry("flopper", "minecraft:textures/entity/fish/tropical_b_pattern_1.png"),
            Map.entry("stripey", "minecraft:textures/entity/fish/tropical_b_pattern_2.png"),
            Map.entry("glitter", "minecraft:textures/entity/fish/tropical_b_pattern_3.png"),
            Map.entry("blockfish", "minecraft:textures/entity/fish/tropical_b_pattern_4.png"),
            Map.entry("betty", "minecraft:textures/entity/fish/tropical_b_pattern_5.png"),
            Map.entry("clayfish", "minecraft:textures/entity/fish/tropical_b_pattern_6.png")
        );

        expected.forEach((pattern, texture) ->
            assertEquals(texture, TropicalFishPortrait.patternTexture(pattern).toString(), pattern)
        );
    }

    @Test
    void appearanceKeepsIndependentOpaqueBaseAndPatternTints() {
        final TropicalFishPortrait.Appearance appearance = TropicalFishPortrait.appearance(
            "kob", new float[] {0.2f, 0.4f, 0.6f}, new float[] {1f, 0.5f, 0f}
        );

        assertEquals(0xFF336699, appearance.baseTint());
        assertEquals(0xFFFF8000, appearance.patternTint());
        assertNotEquals(appearance.baseTint(), appearance.patternTint());
    }

    @Test
    void modernRgbColorsReceiveOpaqueAlpha() {
        final TropicalFishPortrait.Appearance appearance = TropicalFishPortrait.appearance(
            "flopper", 0x123456, 0xABCDEF
        );

        assertEquals(0xFF123456, appearance.baseTint());
        assertEquals(0xFFABCDEF, appearance.patternTint());
    }

    @Test
    void legacyAppearanceKeepsThePatternTextureSelectedByVanilla() {
        final TropicalFishPortrait.Appearance appearance = TropicalFishPortrait.appearance(
            Ids.of("minecraft", "textures/entity/fish/tropical_b_pattern_4.png"),
            new float[] {1f, 1f, 1f},
            new float[] {0f, 0f, 0f}
        );

        assertEquals(
            "minecraft:textures/entity/fish/tropical_b_pattern_4.png",
            appearance.patternTexture().toString()
        );
    }
}
