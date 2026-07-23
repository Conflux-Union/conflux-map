package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.Window;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;
//#if MC>=11900
//$$ import org.joml.Quaternionf;
//#else
import net.minecraft.util.math.Vec3f;
//#endif

/**
 * Core-shader helpers for drawing dynamically-generated textures (map tiles) as flat GUI quads.
 * Render thread only; every call here assumes a current GL context.
 *
 * <p>The version differences live in {@link Mesh} (batch setup/teardown) and in the extra
 * mappings for the {@code GameRenderer} shader accessors, so the geometry below is one shared
 * copy across every supported Minecraft version.
 */
public final class RenderUtil {
    private RenderUtil() {
    }

    /** Selects the flat position+texture shader and standard alpha blending, for textured GUI quads. */
    public static void beginTexturedQuads() {
        useTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
    }

    public static void bindTexture(final int glId) {
        RenderSystem.setShaderTexture(0, glId);
    }

    public static void rotateZ(final MatrixStack matrices, final float degrees) {
        //#if MC>=11900
        //$$ matrices.multiply(new Quaternionf().rotationZ((float) Math.toRadians(degrees)));
        //#else
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(degrees));
        //#endif
    }

    /**
     * Binds an already-vanilla-managed texture (player skin, mob texture, etc.) by identifier.
     *
     * <p>Core shaders sample what {@code RenderSystem.setShaderTexture} points at, not the
     * legacy {@code TextureManager} bind - using the latter leaves unit 0 on whatever was
     * drawn last (map tiles), which is exactly the "icons show dark terrain" bug.
     */
    public static void bindTexture(final MinecraftClient client, final Identifier id) {
        RenderSystem.setShaderTexture(0, id);
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
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_TEXTURE);
        mesh.vertex(model, x, y + height, 0).texture(u0, v1).next();
        mesh.vertex(model, x + width, y + height, 0).texture(u1, v1).next();
        mesh.vertex(model, x + width, y, 0).texture(u1, v0).next();
        mesh.vertex(model, x, y, 0).texture(u0, v0).next();
        mesh.draw();
    }

    /**
     * Self-contained (sets its own shader/blend, unlike {@link #drawQuad} which expects
     * {@link #beginTexturedQuads()} to have been called by a tile-drawing loop) single-texture
     * quad with a per-vertex ARGB tint, multiplied over the sampled texture color. Used for radar
     * entity icons: alpha carries the above/below elevation fade, RGB carries the brightness dim,
     * matching the plain {@link #fillRect}/{@link #fillTriangle}/{@link #drawRing} markers'
     * per-call convention.
     */
    public static void drawTintedQuad(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int argbColor
    ) {
        useTintedTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.QUADS, Mesh.tintedTextureFormat());
        mesh.tintedVertex(model, x, y + height, 0, u0, v1, r, g, b, a);
        mesh.tintedVertex(model, x + width, y + height, 0, u1, v1, r, g, b, a);
        mesh.tintedVertex(model, x + width, y, 0, u1, v0, r, g, b, a);
        mesh.tintedVertex(model, x, y, 0, u0, v0, r, g, b, a);
        mesh.draw();
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
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, 0).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        mesh.draw();
    }

    /**
     * Textured disk sampling an {@link OffscreenCanvas}: rim UVs walk the unit circle
     * around (0.5, 0.5), V flipped because FBO row 0 is the bottom. The currently bound
     * texture must be the canvas contents; call between {@link #beginTexturedQuads()} and
     * a bound texture.
     */
    public static void drawTexturedDisk(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float radius
    ) {
        RenderSystem.disableCull();
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.TRIANGLE_FAN, VertexFormats.POSITION_TEXTURE);
        mesh.vertex(model, centerX, centerY, 0).texture(0.5f, 0.5f).next();
        final int segments = 48;
        for (int i = 0; i <= segments; i++) {
            final double angle = 2.0 * Math.PI * i / segments;
            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            mesh.vertex(model, centerX + cos * radius, centerY + sin * radius, 0)
                .texture(0.5f + 0.5f * cos, 0.5f - 0.5f * sin).next();
        }
        mesh.draw();
        RenderSystem.enableCull();
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
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        final int segments = 48;
        final float inner = outerRadius - thickness;
        for (int i = 0; i <= segments; i++) {
            final double angle = 2.0 * Math.PI * i / segments;
            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            mesh.vertex(model, centerX + cos * outerRadius, centerY + sin * outerRadius, 0).color(r, g, b, a).next();
            mesh.vertex(model, centerX + cos * inner, centerY + sin * inner, 0).color(r, g, b, a).next();
        }
        mesh.draw();
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
        useColorShader();
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
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.TRIANGLES, VertexFormats.POSITION_COLOR);
        mesh.vertex(model, x0, y0, z0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, z1).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, z2).color(r, g, b, a).next();
        mesh.draw();
    }

    /** Flat-colored axis-aligned quad (background/border), independent of any bound texture. */
    public static void fillRect(final MatrixStack matrices, final float x, final float y, final float width, final float height, final int argbColor) {
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);
        mesh.vertex(model, x, y + height, 0).color(r, g, b, a).next();
        mesh.vertex(model, x + width, y + height, 0).color(r, g, b, a).next();
        mesh.vertex(model, x + width, y, 0).color(r, g, b, a).next();
        mesh.vertex(model, x, y, 0).color(r, g, b, a).next();
        mesh.draw();
    }

    /*
     * 1.20 renamed the core shader accessors and changed their return type (Shader ->
     * ShaderProgram), so these three cannot be expressed as extra mappings and fork here instead.
     */

    private static void useTextureShader() {
        //#if MC>=12100
        //$$ RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        //#else
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        //#endif
    }

    private static void useColorShader() {
        //#if MC>=12100
        //$$ RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        //#else
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        //#endif
    }

    private static void useTintedTextureShader() {
        //#if MC>=12100
        //$$ RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        //#else
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        //#endif
    }
}
