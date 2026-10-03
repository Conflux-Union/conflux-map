package cn.net.rms.confluxmap.mc.render;

import cn.net.rms.confluxmap.compat.Ids;
import com.mojang.blaze3d.systems.RenderSystem;
import java.io.IOException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.util.Window;
//#if MC>=12105 && MC<12111
//$$ import com.mojang.blaze3d.textures.FilterMode;
//#elseif MC<12105
import org.lwjgl.opengl.GL11;
//#endif
//#if MC>=260200
//$$ import com.mojang.blaze3d.resource.GraphicsResourceAllocator;
//$$ import net.minecraft.client.renderer.LevelTargetBundle;
//$$ import net.minecraft.client.renderer.PostChain;
//#elseif MC>=12111
//$$ import net.minecraft.client.gl.PostEffectProcessor;
//$$ import net.minecraft.client.render.DefaultFramebufferSet;
//$$ import net.minecraft.client.util.memory.ObjectAllocator;
//#elseif MC>=12103
//$$ import net.minecraft.client.gl.PostEffectProcessor;
//$$ import net.minecraft.client.render.DefaultFramebufferSet;
//$$ import net.minecraft.client.util.ObjectAllocator;
//#elseif MC>=12000
//$$ import net.minecraft.client.gl.PostEffectProcessor;
//#else
import net.minecraft.client.gl.ShaderEffect;
//#endif

//#if MC>=12103
//$$ import com.mojang.blaze3d.systems.ProjectionType;
//#if MC>=260200
//$$ import com.mojang.blaze3d.GpuFormat;
//$$ import org.joml.Vector4f;
//#endif
//#if MC>=260100
//$$ import net.minecraft.client.renderer.ProjectionMatrixBuffer;
//#elseif MC>=12108
//$$ import net.minecraft.client.render.RawProjectionMatrix;
//#endif
//#elseif MC>=12000
//$$ import com.mojang.blaze3d.systems.VertexSorter;
//#endif
//#if MC>=11904
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
 * <p>The canvas projection matches whatever the active version's GUI renderer
 * expects to see around the drawn plane, and modern versions restore the exact
 * projection and vertex sorter that were active on entry.
 */
public final class OffscreenCanvas {
    private Framebuffer framebuffer;
    private int widthPx;
    private int heightPx;
    //#if MC>=12000 && MC<12103
    //$$ private PostEffectProcessor blurEffect;
    //#elseif MC<12000
    private ShaderEffect blurEffect;
    //#endif
    //#if MC>=260100
    //$$ private ProjectionMatrixBuffer projectionMatrix;
    //#elseif MC>=12108
    //$$ private RawProjectionMatrix projectionMatrix;
    //#endif

    /** Bind + clear to transparent; sets an ortho projection in canvas pixel units. */
    public void begin(final int sizePx) {
        beginInternal(sizePx, true);
    }

    /** Rectangular variant used by full-width GUI effects. */
    public void begin(final int widthPx, final int heightPx) {
        beginInternal(widthPx, heightPx, true);
    }

    /** Binds the canvas without clearing it, for persistent texture-atlas updates. */
    public void beginPreserving(final int sizePx) {
        beginInternal(sizePx, false);
    }

    private void beginInternal(final int sizePx, final boolean clear) {
        beginInternal(sizePx, sizePx, clear);
    }

