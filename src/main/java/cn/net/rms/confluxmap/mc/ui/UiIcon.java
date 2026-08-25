package cn.net.rms.confluxmap.mc.ui;

import net.minecraft.util.Identifier;

/** A resolved UI icon together with the color contract expected by its source texture. */
public record UiIcon(UiTextureRegion region, ColorMode colorMode) {
    public static UiIcon monochrome(final Identifier texture) {
        return new UiIcon(UiTextureRegion.full(texture), ColorMode.MONOCHROME_MASK);
    }

    public static UiIcon fullColor(final UiTextureRegion region) {
        return new UiIcon(region, ColorMode.FULL_COLOR);
    }

    public enum ColorMode {
        /** White alpha mask whose RGB is supplied by the button state. */
        MONOCHROME_MASK,
        /** Complete resource-pack artwork whose enabled-state RGB values must be preserved. */
        FULL_COLOR
    }
}
