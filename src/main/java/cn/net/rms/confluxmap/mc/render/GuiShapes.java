package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.core.util.Argb;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.List;
import java.util.Optional;
//#if MC>=260100
//$$ import com.mojang.blaze3d.pipeline.ColorTargetState;
//#endif
//#if MC>=12105
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//#if MC>=260100 && MC<260200
//$$ import com.mojang.blaze3d.shaders.UniformType;
//#elseif MC<260100
//$$ import net.minecraft.client.gl.UniformType;
//#endif
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.gl.ShaderProgramKeys;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
//#if MC<12104
import org.lwjgl.opengl.GL11;
//#endif
//#if MC>=11904
//$$ import org.joml.Matrix4f;
//#else
import net.minecraft.util.math.Matrix4f;
//#endif
//#if MC>=11900
//$$ import org.joml.Quaternionf;
//#else
import net.minecraft.util.math.Vec3f;
//#endif

/**
 * Flat GUI and world-space shape emission shared by the screens, HUD, radar, and world
 * overlay passes. Render thread only; every call here assumes a current GL context. The
 * version differences live in {@link Mesh} (batch setup/teardown) and in the extra
 * mappings for the {@code GameRenderer} shader accessors, so the geometry is one shared
 * copy across every supported Minecraft version.
 */
public final class GuiShapes {
    //#if MC>=12105
    //$$ private static final RenderPipeline GUI_PRESERVE_DESTINATION_ALPHA =
    //$$     createGuiPreserveDestinationAlphaPipeline();
    //$$ private static final RenderPipeline GUI_REPLACE = createGuiReplacePipeline();
    //#endif

    //#if MC<12104
    public static void clearGuiDepth() {
    //#if MC>=12103
    //$$     RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT);
    //#else
        RenderSystem.clear(GL11.GL_DEPTH_BUFFER_BIT, MinecraftClient.IS_SYSTEM_MAC);
    //#endif
    }
    //#endif

