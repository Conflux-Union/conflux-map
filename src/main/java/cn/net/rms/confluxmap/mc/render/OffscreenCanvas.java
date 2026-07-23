package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.util.Window;

//#if MC>=12100
//$$ import com.mojang.blaze3d.systems.VertexSorter;
//$$ import org.joml.Matrix4f;
//#else
import net.minecraft.util.math.Matrix4f;
//#endif

/**
 * Off-screen RGBA render target for HUD elements that need real geometric
 * clipping (the circular minimap). Content is drawn into the canvas in canvas
 * pixel units, then sampled back as a texture by arbitrarily-shaped geometry -
 * unlike destination-alpha masking this works regardless of the main
 * framebuffer's alpha/depth state. Render thread only.
 *
 * <p>The global model-view stack is left untouched: during HUD rendering it
 * holds vanilla's plain {@code (0,0,-2000)} translate, which is equally valid
 * inside the canvas's 1000..3000 ortho depth range.
 */
public final class OffscreenCanvas {
    private Framebuffer framebuffer;

    /** Bind + clear to transparent; sets an ortho projection in canvas pixel units. */
    public void begin(final int sizePx) {
        if (framebuffer == null || framebuffer.textureWidth != sizePx) {
            close();
            framebuffer = new SimpleFramebuffer(sizePx, sizePx, false, MinecraftClient.IS_SYSTEM_MAC);
        }
        framebuffer.setClearColor(0f, 0f, 0f, 0f);
        framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
        framebuffer.beginWrite(true);
        setProjection(ortho(0f, sizePx, 0f, sizePx));
    }

    /**
     * Orthographic projection over the canvas' depth range. 1.19.4 swapped Minecraft's matrix
     * type for JOML's; the argument order (left, right, bottom, top, near, far) is the same in
     * both, so only the constructing call differs.
     */
    private static Matrix4f ortho(final float left, final float right, final float bottom, final float top) {
        //#if MC>=12100
        //$$ return new Matrix4f().setOrtho(left, right, bottom, top, 1000f, 3000f);
        //#else
        return Matrix4f.projectionMatrix(left, right, bottom, top, 1000f, 3000f);
        //#endif
    }

    /** 1.20 made the projection carry an explicit vertex sort order; flat GUI geometry sorts by Z. */
    private static void setProjection(final Matrix4f projection) {
        //#if MC>=12100
        //$$ RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);
        //#else
        RenderSystem.setProjectionMatrix(projection);
        //#endif
    }

    /** Unbind; restores the main framebuffer and vanilla's GUI projection. */
    public void end(final MinecraftClient client) {
        framebuffer.endWrite();
        client.getFramebuffer().beginWrite(true);
        final Window window = client.getWindow();
        setProjection(ortho(
            0f, (float) (window.getFramebufferWidth() / window.getScaleFactor()),
            0f, (float) (window.getFramebufferHeight() / window.getScaleFactor())
        ));
    }

    /** GL texture id of the canvas contents; row 0 is the BOTTOM (flip V when sampling). */
    public int textureId() {
        return framebuffer.getColorAttachment();
    }

    public void close() {
        if (framebuffer != null) {
            framebuffer.delete();
            framebuffer = null;
        }
    }
}
