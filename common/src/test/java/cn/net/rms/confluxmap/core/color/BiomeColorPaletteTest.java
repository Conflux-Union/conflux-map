package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import cn.net.rms.confluxmap.core.util.Argb;
import org.junit.jupiter.api.Test;

class BiomeColorPaletteTest {
    @Test
    void renamedVanillaBiomeAndItsCubiomesIdShareOneStableColor() {
        assertEquals(
            BiomeColorPalette.color("minecraft:snowy_tundra"),
            BiomeColorPalette.color("minecraft:snowy_plains")
        );
        assertEquals(
            BiomeColorPalette.color("minecraft:snowy_plains"),
            BiomeColorPalette.colorForCubiomes(12)
        );
    }

    @Test
    void differentIdentitiesAreOpaqueAndUnknownIdentityIsTransparent() {
        final int plains = BiomeColorPalette.color("minecraft:plains");
        assertEquals(255, Argb.alpha(plains));
        assertNotEquals(plains, BiomeColorPalette.color("minecraft:forest"));
        assertNotEquals(plains, BiomeColorPalette.color("example:plains"));
        assertEquals(Argb.TRANSPARENT, BiomeColorPalette.color(null));
    }

    @Test
    void vanillaBiomesUseTerrainOrientedColorsInsteadOfAHashRainbow() {
        assertEquals(0xFF8FB95B, BiomeColorPalette.color("minecraft:plains"));
        assertEquals(0xFF6FA45A, BiomeColorPalette.color("minecraft:forest"));
        assertEquals(0xFF4B85B7, BiomeColorPalette.color("minecraft:ocean"));
        assertEquals(0xFFD8C17A, BiomeColorPalette.color("minecraft:desert"));
        assertEquals(0xFFE5EEF2, BiomeColorPalette.color("minecraft:snowy_plains"));
    }
}
