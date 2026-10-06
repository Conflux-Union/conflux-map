package cn.net.rms.confluxmap.core.color;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
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
    void modernVanillaBiomesKeepTheirIdentityColor() {
        assertEquals(0xFFDF6827, BiomeColorPalette.color("minecraft:dappled_forest"));
        assertEquals(0xFFABA64F, BiomeColorPalette.color("minecraft:sulfur_caves"));
        assertEquals(
            BiomeColorPalette.color("minecraft:dappled_forest"),
            BiomeColorPalette.colorForCubiomes(188)
        );
        assertEquals(
            BiomeColorPalette.color("minecraft:sulfur_caves"),
            BiomeColorPalette.colorForCubiomes(187)
        );
    }

    @Test
    void everyCubiomesKnownNameAndIdIsOpaque() {
        // The flat biome view draws captured and predicted pixels from this palette; a name or
        // id that resolves to transparent punches a hole in the map instead of coloring a biome
        // (the 26.3 dappled_forest symptom).
        for (int id = 0; id < 256; id++) {
            final int byId = BiomeColorPalette.colorForCubiomes(id);
            assertNotEquals(Argb.TRANSPARENT, byId, "cubiomes id " + id);
            assertEquals(255, Argb.alpha(byId), "cubiomes id " + id);
            for (final String name : CubiomesBiomeIds.namesForId(id)) {
                final int byName = BiomeColorPalette.color("minecraft:" + name);
                assertNotEquals(Argb.TRANSPARENT, byName, "minecraft:" + name);
                assertEquals(255, Argb.alpha(byName), "minecraft:" + name);
            }
        }
    }

    @Test
    void namesMissingFromTheNaturalTableFallBackToAnOpaqueColor() {
        // A name the natural table never learned must not become a transparent hole; it takes
        // the stable hash color like any unknown modded biome.
        final int unmapped = BiomeColorPalette.color("minecraft:the_void");
        assertNotEquals(Argb.TRANSPARENT, unmapped);
        assertEquals(255, Argb.alpha(unmapped));
        final int unmappedId = BiomeColorPalette.colorForCubiomes(200);
        assertNotEquals(Argb.TRANSPARENT, unmappedId);
        assertEquals(255, Argb.alpha(unmappedId));
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
