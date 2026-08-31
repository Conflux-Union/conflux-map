package cn.net.rms.confluxmap.mc.radar;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.util.Argb;
import net.minecraft.util.Identifier;

/** The two independently tinted texture layers used by vanilla tropical fish. */
final class TropicalFishPortrait {
    record Appearance(Identifier patternTexture, int baseTint, int patternTint) {
    }

    private TropicalFishPortrait() {
    }

    static Appearance appearance(
        final String pattern,
        final float[] baseColor,
        final float[] patternColor
    ) {
        return appearance(patternTexture(pattern), baseColor, patternColor);
    }

    static Appearance appearance(
        final Identifier patternTexture,
        final float[] baseColor,
        final float[] patternColor
    ) {
        return new Appearance(
            patternTexture, opaqueTint(baseColor), opaqueTint(patternColor)
        );
    }

    static Appearance appearance(
        final String pattern,
        final int baseColor,
        final int patternColor
    ) {
        return new Appearance(
            patternTexture(pattern), 0xFF000000 | baseColor, 0xFF000000 | patternColor
        );
    }

    static Identifier patternTexture(final String pattern) {
        final String file = switch (pattern) {
            case "kob" -> "tropical_a_pattern_1.png";
            case "sunstreak" -> "tropical_a_pattern_2.png";
            case "snooper" -> "tropical_a_pattern_3.png";
            case "dasher" -> "tropical_a_pattern_4.png";
            case "brinely" -> "tropical_a_pattern_5.png";
            case "spotty" -> "tropical_a_pattern_6.png";
            case "flopper" -> "tropical_b_pattern_1.png";
            case "stripey" -> "tropical_b_pattern_2.png";
            case "glitter" -> "tropical_b_pattern_3.png";
            case "blockfish" -> "tropical_b_pattern_4.png";
            case "betty" -> "tropical_b_pattern_5.png";
            case "clayfish" -> "tropical_b_pattern_6.png";
            default -> throw new IllegalArgumentException("Unknown tropical fish pattern: " + pattern);
        };
        return Ids.of("minecraft", "textures/entity/fish/" + file);
    }

    private static int opaqueTint(final float[] color) {
        return Argb.pack(
            255,
            Math.round(color[0] * 255f),
            Math.round(color[1] * 255f),
            Math.round(color[2] * 255f)
        );
    }
}
