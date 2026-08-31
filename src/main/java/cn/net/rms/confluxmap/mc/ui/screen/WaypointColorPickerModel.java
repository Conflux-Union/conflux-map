package cn.net.rms.confluxmap.mc.ui.screen;

import java.awt.Color;
import java.util.Locale;

/** Minecraft-free color state for the waypoint HSV picker. */
final class WaypointColorPickerModel {
    private float hue;
    private float saturation;
    private float value;

    WaypointColorPickerModel(final int colorArgb) {
        final float[] hsv = Color.RGBtoHSB(
            colorArgb >>> 16 & 0xFF,
            colorArgb >>> 8 & 0xFF,
            colorArgb & 0xFF,
            null
        );
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
    }

    int colorArgb() {
        return fromHsv(hue, saturation, value);
    }

    String hex() {
        return String.format(Locale.ROOT, "#%06X", colorArgb() & 0xFFFFFF);
    }

    boolean setHex(final String text) {
        if (text == null) {
            return false;
        }
        final int offset = text.startsWith("#") ? 1 : 0;
        if (text.length() - offset != 6) {
            return false;
        }
        int rgb = 0;
        for (int index = offset; index < text.length(); index++) {
            final int digit = Character.digit(text.charAt(index), 16);
            if (digit < 0) {
                return false;
            }
            rgb = rgb << 4 | digit;
        }
        final float[] hsv = Color.RGBtoHSB(rgb >>> 16 & 0xFF, rgb >>> 8 & 0xFF, rgb & 0xFF, null);
        hue = hsv[0];
        saturation = hsv[1];
        value = hsv[2];
        return true;
    }

    void selectHue(final double mouseY, final int top, final int height) {
        hue = clamp((float) ((mouseY - top) / Math.max(1, height)));
    }

    void selectSaturationValue(
        final double relativeX,
        final double relativeY,
        final int width,
        final int height
    ) {
        saturation = clamp((float) (relativeX / Math.max(1, width)));
        value = 1.0f - clamp((float) (relativeY / Math.max(1, height)));
    }

    float hue() {
        return hue;
    }

    float saturation() {
        return saturation;
    }

    float value() {
        return value;
    }

    static int fromHsv(final float hue, final float saturation, final float value) {
        return Color.HSBtoRGB(clamp(hue), clamp(saturation), clamp(value));
    }

    private static float clamp(final float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }
}
