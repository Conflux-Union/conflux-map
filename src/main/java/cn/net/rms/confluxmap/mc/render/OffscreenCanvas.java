package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.util.Window;
import net.minecraft.util.math.Matrix4f;

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
        RenderSystem.setProjectionMatrix(Matrix4f.projectionMatrix(0f, sizePx, 0f, sizePx, 1000f, 3000f));
    }

    /** Unbind; restores the main framebuffer and vanilla's GUI projection. */
    public void end(final MinecraftClient client) {
        framebuffer.endWrite();
        client.getFramebuffer().beginWrite(true);
        final Window window = client.getWindow();
        RenderSystem.setProjectionMatrix(Matrix4f.projectionMatrix(
            0f, (float) (window.getFramebufferWidth() / window.getScaleFactor()),
            0f, (float) (window.getFramebufferHeight() / window.getScaleFactor()),
            1000f, 3000f
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
