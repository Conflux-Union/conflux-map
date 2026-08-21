package cn.net.rms.confluxmap.mc.ui;

import net.minecraft.util.Identifier;

/** A complete texture or one normalized region inside a resource-pack-provided atlas. */
public record UiTextureRegion(
    Identifier texture,
    float u0,
    float v0,
    float u1,
    float v1
) {
    public static UiTextureRegion full(final Identifier texture) {
        return new UiTextureRegion(texture, 0f, 0f, 1f, 1f);
    }

    public static UiTextureRegion atlas(
        final Identifier texture,
        final int x,
        final int y,
        final int width,
        final int height,
        final int atlasWidth,
        final int atlasHeight
    ) {
        return new UiTextureRegion(
            texture,
            x / (float) atlasWidth,
            y / (float) atlasHeight,
            (x + width) / (float) atlasWidth,
            (y + height) / (float) atlasHeight
        );
    }
}
