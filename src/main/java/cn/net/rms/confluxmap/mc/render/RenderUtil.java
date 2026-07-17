package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Matrix4f;

/**
 * 1.17.1 core-shader helpers for drawing dynamically-generated textures (map
 * tiles) as flat GUI quads. Render thread only; every call here assumes a
 * current GL context.
 */
public final class RenderUtil {
    private RenderUtil() {
    }

    /** Selects the flat position+texture shader and standard alpha blending, for textured GUI quads. */
    public static void beginTexturedQuads() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public static void bindTexture(final int glId) {
        RenderSystem.setShaderTexture(0, glId);
    }

    /**
     * Draws one axis-aligned textured quad in GUI space. Must be called between
     * {@link #beginTexturedQuads()} and a bound texture ({@link #bindTexture(int)}).
     */
    public static void drawQuad(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1
    ) {
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(model, x, y + height, 0).texture(u0, v1).next();
        buffer.vertex(model, x + width, y + height, 0).texture(u1, v1).next();
        buffer.vertex(model, x + width, y, 0).texture(u1, v0).next();
        buffer.vertex(model, x, y, 0).texture(u0, v0).next();
        tessellator.draw();
    }

    /**
     * Enables the GL scissor test for a rectangle given in GUI (scaled) coordinates,
     * converting to framebuffer pixels via the window's current scale factor.
     */
    public static void enableScissor(
        final MinecraftClient client,
        final int guiX,
        final int guiY,
        final int guiWidth,
        final int guiHeight
    ) {
        final Window window = client.getWindow();
        final double scale = window.getScaleFactor();
        final int fbHeight = window.getFramebufferHeight();
        final int x = (int) Math.round(guiX * scale);
        final int w = (int) Math.round(guiWidth * scale);
        final int h = (int) Math.round(guiHeight * scale);
        final int y = fbHeight - (int) Math.round((guiY + guiHeight) * scale);
        RenderSystem.enableScissor(x, y, w, h);
    }

    public static void disableScissor() {
        RenderSystem.disableScissor();
    }

