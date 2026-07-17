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
