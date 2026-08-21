package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;

/** Resource-pack player marker with selectable code-drawn fallbacks. */
public final class PlayerMarkerRenderer {
    private static final int OUTLINE_COLOR = 0xFF101010;
    private static final int XAERO_TOP_OUTLINE_COLOR = 0x4D101010;
    private static final int XAERO_BOTTOM_OUTLINE_COLOR = 0x80101010;
    private static final float OUTLINE_WIDTH = 2.8f;
    private static final float FILL_WIDTH = 1.4f;
    private static final float TIP_Y = -4f;
    private static final float ARM_END_X = 4.15f;
    private static final float ARM_END_Y = 2.5f;
    static final ArmGeometry LEFT_OUTLINE = arm(0f, TIP_Y, -ARM_END_X, ARM_END_Y, OUTLINE_WIDTH);
    static final ArmGeometry RIGHT_OUTLINE = arm(0f, TIP_Y, ARM_END_X, ARM_END_Y, OUTLINE_WIDTH);
    static final ArmGeometry LEFT_FILL = arm(0f, TIP_Y, -ARM_END_X, ARM_END_Y, FILL_WIDTH);
    static final ArmGeometry RIGHT_FILL = arm(0f, TIP_Y, ARM_END_X, ARM_END_Y, FILL_WIDTH);
    static final TriangleGeometry JOIN_OUTLINE = new TriangleGeometry(
        0f, -5.2f, -2.45f, 0.2f, 2.45f, 0.2f
    );
    static final TriangleGeometry JOIN_FILL = new TriangleGeometry(
        0f, -4.75f, -1.95f, -0.15f, 1.95f, -0.15f
    );
    static final TriangleGeometry TRADITIONAL_OUTLINE = new TriangleGeometry(
        0f, -6.5f, -5f, 5.5f, 5f, 5.5f
    );
    static final TriangleGeometry TRADITIONAL_FILL = new TriangleGeometry(
        0f, -5f, -3.5f, 4f, 3.5f, 4f
    );

    private PlayerMarkerRenderer() {
    }

    /**
     * Draws a marker centered on the player. Resource textures face up; {@code angle} rotates
     * either the texture or the fallback chevron so that up continues to mean player-forward.
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
        final Optional<UiResourceTheme.PlayerMarkerTexture> texture = theme.playerMarker();
        matrices.push();
        matrices.translate(centerX, centerY, 0);
        RenderUtil.rotateZ(
            matrices,
            angle + texture.map(UiResourceTheme.PlayerMarkerTexture::rotationOffset).orElse(0f)
        );
        if (texture.isPresent()) {
            drawTexture(client, matrices, texture.get(), fallbackColor);
        } else if (fallbackStyle == ConfluxConfig.PlayerMarkerStyle.TRADITIONAL) {
            drawTraditional(matrices, fallbackColor);
        } else {
            drawModern(matrices, fallbackColor);
        }
        matrices.pop();
    }

    private static void drawTexture(
        final MinecraftClient client,
        final MatrixStack matrices,
        final UiResourceTheme.PlayerMarkerTexture marker,
        final int fallbackColor
    ) {
        final UiTextureRegion texture = marker.texture();
        RenderUtil.beginTexturedQuads();
        RenderUtil.bindTexture(client, texture.texture());
        if (marker.tintWithFallbackColor()) {
            drawTintedTexture(
                matrices, marker, -marker.outlineOffsetY(), XAERO_TOP_OUTLINE_COLOR
            );
            drawTintedTexture(
                matrices, marker, marker.outlineOffsetY(), XAERO_BOTTOM_OUTLINE_COLOR
            );
            drawTintedTexture(matrices, marker, 0f, fallbackColor);
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

    private static void drawTraditional(final MatrixStack matrices, final int fillColor) {
        drawTriangle(matrices, TRADITIONAL_OUTLINE, OUTLINE_COLOR);
        drawTriangle(matrices, TRADITIONAL_FILL, fillColor);
    }

    private static void drawModern(final MatrixStack matrices, final int fillColor) {
        drawTriangle(matrices, JOIN_OUTLINE, OUTLINE_COLOR);
        drawArm(matrices, LEFT_OUTLINE, OUTLINE_COLOR);
        drawArm(matrices, RIGHT_OUTLINE, OUTLINE_COLOR);
        drawArm(matrices, LEFT_FILL, fillColor);
        drawArm(matrices, RIGHT_FILL, fillColor);
        drawTriangle(matrices, JOIN_FILL, fillColor);
    }

    private static void drawTriangle(
        final MatrixStack matrices,
        final TriangleGeometry triangle,
        final int color
    ) {
        RenderUtil.fillTriangle(
            matrices,
            triangle.ax(), triangle.ay(),
            triangle.bx(), triangle.by(),
            triangle.cx(), triangle.cy(),
            color
        );
    }

    private static void drawArm(
        final MatrixStack matrices,
        final ArmGeometry arm,
        final int color
    ) {
        RenderUtil.fillTriangle(
            matrices, arm.ax(), arm.ay(), arm.cx(), arm.cy(), arm.bx(), arm.by(), color
        );
        RenderUtil.fillTriangle(
            matrices, arm.ax(), arm.ay(), arm.dx(), arm.dy(), arm.cx(), arm.cy(), color
        );
    }

    private static ArmGeometry arm(
        final float x0,
        final float y0,
        final float x1,
        final float y1,
        final float width
    ) {
        final float dx = x1 - x0;
        final float dy = y1 - y0;
        final float halfScale = width / (2f * (float) Math.sqrt(dx * dx + dy * dy));
        final float offsetX = -dy * halfScale;
        final float offsetY = dx * halfScale;
        final float ax = x0 + offsetX;
        final float ay = y0 + offsetY;
        final float bx = x0 - offsetX;
        final float by = y0 - offsetY;
        final float cx = x1 - offsetX;
        final float cy = y1 - offsetY;
        return new ArmGeometry(ax, ay, bx, by, cx, cy, x1 + offsetX, y1 + offsetY);
    }

    record ArmGeometry(
        float ax,
        float ay,
        float bx,
        float by,
        float cx,
        float cy,
        float dx,
        float dy
    ) {}

    record TriangleGeometry(float ax, float ay, float bx, float by, float cx, float cy) {}
}
