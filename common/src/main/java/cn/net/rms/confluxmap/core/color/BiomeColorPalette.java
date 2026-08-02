package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Stable terrain-oriented palette keyed by biome resource identifier. Captured and predicted
 * copies of the same biome use the same color without trying to reproduce Minecraft's
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
            final String biomeName = biomeId.substring("minecraft:".length());
            final java.util.OptionalInt cubiomesId = CubiomesBiomeIds.idForName(
                biomeName
            );
            if (cubiomesId.isPresent()) {
                return colorForCubiomes(cubiomesId.getAsInt());
            }
            return vanillaColor(biomeName, paletteKey);
        }
        return stableColor(paletteKey);
    }

    public static int colorForCubiomes(final int cubiomesId) {
        return CubiomesBiomeIds.nameForId(cubiomesId)
            .map(name -> vanillaColor(name, "cubiomes:" + cubiomesId))
            .orElseGet(() -> stableColor("cubiomes:" + cubiomesId));
    }

    private static int vanillaColor(final String biomeName, final String fallbackKey) {
        if (biomeName.contains("ocean")) {
            return biomeName.contains("frozen") ? 0xFF91B9D4 : 0xFF4B85B7;
        }
        if (biomeName.contains("river")) {
            return biomeName.contains("frozen") ? 0xFF91B9D4 : 0xFF5C9FC5;
        }
        if (biomeName.contains("the_end") || biomeName.startsWith("end_")) {
            return 0xFFD4C67B;
        }
        if (biomeName.contains("mushroom")) {
            return 0xFF9E82AB;
        }
        if (biomeName.contains("beach") || biomeName.contains("shore")) {
            return biomeName.contains("snow") ? 0xFFE5EEF2 : 0xFFE3D39B;
        }
        if (biomeName.contains("desert")) {
            return 0xFFD8C17A;
        }
        if (biomeName.contains("badlands")) {
            return 0xFFC97A4A;
        }
        if (biomeName.contains("snow") || biomeName.contains("frozen") || biomeName.contains("ice")) {
            return 0xFFE5EEF2;
        }
        if (biomeName.contains("peak") || biomeName.contains("mountain")
            || biomeName.contains("windswept") || biomeName.contains("stone")) {
            return 0xFF9DA2A0;
        }
        if (biomeName.contains("deep_dark")) {
            return 0xFF40505A;
        }
        if (biomeName.contains("dripstone")) {
            return 0xFF9E7E67;
        }
        if (biomeName.contains("lush_caves")) {
            return 0xFF62A76A;
        }
        if (biomeName.contains("mangrove") || biomeName.contains("swamp")) {
            return 0xFF718C4A;
        }
        if (biomeName.contains("jungle")) {
            return 0xFF4F8B45;
        }
        if (biomeName.contains("taiga")) {
            return 0xFF5F8B75;
        }
        if (biomeName.contains("dark_forest") || biomeName.contains("pale_garden")) {
            return 0xFF456445;
        }
        if (biomeName.contains("forest")) {
            return 0xFF6FA45A;
        }
        if (biomeName.contains("savanna")) {
            return 0xFFA6B75B;
        }
        if (biomeName.contains("cherry")) {
            return 0xFFC897A7;
        }
        if (biomeName.contains("meadow") || biomeName.contains("grove")) {
            return 0xFF9FC46C;
        }
        if (biomeName.contains("plains")) {
            return 0xFF8FB95B;
        }
        return stableColor(fallbackKey);
    }

    private static int stableColor(final String paletteKey) {
        int hash = 0x811C9DC5;
        for (int i = 0; i < paletteKey.length(); i++) {
            hash ^= paletteKey.charAt(i);
            hash *= 0x01000193;
        }
        final float hue = (hash & 0xFFFF) / 65536.0f;
        final float saturation = 0.48f + ((hash >>> 16) & 0x0F) / 100.0f;
        final float value = 0.72f + ((hash >>> 20) & 0x0F) / 100.0f;
        return hsv(hue, saturation, Math.min(value, 0.87f));
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
