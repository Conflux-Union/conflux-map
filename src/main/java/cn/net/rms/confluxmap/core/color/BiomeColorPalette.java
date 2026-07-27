package cn.net.rms.confluxmap.core.color;

import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.util.Argb;

/**
 * Stable debug palette keyed by biome resource identifier. Colors deliberately depend only on
 * identity, so captured and predicted copies of the same biome match across sessions and no
 * caller needs to infer identity from Minecraft's position-dependent tint colors.
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
            final java.util.OptionalInt cubiomesId = CubiomesBiomeIds.idForName(
                biomeId.substring("minecraft:".length())
            );
            if (cubiomesId.isPresent()) {
                paletteKey = "cubiomes:" + cubiomesId.getAsInt();
            }
        }
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

    public static int colorForCubiomes(final int cubiomesId) {
        return color("cubiomes:" + cubiomesId);
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
