package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    void commonVanillaBiomesUseMutedNaturalColors() {
        assertEquals(0xFF8FBC68, BiomeColorPalette.color("minecraft:plains"));
        assertEquals(0xFF477A45, BiomeColorPalette.color("minecraft:forest"));
        assertEquals(0xFFD7C27A, BiomeColorPalette.color("minecraft:desert"));
        assertEquals(0xFF477FA8, BiomeColorPalette.color("minecraft:ocean"));
        assertEquals(0xFFDDEAF0, BiomeColorPalette.color("minecraft:snowy_plains"));
        assertEquals(0xFFA45A4E, BiomeColorPalette.color("minecraft:nether_wastes"));
    }

    @Test
    void moddedBiomeFallbackIsStableOpaqueAndMuted() {
        final int first = BiomeColorPalette.color("example:crystal_fields");
        final int second = BiomeColorPalette.color("example:crystal_fields");

        assertEquals(first, second);
        assertEquals(255, Argb.alpha(first));
        final int max = Math.max(Argb.red(first), Math.max(Argb.green(first), Argb.blue(first)));
        final int min = Math.min(Argb.red(first), Math.min(Argb.green(first), Argb.blue(first)));
        assertTrue(max - min <= 72);
    }
}
