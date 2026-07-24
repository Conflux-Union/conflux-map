package cn.net.rms.confluxmap.mc.render;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.util.Window;

//#if MC>=12103
//$$ import com.mojang.blaze3d.systems.ProjectionType;
//#if MC>=12108
//$$ import net.minecraft.client.render.RawProjectionMatrix;
//#endif
//#elseif MC>=12100
//$$ import com.mojang.blaze3d.systems.VertexSorter;
//#endif
//#if MC>=12100
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
 * <p>The global model-view stack is left untouched. The canvas projection uses
 * the depth range of the active version's GUI renderer; 1.21.1 also restores
 * the exact projection and vertex sorter that were active on entry.
 */
public final class OffscreenCanvas {
    private Framebuffer framebuffer;
    //#if MC>=12108
    //$$ private RawProjectionMatrix projectionMatrix;
    //#endif

    /** Bind + clear to transparent; sets an ortho projection in canvas pixel units. */
    public void begin(final int sizePx) {
        if (framebuffer == null || framebuffer.textureWidth != sizePx) {
            close();
            //#if MC>=12105
            //$$ framebuffer = new SimpleFramebuffer("Conflux Map minimap", sizePx, sizePx, false);
            //#elseif MC>=12103
            //$$ framebuffer = new SimpleFramebuffer(sizePx, sizePx, false);
            //#else
            framebuffer = new SimpleFramebuffer(sizePx, sizePx, false, MinecraftClient.IS_SYSTEM_MAC);
            //#endif
        }
        //#if MC>=12108
        //$$ if (projectionMatrix == null) {
        //$$     projectionMatrix = new RawProjectionMatrix("Conflux Map minimap projection");
        //$$ }
        //#endif
        //#if MC>=12105
        //$$ RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
        //$$     framebuffer.getColorAttachment(), 0
        //$$ );
        //$$ RenderUtil.setDrawTarget(framebuffer);
        //#elseif MC>=12103
        //$$ framebuffer.setClearColor(0f, 0f, 0f, 0f);
        //$$ framebuffer.clear();
        //#else
        framebuffer.setClearColor(0f, 0f, 0f, 0f);
        framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
        //#endif
        //#if MC<12105
        framebuffer.beginWrite(true);
        //#endif
        //#if MC>=12100
        //#if MC<12103
        //$$ RenderSystem.backupProjectionMatrix();
        //#endif
        //#endif
        setProjection(canvasProjection(sizePx));
    }

    /** 1.21.1 GUI rendering culls map quads unless the canvas uses the same downward Y axis. */
    private static Matrix4f canvasProjection(final int sizePx) {
        //#if MC>=12100
        //#if MC<12103
        //$$ return ortho(0f, sizePx, sizePx, 0f);
        //#else
        //$$ return ortho(0f, sizePx, 0f, sizePx);
        //#endif
        //#else
        return ortho(0f, sizePx, 0f, sizePx);
        //#endif
    }

    /**
     * Orthographic projection over the canvas' depth range. 1.19.4 swapped Minecraft's matrix
     * type for JOML's; the argument order (left, right, bottom, top, near, far) is the same in
     * both, so only the constructing call differs.
     */
    private static Matrix4f ortho(final float left, final float right, final float bottom, final float top) {
        //#if MC>=12100
        //#if MC<12103
        //$$ return new Matrix4f().setOrtho(left, right, bottom, top, 1000f, 21000f);
        //#else
        //$$ return new Matrix4f().setOrtho(left, right, bottom, top, 1000f, 3000f);
        //#endif
        //#else
        return Matrix4f.projectionMatrix(left, right, bottom, top, 1000f, 3000f);
        //#endif
    }

    /** 1.20 made the projection carry an explicit vertex sort order; flat GUI geometry sorts by Z. */
    private void setProjection(final Matrix4f projection) {
        //#if MC>=12108
        //$$ RenderSystem.setProjectionMatrix(projectionMatrix.set(projection), ProjectionType.ORTHOGRAPHIC);
        //#elseif MC>=12103
        //$$ RenderSystem.setProjectionMatrix(projection, ProjectionType.ORTHOGRAPHIC);
        //#elseif MC>=12100
        //$$ RenderSystem.setProjectionMatrix(projection, VertexSorter.BY_Z);
        //#else
        RenderSystem.setProjectionMatrix(projection);
        //#endif
    }

    /** Unbind; restores the main framebuffer and vanilla's GUI projection. */
    public void end(final MinecraftClient client) {
        //#if MC>=12105
        //$$ RenderUtil.setDrawTarget(null);
        //#else
        framebuffer.endWrite();
        client.getFramebuffer().beginWrite(true);
        //#endif
        //#if MC>=12103
        //$$ final Window window = client.getWindow();
        //$$ setProjection(ortho(
        //$$     0f, (float) (window.getFramebufferWidth() / window.getScaleFactor()),
        //$$     0f, (float) (window.getFramebufferHeight() / window.getScaleFactor())
        //$$ ));
        //#elseif MC>=12100
        //$$ RenderSystem.restoreProjectionMatrix();
        //#else
        final Window window = client.getWindow();
        setProjection(ortho(
            0f, (float) (window.getFramebufferWidth() / window.getScaleFactor()),
            0f, (float) (window.getFramebufferHeight() / window.getScaleFactor())
        ));
        //#endif
    }

    /** Binds the canvas contents for sampling; row 0 is the BOTTOM (flip V when sampling). */
    public void bindTexture() {
        //#if MC>=12108
        //$$ RenderUtil.bindTexture(framebuffer.getColorAttachmentView());
        //#elseif MC>=12105
        //$$ RenderUtil.bindTexture(framebuffer.getColorAttachment());
        //#else
        RenderUtil.bindTexture(framebuffer.getColorAttachment());
        //#endif
    }

    public void close() {
        if (framebuffer != null) {
            framebuffer.delete();
            framebuffer = null;
        }
        //#if MC>=12108
        //$$ if (projectionMatrix != null) {
        //$$     projectionMatrix.close();
        //$$     projectionMatrix = null;
        //$$ }
        //#endif
    }
}