    private void beginInternal(final int widthPx, final int heightPx, final boolean clear) {
        final boolean created = framebuffer == null
            || framebuffer.textureWidth != widthPx
            || framebuffer.textureHeight != heightPx;
        if (created) {
            close();
            //#if MC>=260300
            //$$ // 26.3 RenderTargets describe both attachments as formats; null depth means none.
            //$$ framebuffer = new TextureTarget(
            //$$     "Conflux Map canvas", widthPx, heightPx, GpuFormat.RGBA8_UNORM, null
            //$$ );
            //#elseif MC>=260200
            //$$ framebuffer = new TextureTarget(
            //$$     "Conflux Map canvas", widthPx, heightPx, false, GpuFormat.RGBA8_UNORM
            //$$ );
            //#elseif MC>=12105
            //$$ framebuffer = new SimpleFramebuffer("Conflux Map canvas", widthPx, heightPx, false);
            //#elseif MC>=12103
            //$$ framebuffer = new SimpleFramebuffer(widthPx, heightPx, false);
            //#else
            framebuffer = new SimpleFramebuffer(
                widthPx, heightPx, false, MinecraftClient.IS_SYSTEM_MAC
            );
            //#endif
            this.widthPx = widthPx;
            this.heightPx = heightPx;
        }
        //#if MC>=260100
        //$$ if (projectionMatrix == null) {
        //$$     projectionMatrix = new ProjectionMatrixBuffer("Conflux Map minimap projection");
        //$$ }
        //#elseif MC>=12108
        //$$ if (projectionMatrix == null) {
        //$$     projectionMatrix = new RawProjectionMatrix("Conflux Map minimap projection");
        //$$ }
        //#endif
        //#if MC>=260200
        //$$ if (created || clear) {
        //$$     RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
        //$$         framebuffer.getColorTexture(), new Vector4f(0f, 0f, 0f, 0f)
        //$$     );
        //$$ }
        //$$ RenderUtil.setDrawTarget(framebuffer);
        //#elseif MC>=12105
        //$$ if (created || clear) {
        //$$     RenderSystem.getDevice().createCommandEncoder().clearColorTexture(
        //$$         framebuffer.getColorAttachment(), 0
        //$$     );
        //$$ }
        //$$ RenderUtil.setDrawTarget(framebuffer);
        //#elseif MC>=12103
        //$$ if (created || clear) {
        //$$     framebuffer.setClearColor(0f, 0f, 0f, 0f);
        //$$     framebuffer.clear();
        //$$ }
        //#else
        if (created || clear) {
            framebuffer.setClearColor(0f, 0f, 0f, 0f);
            framebuffer.clear(MinecraftClient.IS_SYSTEM_MAC);
        }
        //#endif
        //#if MC<12105
        framebuffer.beginWrite(true);
        //#endif
        //#if MC>=12000
        //$$ RenderSystem.backupProjectionMatrix();
        //#endif
        //#if MC>=12100
        //$$ // Persistent atlases are filled from the client tick, outside any GUI pass, so the
        //$$ // global model-view still holds whatever the world pass left in it. Canvas geometry is
        //$$ // already in canvas pixels and needs none of it.
        //$$ RenderSystem.getModelViewStack().pushMatrix().identity();
        //#if MC<12103
        //$$ RenderSystem.applyModelViewMatrix();
        //#endif
        //#endif
        setProjection(canvasProjection(widthPx, heightPx));
    }

    /** Modern GUI rendering culls map quads unless the canvas uses the same downward Y axis. */
    private static Matrix4f canvasProjection(final int sizePx) {
        return canvasProjection(sizePx, sizePx);
    }

    private static Matrix4f canvasProjection(final int widthPx, final int heightPx) {
        //#if MC>=12000
        //$$ return ortho(0f, widthPx, heightPx, 0f);
        //#else
        return ortho(0f, widthPx, 0f, heightPx);
        //#endif
    }

    /** Local depth used while the persistent radar atlas is filled from a client tick. */
    public static float atlasDrawPlaneZ() {
        //#if MC<12100
        return -2000f;
        //#else
        //$$ return 0f;
        //#endif
    }

    /**
     * Orthographic projection over the canvas' depth range. 1.19.4 swapped Minecraft's matrix
     * type for JOML's; the argument order (left, right, bottom, top, near, far) is the same in
     * both, so only the constructing call differs. Modern GUI rendering puts its draw plane at
     * z=-11000, so the far plane has to reach past it or every canvas quad is depth-clipped.
     */
    private static Matrix4f ortho(final float left, final float right, final float bottom, final float top) {
        //#if MC>=12100
        //$$ // The canvas installs its own identity model-view, so the depth range only has to cover
        //$$ // the z=0 plane every canvas quad sits on.
        //$$ return new Matrix4f().setOrtho(left, right, bottom, top, -1000f, 1000f);
        //#elseif MC>=11904
        //$$ return new Matrix4f().setOrtho(left, right, bottom, top, 1000f, 21000f);
        //#else
        return Matrix4f.projectionMatrix(left, right, bottom, top, 1000f, 3000f);
        //#endif
    }

