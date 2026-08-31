package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
//#if MC<11900
import com.mojang.blaze3d.systems.RenderSystem;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Texture-backed player and detached-camera marker renderer. */
public final class PlayerMarkerRenderer {
    private static final int XAERO_TOP_OUTLINE_COLOR = 0x4D101010;
    private static final int XAERO_BOTTOM_OUTLINE_COLOR = 0x80101010;
    private static final Identifier MODERN_MARKER = Ids.of(
        "confluxmap", "textures/gui/markers/player_marker_modern.png"
    );
    private static final Identifier TRADITIONAL_MARKER = Ids.of(
        "confluxmap", "textures/gui/markers/player_marker_traditional.png"
    );
    private PlayerMarkerRenderer() {
    }

    /**
     * Draws a player-style marker at a map position. Marker textures face up; {@code angle}
     * rotates the texture so that up means the marked entity's forward.
     */
    public static void draw(
        final MinecraftClient client,
        final MatrixStack matrices,
        final UiResourceTheme theme,
        final ConfluxConfig.PlayerMarkerStyle fallbackStyle,
        final float centerX,
        final float centerY,
        final float angle,
        final int fallbackColor
    ) {
        draw(client, matrices, theme, fallbackStyle, centerX, centerY, angle, fallbackColor, 1f);
    }

    public static void draw(
        final MinecraftClient client,
        final MatrixStack matrices,
        final UiResourceTheme theme,
        final ConfluxConfig.PlayerMarkerStyle fallbackStyle,
        final float centerX,
        final float centerY,
        final float angle,
        final int fallbackColor,
        final float opacity
    ) {
        final UiResourceTheme.PlayerMarkerTexture texture =
            theme.playerMarker().orElseGet(() -> builtInMarker(fallbackStyle));
        //#if MC<11900
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        //#endif
        matrices.push();
        try {
            matrices.translate(centerX, centerY, 0);
            RenderUtil.rotateZ(
                matrices,
                angle + texture.rotationOffset()
            );
            drawTexture(client, matrices, texture, fallbackColor, opacity);
        } finally {
            matrices.pop();
            //#if MC<11900
            RenderSystem.depthMask(true);
            RenderSystem.enableDepthTest();
            //#endif
        }
    }

    static UiResourceTheme.PlayerMarkerTexture builtInMarker(
        final ConfluxConfig.PlayerMarkerStyle style
    ) {
        return UiResourceTheme.PlayerMarkerTexture.fullColor(
            style == ConfluxConfig.PlayerMarkerStyle.TRADITIONAL
                ? TRADITIONAL_MARKER
                : MODERN_MARKER
        );
    }

    private static void drawTexture(
        final MinecraftClient client,
        final MatrixStack matrices,
        final UiResourceTheme.PlayerMarkerTexture marker,
        final int fallbackColor,
        final float opacity
    ) {
        final UiTextureRegion texture = marker.texture();
        RenderUtil.beginTexturedQuads();
        RenderUtil.bindTexture(client, texture.texture());
        if (marker.tintWithFallbackColor()) {
            drawTintedTexture(
                matrices,
                marker,
                -marker.outlineOffsetY(),
                colorAtOpacity(XAERO_TOP_OUTLINE_COLOR, opacity)
            );
            drawTintedTexture(
                matrices,
                marker,
                marker.outlineOffsetY(),
                colorAtOpacity(XAERO_BOTTOM_OUTLINE_COLOR, opacity)
            );
            drawTintedTexture(matrices, marker, 0f, colorAtOpacity(fallbackColor, opacity));
            return;
        }
        if (opacity < 1f) {
            drawTintedTexture(matrices, marker, 0f, colorAtOpacity(0xFFFFFFFF, opacity));
            return;
        }
        RenderUtil.drawQuad(
            matrices,
            marker.x(),
            marker.y(),
            marker.width(),
            marker.height(),
            texture.u0(),
            texture.v0(),
            texture.u1(),
            texture.v1()
        );
    }

    private static void drawTintedTexture(
        final MatrixStack matrices,
        final UiResourceTheme.PlayerMarkerTexture marker,
        final float offsetY,
        final int color
    ) {
        final UiTextureRegion texture = marker.texture();
        RenderUtil.drawTintedQuad(
            matrices,
            marker.x(),
            marker.y() + offsetY,
            marker.width(),
            marker.height(),
            texture.u0(),
            texture.v0(),
            texture.u1(),
            texture.v1(),
            color
        );
    }

    static int colorAtOpacity(final int color, final float opacity) {
        return Argb.scaleAlpha(color, opacity);
    }
}
