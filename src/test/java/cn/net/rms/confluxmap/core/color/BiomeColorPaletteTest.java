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
}