    /** 1.20 made the projection carry an explicit vertex sort order; flat GUI geometry sorts by Z. */
    private void setProjection(final Matrix4f projection) {
        //#if MC>=260100
        //$$ RenderSystem.setProjectionMatrix(projectionMatrix.getBuffer(projection), ProjectionType.ORTHOGRAPHIC);
        //#elseif MC>=12108
        //$$ RenderSystem.setProjectionMatrix(projectionMatrix.set(projection), ProjectionType.ORTHOGRAPHIC);
        //#elseif MC>=12103
        //$$ RenderSystem.setProjectionMatrix(projection, ProjectionType.ORTHOGRAPHIC);
        //#elseif MC>=12000
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
        //#if MC>=12100
        //$$ RenderSystem.getModelViewStack().popMatrix();
        //#if MC<12103
        //$$ RenderSystem.applyModelViewMatrix();
        //#endif
        //#endif
        //#if MC>=12000
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

    /** Binds the canvas with bilinear filtering for scaled GUI effects. */
    public void bindTextureLinear() {
        //#if MC>=12111
        //$$ RenderUtil.bindTexture(
        //$$     framebuffer.getColorAttachmentView(),
        //$$     RenderSystem.getSamplerCache().get(
        //$$         com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
        //$$         com.mojang.blaze3d.textures.AddressMode.CLAMP_TO_EDGE,
        //$$         com.mojang.blaze3d.textures.FilterMode.LINEAR,
        //$$         com.mojang.blaze3d.textures.FilterMode.LINEAR,
        //$$         false
        //$$     )
        //$$ );
        //#elseif MC>=12108
        //$$ framebuffer.setFilter(FilterMode.LINEAR);
        //$$ RenderUtil.bindTexture(framebuffer.getColorAttachmentView());
        //#elseif MC>=12105
        //$$ framebuffer.setFilter(FilterMode.LINEAR);
        //$$ RenderUtil.bindTexture(framebuffer.getColorAttachment());
        //#else
        framebuffer.setTexFilter(GL11.GL_LINEAR);
        RenderUtil.bindTexture(framebuffer.getColorAttachment());
        //#endif
    }

    /** Applies Minecraft's menu-style separable box blur to this canvas in place. */
    public boolean applyMenuBlur(final MinecraftClient client) throws IOException {
        //#if MC>=260200
        //$$ final PostChain blur = client.getShaderManager().getPostChain(
        //$$     Ids.of("minecraft", "blur"), LevelTargetBundle.MAIN_TARGETS
        //$$ );
        //$$ if (blur == null) {
        //$$     return false;
        //$$ }
        //$$ blur.process(framebuffer, GraphicsResourceAllocator.UNPOOLED);
        //#elseif MC>=12103
        //$$ final PostEffectProcessor blur = client.getShaderLoader().loadPostEffect(
        //$$     Ids.of("minecraft", "blur"), DefaultFramebufferSet.MAIN_ONLY
        //$$ );
        //$$ if (blur == null) {
        //$$     return false;
        //$$ }
        //$$ final float radius = client.options.getMenuBackgroundBlurrinessValue();
        //#if MC<12105
        //$$ blur.setUniforms("Radius", radius);
        //$$ blur.render(framebuffer, ObjectAllocator.TRIVIAL);
        //$$ // Every post-effect pass ends by unbinding to the default window framebuffer, and
        //$$ // 1.21.2-1.21.4 GUI drawing targets whatever is bound. Vanilla rebinds the main
        //$$ // framebuffer right after GameRenderer.renderBlur for exactly this reason.
        //$$ client.getFramebuffer().beginWrite(false);
        //#elseif MC<12108
        //$$ blur.render(
        //$$     framebuffer, ObjectAllocator.TRIVIAL,
        //$$     pass -> pass.setUniform("Radius", radius)
        //$$ );
        //#else
        //$$ blur.render(framebuffer, ObjectAllocator.TRIVIAL);
        //#endif
        //#else
        if (blurEffect == null) {
            blurEffect = createMenuBlurEffect(client);
            blurEffect.setupDimensions(widthPx, heightPx);
        }
        //#if MC>=12100
        //$$ blurEffect.setUniforms(
        //$$     "Radius", client.options.getMenuBackgroundBlurrinessValue()
        //$$ );
        //#endif
        blurEffect.render(0f);
        client.getFramebuffer().beginWrite(false);
        //#endif
        return true;
    }

    //#if MC<12103
    private
        //#if MC>=12000
        //$$ PostEffectProcessor
        //#else
        ShaderEffect
        //#endif
        createMenuBlurEffect(final MinecraftClient client) throws IOException {
        return new
            //#if MC>=12000
            //$$ PostEffectProcessor(
            //#else
            ShaderEffect(
            //#endif
            client.getTextureManager(), client.getResourceManager(), framebuffer,
            //#if MC>=12100
            //$$ Ids.of("minecraft", "shaders/post/blur.json")
            //#else
            Ids.of("confluxmap", "shaders/post/split_map_blur.json")
            //#endif
        );
    }
    //#endif

    public void close() {
        //#if MC<12103
        if (blurEffect != null) {
            blurEffect.close();
            blurEffect = null;
        }
        //#endif
        if (framebuffer != null) {
            framebuffer.delete();
            framebuffer = null;
            widthPx = 0;
            heightPx = 0;
        }
        //#if MC>=12108
        //$$ if (projectionMatrix != null) {
        //$$     projectionMatrix.close();
        //$$     projectionMatrix = null;
        //$$ }
        //#endif
    }

    public int size() {
        return widthPx;
    }
}