    public static void rotateZ(final MatrixStack matrices, final float degrees) {
        //#if MC>=260300
        //$$ // 26.3 dropped mulPose(Quaternionf); rotate through a matrix instead.
        //$$ matrices.mulPose(new org.joml.Matrix4f().rotation(
        //$$     new Quaternionf().rotationZ((float) Math.toRadians(degrees))));
        //#elseif MC>=11900
        //$$ matrices.multiply(new Quaternionf().rotationZ((float) Math.toRadians(degrees)));
        //#else
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(degrees));
        //#endif
    }

    /**
     * Draws one axis-aligned textured quad in GUI space. Must be called between
     * {@link TextureBinds#beginTexturedQuads()} and a bound texture ({@link TextureBinds#bindTexture(int)}).
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
        //#if MC>=12105
        //$$ final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        //$$ mesh.tintedVertex(model, x, y + height, 0, u0, v1, 1f, 1f, 1f, 1f);
        //$$ mesh.tintedVertex(model, x + width, y + height, 0, u1, v1, 1f, 1f, 1f, 1f);
        //$$ mesh.tintedVertex(model, x + width, y, 0, u1, v0, 1f, 1f, 1f, 1f);
        //$$ mesh.tintedVertex(model, x, y, 0, u0, v0, 1f, 1f, 1f, 1f);
        //$$ mesh.drawGui(RenderPipelines.GUI_TEXTURED);
        //#else
        final Mesh mesh = Mesh.begin(Mesh.Mode.QUADS, VertexFormats.POSITION_TEXTURE);
        mesh.vertex(model, x, y + height, 0).texture(u0, v1).next();
        mesh.vertex(model, x + width, y + height, 0).texture(u1, v1).next();
        mesh.vertex(model, x + width, y, 0).texture(u1, v0).next();
        mesh.vertex(model, x, y, 0).texture(u0, v0).next();
        mesh.draw();
        //#endif
    }

    /**
     * Self-contained (sets its own shader/blend, unlike {@link #drawQuad} which expects
     * {@link TextureBinds#beginTexturedQuads()} to have been called by a tile-drawing loop) single-texture
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
        //#if MC<12105
        useTintedTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        mesh.tintedVertex(model, x, y + height, 0, u0, v1, r, g, b, a);
        mesh.tintedVertex(model, x + width, y + height, 0, u1, v1, r, g, b, a);
        mesh.tintedVertex(model, x + width, y, 0, u1, v0, r, g, b, a);
        mesh.tintedVertex(model, x, y, 0, u0, v0, r, g, b, a);
        //#if MC>=12105
        //$$ mesh.drawGui(RenderPipelines.GUI_TEXTURED);
        //#else
        mesh.draw();
        //#endif
    }

    /**
     * Draws a dark texture-alpha silhouette expanded by {@code radius} GUI pixels. The textured
     * shader multiplies sampled RGB rather than replacing it, so this helper deliberately emits
     * black vertices and accepts opacity only. All shifted copies share one mesh submission;
     * drawing the original texture afterward covers the dark interior, leaving only the contour
     * around non-transparent pixels.
     */
    public static void drawDarkTextureOutline(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int radius,
        final float opacity
    ) {
        if (radius <= 0) {
            return;
        }
        //#if MC<12105
        useTintedTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final int diameter = radius * 2 + 1;
        final int copies = diameter * diameter - 1;
        // All shifted silhouettes can cover the same target pixel. Give each draw only the
        // per-layer alpha that converges on the requested fade after source-over accumulation.
        final float a = Argb.alphaForRepeatedOverdraw(opacity, copies);
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        for (int offsetY = -radius; offsetY <= radius; offsetY++) {
            for (int offsetX = -radius; offsetX <= radius; offsetX++) {
                if (offsetX == 0 && offsetY == 0) {
                    continue;
                }
                final float left = x + offsetX;
                final float top = y + offsetY;
                mesh.tintedVertex(model, left, top + height, 0, u0, v1, 0f, 0f, 0f, a);
                mesh.tintedVertex(model, left + width, top + height, 0, u1, v1, 0f, 0f, 0f, a);
                mesh.tintedVertex(model, left + width, top, 0, u1, v0, 0f, 0f, 0f, a);
                mesh.tintedVertex(model, left, top, 0, u0, v0, 0f, 0f, 0f, a);
            }
        }
        //#if MC>=12105
        //$$ mesh.drawGui(RenderPipelines.GUI_TEXTURED);
        //#else
        mesh.draw();
        //#endif
    }

    /** Maps one horizontal texture strip clockwise around a circular frame. */
    public static void drawTexturedRing(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float outerRadius,
        final float thickness,
        final float u0,
        final float v0,
        final float u1,
        final float v1,
        final int argbColor
    ) {
        //#if MC<12105
        useTintedTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final int segments = Math.max(32, (int) Math.ceil(outerRadius * Math.PI / 2));
        final float innerRadius = Math.max(0f, outerRadius - thickness);
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        for (int i = 0; i < segments; i++) {
            final float p0 = i / (float) segments;
            final float p1 = (i + 1) / (float) segments;
            final double angle0 = -Math.PI / 2.0 + p0 * Math.PI * 2.0;
            final double angle1 = -Math.PI / 2.0 + p1 * Math.PI * 2.0;
            final float sin0 = (float) Math.sin(angle0);
            final float cos0 = (float) Math.cos(angle0);
            final float sin1 = (float) Math.sin(angle1);
            final float cos1 = (float) Math.cos(angle1);
            final float texU0 = u0 + (u1 - u0) * p0;
            final float texU1 = u0 + (u1 - u0) * p1;

            texturedVertex(
                mesh, model,
                centerX + cos0 * innerRadius, centerY + sin0 * innerRadius,
                texU0, v1, r, g, b, a
            );
            texturedVertex(
                mesh, model,
                centerX + cos1 * innerRadius, centerY + sin1 * innerRadius,
                texU1, v1, r, g, b, a
            );
            texturedVertex(
                mesh, model,
                centerX + cos1 * outerRadius, centerY + sin1 * outerRadius,
                texU1, v0, r, g, b, a
            );
            texturedVertex(
                mesh, model,
                centerX + cos0 * outerRadius, centerY + sin0 * outerRadius,
                texU0, v0, r, g, b, a
            );
        }
        drawGuiTexturedMesh(mesh);
    }

    private static void texturedVertex(
        final Mesh mesh,
        //#if MC>=11904
        //$$ final org.joml.Matrix4f model,
        //#else
        final Matrix4f model,
        //#endif
        final float x,
        final float y,
        final float u,
        final float v,
        final float r,
        final float g,
        final float b,
        final float a
    ) {
        mesh.tintedVertex(model, x, y, 0, u, v, r, g, b, a);
    }

    private static void drawGuiTexturedMesh(final Mesh mesh) {
        //#if MC>=12105
        //$$ mesh.drawGui(RenderPipelines.GUI_TEXTURED);
        //#else
        mesh.draw();
        //#endif
    }

    /**
     * Draws already-projected textured model quads into the current target. The array uses
     * {@code x,y,z,u,v} per vertex and must contain complete groups of four vertices.
     */
    public static void drawProjectedTexturedQuads(
        final MatrixStack matrices,
        final float[] vertices,
        final int argbColor
    ) {
        if (vertices.length == 0) {
            return;
        }
        if (vertices.length % 20 != 0) {
            throw new IllegalArgumentException("projected textured quads require 20 floats per quad");
        }
        //#if MC<12105
        useTintedTextureShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        for (int i = 0; i < vertices.length; i += 5) {
            mesh.tintedVertex(
                model, vertices[i], vertices[i + 1], vertices[i + 2],
                vertices[i + 3], vertices[i + 4], r, g, b, a
            );
        }
        //#if MC>=12105
        //$$ mesh.drawGui(RenderPipelines.GUI_TEXTURED);
        //#else
        mesh.draw();
        //#endif
    }

    /** Replaces one rectangle in the current render target with fully transparent pixels. */
    public static void clearTargetRect(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height
    ) {
        //#if MC<12105
        useColorShader();
        RenderSystem.disableBlend();
        //#endif
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR);
        appendRect(mesh, model, x, y, width, height, 0);
        //#if MC>=12105
        //$$ mesh.drawGui(GUI_REPLACE);
        //#else
        mesh.draw();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
    }

    /** Flat-colored filled triangle in GUI space (player arrow etc.). */
    public static void fillTriangle(
        final MatrixStack matrices,
        final float x0, final float y0,
        final float x1, final float y1,
        final float x2, final float y2,
        final int argbColor
    ) {
        //#if MC<12105
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        //#if MC>=12105
        //$$ final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR);
        //#else
        final Mesh mesh = Mesh.begin(Mesh.Mode.TRIANGLES, VertexFormats.POSITION_COLOR);
        //#endif
        mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, 0).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        //#if MC>=12105
        //$$ mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        //$$ // Callers pass either winding (compare the two halves of a StructureMarkerRenderer
        //$$ // diamond), and 1.21.5 pipelines cull back faces. Emitting both windings of a flat
        //$$ // triangle costs nothing at raster time: exactly one of them survives the cull.
        //$$ mesh.vertex(model, x2, y2, 0).color(r, g, b, a).next();
        //$$ mesh.vertex(model, x1, y1, 0).color(r, g, b, a).next();
        //$$ mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        //$$ mesh.vertex(model, x0, y0, 0).color(r, g, b, a).next();
        //$$ mesh.drawGui(RenderPipelines.GUI);
        //#else
        mesh.draw();
        //#endif
    }

    /**
     * Small pixel-style diamond with a top-left highlight, lower-right shading, and a restrained
     * drop shadow. Every layer is appended to one mesh so dense radar views still issue one draw.
     */
    public static void fillBeveledDiamond(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float radius,
        final int argbColor
    ) {
        //#if MC<12105
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR);
        final int dropShadow = Argb.scaleAlpha(
            0xA0000000, Argb.alpha(argbColor) / 255f
        );
        appendDiamond(
            mesh, model, centerX + 0.5f, centerY + 0.5f,
            Math.max(0f, radius - 0.25f), dropShadow
        );
        final int topLeft = Argb.blendOver(argbColor, 0x70FFFFFF);
        final int topRight = Argb.blendOver(argbColor, 0x28FFFFFF);
        final int bottomRight = Argb.scale(argbColor, 0.5f);
        final int bottomLeft = Argb.scale(argbColor, 0.72f);
        appendTriangleQuad(
            mesh, model,
            centerX, centerY - radius,
            centerX - radius, centerY,
            centerX, centerY,
            topLeft
        );
        appendTriangleQuad(
            mesh, model,
            centerX, centerY - radius,
            centerX, centerY,
            centerX + radius, centerY,
            topRight
        );
        appendTriangleQuad(
            mesh, model,
            centerX, centerY + radius,
            centerX + radius, centerY,
            centerX, centerY,
            bottomRight
        );
        appendTriangleQuad(
            mesh, model,
            centerX, centerY + radius,
            centerX, centerY,
            centerX - radius, centerY,
            bottomLeft
        );
        //#if MC>=12105
        //$$ mesh.drawGui(RenderPipelines.GUI);
        //#else
        mesh.draw();
        //#endif
    }

    private static void appendDiamond(
        final Mesh mesh,
        final Matrix4f model,
        final float centerX,
        final float centerY,
        final float radius,
        final int argbColor
    ) {
        appendColoredVertex(mesh, model, centerX, centerY + radius, argbColor);
        appendColoredVertex(mesh, model, centerX + radius, centerY, argbColor);
        appendColoredVertex(mesh, model, centerX, centerY - radius, argbColor);
        appendColoredVertex(mesh, model, centerX - radius, centerY, argbColor);
    }

    private static void appendTriangleQuad(
        final Mesh mesh,
        final Matrix4f model,
        final float x0,
        final float y0,
        final float x1,
        final float y1,
        final float x2,
        final float y2,
        final int argbColor
    ) {
        appendColoredVertex(mesh, model, x0, y0, argbColor);
        appendColoredVertex(mesh, model, x1, y1, argbColor);
        appendColoredVertex(mesh, model, x2, y2, argbColor);
        appendColoredVertex(mesh, model, x2, y2, argbColor);
    }

    private static void appendColoredVertex(
        final Mesh mesh,
        final Matrix4f model,
        final float x,
        final float y,
        final int argbColor
    ) {
        mesh.vertex(model, x, y, 0).color(
            Argb.red(argbColor) / 255f,
            Argb.green(argbColor) / 255f,
            Argb.blue(argbColor) / 255f,
            Argb.alpha(argbColor) / 255f
        ).next();
    }

    /**
     * Textured disk sampling an {@link OffscreenCanvas}: rim UVs walk the unit circle
     * around (0.5, 0.5), V flipped because FBO row 0 is the bottom. The currently bound
     * texture must be the canvas contents; call between {@link TextureBinds#beginTexturedQuads()} and
     * a bound texture.
     */
    public static void drawTexturedDisk(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float radius
    ) {
        final var model = matrices.peek().getModel();
        //#if MC>=12105
        //$$ final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, Mesh.tintedTextureFormat());
        //$$ final int segments = 48;
        //$$ for (int i = 0; i < segments; i++) {
        //$$     final double angle0 = 2.0 * Math.PI * i / segments;
        //$$     final double angle1 = 2.0 * Math.PI * (i + 1) / segments;
        //$$     final float cos0 = (float) Math.cos(angle0);
        //$$     final float sin0 = (float) Math.sin(angle0);
        //$$     final float cos1 = (float) Math.cos(angle1);
        //$$     final float sin1 = (float) Math.sin(angle1);
        //$$     // Wound backwards through the segment so the fan matches the front-facing
        //$$     // order of fillRect; the legacy path called disableCull here instead.
        //$$     mesh.tintedVertex(model, centerX, centerY, 0, 0.5f, 0.5f, 1f, 1f, 1f, 1f);
        //$$     mesh.tintedVertex(
        //$$         model, centerX + cos1 * radius, centerY + sin1 * radius, 0,
        //$$         0.5f + 0.5f * cos1, 0.5f - 0.5f * sin1, 1f, 1f, 1f, 1f
        //$$     );
        //$$     mesh.tintedVertex(
        //$$         model, centerX + cos0 * radius, centerY + sin0 * radius, 0,
        //$$         0.5f + 0.5f * cos0, 0.5f - 0.5f * sin0, 1f, 1f, 1f, 1f
        //$$     );
        //$$     mesh.tintedVertex(model, centerX, centerY, 0, 0.5f, 0.5f, 1f, 1f, 1f, 1f);
        //$$ }
        //$$ mesh.drawGui(RenderPipelines.GUI_TEXTURED);
        //#else
        RenderSystem.disableCull();
        final Mesh mesh = Mesh.begin(Mesh.Mode.TRIANGLE_FAN, VertexFormats.POSITION_TEXTURE);
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
        //#endif
    }

    /** Ring outline (circle border), as a triangle strip pre-1.21.5 and segment quads after. */
    public static void drawRing(
        final MatrixStack matrices,
        final float centerX,
        final float centerY,
        final float outerRadius,
        final float thickness,
        final int argbColor
    ) {
        //#if MC<12105
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        final var model = matrices.peek().getModel();
        //#if MC>=12105
        //$$ final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR);
        //#else
        final Mesh mesh = Mesh.begin(Mesh.Mode.TRIANGLE_STRIP, VertexFormats.POSITION_COLOR);
        //#endif
        final int segments = 48;
        final float inner = outerRadius - thickness;
        //#if MC>=12105
        //$$ for (int i = 0; i < segments; i++) {
        //$$     final double angle0 = 2.0 * Math.PI * i / segments;
        //$$     final double angle1 = 2.0 * Math.PI * (i + 1) / segments;
        //$$     final float cos0 = (float) Math.cos(angle0);
        //$$     final float sin0 = (float) Math.sin(angle0);
        //$$     final float cos1 = (float) Math.cos(angle1);
        //$$     final float sin1 = (float) Math.sin(angle1);
        //$$     // Inner edge first, so each segment quad winds like fillRect and survives the
        //$$     // back-face cull that 1.21.5 pipelines apply to GUI geometry.
        //$$     mesh.vertex(model, centerX + cos0 * inner, centerY + sin0 * inner, 0).color(r, g, b, a).next();
        //$$     mesh.vertex(model, centerX + cos1 * inner, centerY + sin1 * inner, 0).color(r, g, b, a).next();
        //$$     mesh.vertex(model, centerX + cos1 * outerRadius, centerY + sin1 * outerRadius, 0).color(r, g, b, a).next();
        //$$     mesh.vertex(model, centerX + cos0 * outerRadius, centerY + sin0 * outerRadius, 0).color(r, g, b, a).next();
        //$$ }
        //$$ mesh.drawGui(RenderPipelines.GUI);
        //#else
        for (int i = 0; i <= segments; i++) {
            final double angle = 2.0 * Math.PI * i / segments;
            final float cos = (float) Math.cos(angle);
            final float sin = (float) Math.sin(angle);
            mesh.vertex(model, centerX + cos * outerRadius, centerY + sin * outerRadius, 0).color(r, g, b, a).next();
            mesh.vertex(model, centerX + cos * inner, centerY + sin * inner, 0).color(r, g, b, a).next();
        }
        mesh.draw();
        //#endif
    }

    /** Flat-colored axis-aligned quad (background/border), independent of any bound texture. */
    public static void fillRect(final MatrixStack matrices, final float x, final float y, final float width, final float height, final int argbColor) {
        fillRect(matrices, x, y, width, height, argbColor, true);
    }

    /** Draws many flat GUI rectangles in one batch. */
    public static void fillRects(final MatrixStack matrices, final List<ColoredRect> rects) {
        fillRects(matrices, rects, false);
    }

    /** Draws translucent GUI rectangles without replacing the target's existing alpha. */
    public static void fillRectsPreservingDestinationAlpha(
        final MatrixStack matrices,
        final List<ColoredRect> rects
    ) {
        fillRects(matrices, rects, true);
    }

    private static void fillRects(
        final MatrixStack matrices,
        final List<ColoredRect> rects,
        final boolean preserveDestinationAlpha
    ) {
        if (rects.isEmpty()) {
            return;
        }
        //#if MC<12105
        useColorShader();
        RenderSystem.enableBlend();
        if (preserveDestinationAlpha) {
            RenderSystem.blendFuncSeparate(
                com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE_MINUS_SRC_ALPHA,
                com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ZERO,
                com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE
            );
        } else {
            RenderSystem.defaultBlendFunc();
        }
        //#endif
        final var model = matrices.peek().getModel();
        final Mesh mesh = Mesh.beginGui(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR);
        for (final ColoredRect rect : rects) {
            appendRect(mesh, model, rect.x(), rect.y(), rect.width(), rect.height(), rect.argbColor());
        }
        //#if MC>=12105
        //$$ mesh.drawGui(
        //$$     preserveDestinationAlpha ? GUI_PRESERVE_DESTINATION_ALPHA : RenderPipelines.GUI
        //$$ );
        //#else
        mesh.draw();
        if (preserveDestinationAlpha) {
            RenderSystem.defaultBlendFunc();
        }
        //#endif
    }

    //#if MC>=12105
    //$$ private static RenderPipeline createGuiPreserveDestinationAlphaPipeline() {
    //$$     final RenderPipeline gui = RenderPipelines.GUI;
    //#if MC>=260200
    //$$     final RenderPipeline.Builder builder = RenderPipeline.builder()
    //$$         .withLocation("pipeline/confluxmap_gui_preserve_destination_alpha")
    //#if MC>=260300
    //$$         .withVertexShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
    //$$         .withFragmentShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
    //#else
    //$$         .withVertexShader(gui.getVertexShader())
    //$$         .withFragmentShader(gui.getFragmentShader())
    //#endif
    //$$         .withCull(gui.isCull())
    //$$         .withColorTargetState(new ColorTargetState(
    //#if MC>=260300
    //$$             // 26.3 dropped the singular accessor; the list holds one entry for GUI.
    //$$             gui.getColorTargetStates().get(0).blendFunction(),
    //$$             gui.getColorTargetStates().get(0).format(),
    //#else
    //$$             gui.getColorTargetState().blendFunction(),
    //$$             gui.getColorTargetState().format(),
    //#endif
    //$$             ColorTargetState.WRITE_COLOR
    //$$         ))
    //$$         .withVertexBinding(0, gui.getVertexFormatBinding(0))
    //$$         .withPrimitiveTopology(gui.getPrimitiveTopology());
    //$$     for (final var bindGroupLayout : gui.getBindGroupLayouts()) {
    //$$         builder.withBindGroupLayout(bindGroupLayout);
    //$$     }
    //#else
    //$$     final RenderPipeline.Builder builder = RenderPipeline.builder()
    //$$         .withLocation("pipeline/confluxmap_gui_preserve_destination_alpha")
    //#if MC>=260300
    //$$         .withVertexShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
    //$$         .withFragmentShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
    //#else
    //$$         .withVertexShader(gui.getVertexShader())
    //$$         .withFragmentShader(gui.getFragmentShader())
    //#endif
    //$$         .withVertexFormat(gui.getVertexFormat(), gui.getVertexFormatMode());
    //#if MC>=260100
    //$$     builder
    //$$         .withCull(gui.isCull())
    //$$         .withColorTargetState(new ColorTargetState(
    //$$             gui.getColorTargetState().blendFunction(),
    //$$             ColorTargetState.WRITE_COLOR
    //$$         ));
    //#else
    //$$     builder
    //$$         .withBlend(gui.getBlendFunction().orElseThrow())
    //$$         .withColorWrite(true, false);
    //#endif
    //#if MC>=12108
    //$$     builder
    //$$         .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
    //$$         .withUniform("Projection", UniformType.UNIFORM_BUFFER);
    //#else
    //$$     builder
    //$$         .withUniform("ModelViewMat", UniformType.MATRIX4X4)
    //$$         .withUniform("ProjMat", UniformType.MATRIX4X4)
    //$$         .withUniform("ColorModulator", UniformType.VEC4);
    //#endif
    //#endif
    //$$     return builder.build();
    //$$ }
    //$$
    //$$ private static RenderPipeline createGuiReplacePipeline() {
    //$$     final RenderPipeline gui = RenderPipelines.GUI;
    //#if MC>=260200
    //$$     final RenderPipeline.Builder builder = RenderPipeline.builder()
    //$$         .withLocation("pipeline/confluxmap_gui_replace")
    //#if MC>=260300
    //$$         .withVertexShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
    //$$         .withFragmentShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
    //#else
    //$$         .withVertexShader(gui.getVertexShader())
    //$$         .withFragmentShader(gui.getFragmentShader())
    //#endif
    //$$         .withCull(gui.isCull())
    //$$         .withColorTargetState(new ColorTargetState(
    //#if MC>=260300
    //$$             Optional.empty(), gui.getColorTargetStates().get(0).format(), ColorTargetState.WRITE_ALL
    //#else
    //$$             Optional.empty(), gui.getColorTargetState().format(), ColorTargetState.WRITE_ALL
    //#endif
    //$$         ))
    //$$         .withVertexBinding(0, gui.getVertexFormatBinding(0))
    //$$         .withPrimitiveTopology(gui.getPrimitiveTopology());
    //$$     for (final var bindGroupLayout : gui.getBindGroupLayouts()) {
    //$$         builder.withBindGroupLayout(bindGroupLayout);
    //$$     }
    //#else
    //$$     final RenderPipeline.Builder builder = RenderPipeline.builder()
    //$$         .withLocation("pipeline/confluxmap_gui_replace")
    //#if MC>=260300
    //$$         .withVertexShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.VERTEX))
    //$$         .withFragmentShader(gui.getShaders().get(com.mojang.renderpearl.api.pipeline.ShaderType.FRAGMENT))
    //#else
    //$$         .withVertexShader(gui.getVertexShader())
    //$$         .withFragmentShader(gui.getFragmentShader())
    //#endif
    //$$         .withVertexFormat(gui.getVertexFormat(), gui.getVertexFormatMode());
    //#if MC>=260100
    //$$     builder
    //$$         .withCull(gui.isCull())
    //$$         .withColorTargetState(new ColorTargetState(Optional.empty(), ColorTargetState.WRITE_ALL));
    //#else
    //$$     builder
    //$$         .withoutBlend()
    //$$         .withColorWrite(true, true);
    //#endif
    //#if MC>=12108
    //$$     builder
    //$$         .withUniform("DynamicTransforms", UniformType.UNIFORM_BUFFER)
    //$$         .withUniform("Projection", UniformType.UNIFORM_BUFFER);
    //#else
    //$$     builder
    //$$         .withUniform("ModelViewMat", UniformType.MATRIX4X4)
    //$$         .withUniform("ProjMat", UniformType.MATRIX4X4)
    //$$         .withUniform("ColorModulator", UniformType.VEC4);
    //#endif
    //#endif
    //$$     return builder.build();
    //$$ }
    //#endif

    /**
     * The same quad as {@link #fillRect}, drawn as world geometry from a {@code WorldRenderEvents}
     * callback (waypoint label plates) rather than as part of the GUI. The distinction only
     * matters from 1.21.6, where GUI drawing is recorded for a later pass and world drawing is not
     * - see {@link Mesh#beginGui}.
     */
    public static void fillRect3D(final MatrixStack matrices, final float x, final float y, final float width, final float height, final int argbColor) {
        fillRect(matrices, x, y, width, height, argbColor, false);
    }

    private static void fillRect(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float width,
        final float height,
        final int argbColor,
        final boolean gui
    ) {
        //#if MC<12105
        useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        //#endif
        final var model = matrices.peek().getModel();
        final Mesh mesh = gui
            ? Mesh.beginGui(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR)
            : Mesh.begin(Mesh.Mode.QUADS, VertexFormats.POSITION_COLOR);
        appendRect(mesh, model, x, y, width, height, argbColor);
        //#if MC>=12105
        //$$ if (gui) {
        //$$     mesh.drawGui(RenderPipelines.GUI);
        //$$ } else {
        //$$     mesh.draw(RenderPipelines.GUI);
        //$$ }
        //#else
        mesh.draw();
        //#endif
    }

    private static void appendRect(
        final Mesh mesh,
        final Matrix4f model,
        final float x,
        final float y,
        final float width,
        final float height,
        final int argbColor
    ) {
        final float a = Argb.alpha(argbColor) / 255f;
        final float r = Argb.red(argbColor) / 255f;
        final float g = Argb.green(argbColor) / 255f;
        final float b = Argb.blue(argbColor) / 255f;
        mesh.vertex(model, x, y + height, 0).color(r, g, b, a).next();
        mesh.vertex(model, x + width, y + height, 0).color(r, g, b, a).next();
        mesh.vertex(model, x + width, y, 0).color(r, g, b, a).next();
        mesh.vertex(model, x, y, 0).color(r, g, b, a).next();
    }

    public record ColoredRect(float x, float y, float width, float height, int argbColor) {
    }

    /*
     * 1.20 renamed the core shader accessors and changed their return type (Shader ->
     * ShaderProgram), so these three cannot be expressed as extra mappings and fork here instead.
     */

    public static void useTextureShader() {
        //#if MC>=12105
        //$$ // Pipeline selection happens at draw time.
        //#elseif MC>=12103
        //$$ RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX);
        //#elseif MC>=12000
        //$$ RenderSystem.setShader(GameRenderer::getPositionTexProgram);
        //#else
        RenderSystem.setShader(GameRenderer::getPositionTexShader);
        //#endif
    }

    public static void useColorShader() {
        //#if MC>=12105
        //$$ // Pipeline selection happens at draw time.
        //#elseif MC>=12103
        //$$ RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        //#elseif MC>=12000
        //$$ RenderSystem.setShader(GameRenderer::getPositionColorProgram);
        //#else
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        //#endif
    }

    private static void useTintedTextureShader() {
        //#if MC>=12105
        //$$ // Pipeline selection happens at draw time.
        //#elseif MC>=12103
        //$$ RenderSystem.setShader(ShaderProgramKeys.POSITION_TEX_COLOR);
        //#elseif MC>=12100
        //$$ RenderSystem.setShader(GameRenderer::getPositionTexColorProgram);
        //#elseif MC>=12000
        //$$ RenderSystem.setShader(GameRenderer::getPositionColorTexProgram);
        //#else
        RenderSystem.setShader(GameRenderer::getPositionColorTexShader);
        //#endif
    }
}
