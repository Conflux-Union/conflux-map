package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Stable natural terrain palette keyed by biome resource identifier. Captured and predicted
 * copies of the same biome use the same color without relying on Minecraft's
 * position-dependent grass and foliage tinting.
 */
public final class BiomeColorPalette {
    private BiomeColorPalette() {
    }

    public static int color(final String biomeId) {
        if (biomeId == null || biomeId.isEmpty()) {
            return Argb.TRANSPARENT;
        }
        String paletteKey = biomeId;
        if (biomeId.startsWith("minecraft:")) {
            final String name = biomeId.substring("minecraft:".length());
            final java.util.OptionalInt cubiomesId = CubiomesBiomeIds.idForName(
                name
            );
            if (cubiomesId.isPresent()) {
                return naturalVanillaColor(
                    CubiomesBiomeIds.nameForId(cubiomesId.getAsInt()).orElse(name)
                );
            }
            final int natural = naturalVanillaColor(name);
            if (natural != Argb.TRANSPARENT) {
                return natural;
            }
            return fallbackColor(paletteKey);
        }
        return fallbackColor(paletteKey);
    }

    public static int colorForCubiomes(final int cubiomesId) {
        return CubiomesBiomeIds.nameForId(cubiomesId)
            .map(BiomeColorPalette::naturalVanillaColor)
            .orElseGet(() -> fallbackColor("cubiomes:" + cubiomesId));
    }

    private static int fallbackColor(final String paletteKey) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < paletteKey.length(); i++) {
            hash ^= paletteKey.charAt(i);
            hash *= 0x01000193;
        }
        final float hue = (hash & 0xFFFF) / 65536.0f;
        final float saturation = 0.22f + ((hash >>> 16) & 0x0F) / 150.0f;
        final float value = 0.62f + ((hash >>> 20) & 0x0F) / 100.0f;
        return hsv(hue, Math.min(saturation, 0.32f), Math.min(value, 0.77f));
    }

    private static int naturalVanillaColor(final String name) {
        return switch (name) {
            case "plains" -> 0xFF8FBC68;
            case "sunflower_plains" -> 0xFF9BC76A;
            case "meadow" -> 0xFF83B86F;
            case "forest", "wooded_hills", "wooded_mountains" -> 0xFF477A45;
            case "flower_forest" -> 0xFF6F9E61;
            case "birch_forest", "birch_forest_hills", "tall_birch_forest", "tall_birch_hills" -> 0xFF719B58;
            case "dark_forest", "dark_forest_hills" -> 0xFF35583B;
            case "cherry_grove" -> 0xFFD996A8;
            case "pale_garden" -> 0xFFA8ADA3;
            case "taiga", "taiga_hills", "taiga_mountains", "snowy_taiga", "snowy_taiga_hills", "snowy_taiga_mountains" -> 0xFF587660;
            case "giant_tree_taiga", "giant_tree_taiga_hills", "giant_spruce_taiga", "giant_spruce_taiga_hills" -> 0xFF4C6855;
            case "swamp", "swamp_hills" -> 0xFF657447;
            case "mangrove_swamp" -> 0xFF536F4E;
            case "jungle", "jungle_hills", "jungle_edge", "modified_jungle", "modified_jungle_edge", "bamboo_jungle", "bamboo_jungle_hills" -> 0xFF3F8146;
            case "desert", "desert_hills", "desert_lakes", "beach" -> 0xFFD7C27A;
            case "savanna", "savanna_plateau", "shattered_savanna", "shattered_savanna_plateau" -> 0xFFB6A65D;
            case "badlands", "badlands_plateau", "wooded_badlands_plateau", "eroded_badlands", "modified_wooded_badlands_plateau", "modified_badlands_plateau" -> 0xFFB8754A;
            case "mountains", "gravelly_mountains", "modified_gravelly_mountains", "stony_peaks", "stone_shore" -> 0xFF858B83;
            case "snowy_tundra", "snowy_mountains", "snowy_slopes", "snowy_beach", "grove" -> 0xFFDDEAF0;
            case "ice_spikes", "frozen_peaks" -> 0xFFE9F1F4;
            case "jagged_peaks" -> 0xFFA9B1AE;
            case "ocean" -> 0xFF477FA8;
            case "deep_ocean" -> 0xFF365F86;
            case "warm_ocean" -> 0xFF4A9EAA;
            case "lukewarm_ocean", "deep_lukewarm_ocean" -> 0xFF458EAD;
            case "cold_ocean", "deep_cold_ocean" -> 0xFF426B96;
            case "frozen_ocean", "deep_frozen_ocean" -> 0xFF7BA6BE;
            case "river" -> 0xFF4F91BD;
            case "frozen_river" -> 0xFF89B7CC;
            case "mushroom_fields", "mushroom_field_shore" -> 0xFF9B6C8F;
            case "dripstone_caves" -> 0xFF76685B;
            case "lush_caves" -> 0xFF4E8A59;
            case "deep_dark" -> 0xFF33434A;
            case "the_end", "small_end_islands", "end_midlands", "end_highlands", "end_barrens" -> 0xFF929567;
            case "nether_wastes" -> 0xFFA45A4E;
            case "crimson_forest" -> 0xFF8F3F49;
            case "warped_forest" -> 0xFF3E827B;
            case "soul_sand_valley" -> 0xFF71665E;
            case "basalt_deltas" -> 0xFF4F4C51;
            default -> Argb.TRANSPARENT;
        };
    }
    private static int hsv(final float hue, final float saturation, final float value) {
        final float scaled = hue * 6.0f;
        final int sector = (int) Math.floor(scaled);
        final float fraction = scaled - sector;
        final float p = value * (1.0f - saturation);
        final float q = value * (1.0f - saturation * fraction);
        final float t = value * (1.0f - saturation * (1.0f - fraction));
        final float r;
        final float g;
        final float b;
        switch (sector % 6) {
            case 0 -> {
                r = value;
                g = t;
                b = p;
            }
            case 1 -> {
                r = q;
                g = value;
                b = p;
            }
            case 2 -> {
                r = p;
                g = value;
                b = t;
            }
            case 3 -> {
                r = p;
                g = q;
                b = value;
            }
            case 4 -> {
                r = t;
                g = p;
                b = value;
            }
            default -> {
                r = value;
                g = p;
                b = q;
            }
        }
        return Argb.pack(255, Math.round(r * 255), Math.round(g * 255), Math.round(b * 255));
    }
}