    /** Flat-colored filled triangle in GUI space (player arrow etc.). */
    public static void fillTriangle(
        final MatrixStack matrices,
        final float x0, final float y0,
        final float x1, final float y1,
        final float x2, final float y2,
        final int argbColor
    ) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        buffer.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        buffer.vertex(model, x1, y1, 0).color(r, g, b, a).next();
        buffer.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        tessellator.draw();
    }

    /**
     * Pass 1 of the circular mask: stamp the destination alpha channel with 0 across
     * the bounding square, then 1 inside the circle. Colors are untouched. After this,
     * draw the map with {@link #beginMaskedQuads()} so it only lands inside the circle,
     * then call {@link #endMaskedQuads(MatrixStack, float, float, float, float)}.
     */
    public static void stampCircleAlpha(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float radius
    ) {
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(model, centerX - radius, centerY + radius, 0).color(0f, 0f, 0f, 0f).next();
        buffer.vertex(model, centerX + radius, centerY + radius, 0).color(0f, 0f, 0f, 0f).next();
        buffer.vertex(model, centerX + radius, centerY - radius, 0).color(0f, 0f, 0f, 0f).next();
        buffer.vertex(model, centerX - radius, centerY - radius, 0).color(0f, 0f, 0f, 0f).next();
        tessellator.draw();
        buffer.begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_COLOR);
        buffer.vertex(model, centerX, centerY, 0).color(0f, 0f, 0f, 1f).next();
        final int segments = 48;
        for (int i = 0; i <= segments; i++) {
            final double angle = 2.0 * Math.PI * i / segments;
            buffer.vertex(model, centerX + (float) (Math.cos(angle) * radius), centerY + (float) (Math.sin(angle) * radius), 0)
                .color(0f, 0f, 0f, 1f).next();
        }
        tessellator.draw();
        RenderSystem.colorMask(true, true, true, true);
    }

    /** Pass 2 setup: textured quads that only render where the destination alpha was stamped 1. */
    public static void beginMaskedQuads() {
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.DST_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE_MINUS_DST_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ZERO,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE
        );
    }

    /** Restores default blending and repairs the destination alpha the mask pass dirtied. */
    public static void endMaskedQuads(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height
    ) {
        RenderSystem.defaultBlendFunc();
        RenderSystem.colorMask(false, false, false, true);
        RenderSystem.disableBlend();
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(model, x, y + height, 0).color(0f, 0f, 0f, 1f).next();
        buffer.vertex(model, x + width, y + height, 0).color(0f, 0f, 0f, 1f).next();
        buffer.vertex(model, x + width, y, 0).color(0f, 0f, 0f, 1f).next();
        buffer.vertex(model, x, y, 0).color(0f, 0f, 0f, 1f).next();
        tessellator.draw();
        RenderSystem.colorMask(true, true, true, true);
        RenderSystem.enableBlend();
    }

    /** Anti-clockwise ring outline (circle border), drawn as a triangle strip. */
    public static void drawRing(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float outerRadius,
        final float thickness,
        final int argbColor
    ) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        final int segments = 48;
        final float inner = outerRadius - thickness;
        for (int i = 0; i <= segments; i++) {
            final double angle = 2.0 * Math.PI * i / segments;
            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            buffer.vertex(model, centerX + cos * outerRadius, centerY + sin * outerRadius, 0).color(r, g, b, a).next();
            buffer.vertex(model, centerX + cos * inner, centerY + sin * inner, 0).color(r, g, b, a).next();
        }
        tessellator.draw();
    }

    /**
     * Selects the flat position+color shader and additive-ish blending
     * ({@code src*alpha + dst*1}), for glow-style translucent 3D geometry like waypoint
     * beams (see {@code mc.ui.world.WaypointWorldRenderer}). Unlike {@link #fillTriangle}
     * and {@link #fillRect}, this does not reset itself on every draw call - callers issue
     * this once, draw as many {@link #fillTriangle3D} calls as needed, then restore normal
     * blending with {@link #restoreDefaultBlend()} when done.
     */
    public static void beginAdditiveTriangles() {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ZERO
        );
    }

    /** Restores standard alpha blending after {@link #beginAdditiveTriangles()}. */
    public static void restoreDefaultBlend() {
        RenderSystem.defaultBlendFunc();
    }

    /**
     * Flat-colored filled triangle with three independent coordinates, for true 3D
     * world-space geometry drawn from a {@code WorldRenderEvents} callback (unlike
     * {@link #fillTriangle}, which always draws on the local matrix's Z=0 plane for flat
     * GUI shapes). Assumes the shader and blend state are already set up by the caller -
     * see {@link #beginAdditiveTriangles()}.
     */
    public static void fillTriangle3D(
        final MatrixStack matrices,
        final float x0, final float y0, final float z0,
        final float x1, final float y1, final float z1,
        final float x2, final float y2, final float z2,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        buffer.vertex(model, x0, y0, z0).color(r, g, b, a).next();
        buffer.vertex(model, x1, y1, z1).color(r, g, b, a).next();
        buffer.vertex(model, x2, y2, z2).color(r, g, b, a).next();
        tessellator.draw();
    }

    /** Flat-colored axis-aligned quad (background/border), independent of any bound texture. */
    public static void fillRect(final MatrixStack matrices, final float x, final float y, final float width, final float height, final int argbColor) {
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final Matrix4f model = matrices.peek().getModel();
        final Tessellator tessellator = Tessellator.getInstance();
        final BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        buffer.vertex(model, x, y + height, 0).color(r, g, b, a).next();
        buffer.vertex(model, x + width, y + height, 0).color(r, g, b, a).next();
        buffer.vertex(model, x + width, y, 0).color(r, g, b, a).next();
        buffer.vertex(model, x, y, 0).color(r, g, b, a).next();
        tessellator.draw();
    }
}
