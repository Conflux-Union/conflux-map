package cn.net.rms.confluxmap.mc.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
//#if MC>=12105
//$$ import com.mojang.blaze3d.buffers.GpuBuffer;
//$$ import com.mojang.blaze3d.pipeline.RenderPipeline;
//$$ import com.mojang.blaze3d.systems.RenderPass;
//$$ import com.mojang.blaze3d.systems.RenderSystem;
//$$ import com.mojang.blaze3d.vertex.VertexFormat;
//$$ import java.util.OptionalDouble;
//$$ import java.util.OptionalInt;
//$$ import net.minecraft.client.render.BuiltBuffer;
//#else
import net.minecraft.client.render.VertexFormat;
//#endif
import net.minecraft.client.render.VertexFormats;

//#if MC>=260100
//$$ import net.minecraft.client.gui.navigation.ScreenRectangle;
//$$ import net.minecraft.client.renderer.state.gui.GuiRenderState;
//$$ import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//$$ import net.minecraft.client.gui.render.TextureSetup;
//$$ import java.util.Arrays;
//#elseif MC>=12108
//$$ import net.minecraft.client.gui.ScreenRect;
//$$ import net.minecraft.client.gui.render.state.GuiRenderState;
//$$ import net.minecraft.client.gui.render.state.SimpleGuiElementRenderState;
//$$ import net.minecraft.client.render.VertexConsumer;
//$$ import net.minecraft.client.texture.TextureSetup;
//$$ import java.util.Arrays;
//#endif

//#if MC>=12105
//$$ import org.joml.Matrix4f;
//#if MC>=12108
//$$ import org.joml.Vector3f;
//$$ import org.joml.Vector4f;
//#endif
//#elseif MC>=12100
//$$ import net.minecraft.client.render.BufferRenderer;
//$$ import org.joml.Matrix4f;
//#else
import net.minecraft.util.math.Matrix4f;
//#endif

/**
 * One immediate-mode draw call, hiding how this Minecraft version starts and finishes a
 * tessellator batch.
 *
 * <p>1.21 rewrote the entry and exit of the batch - {@code Tessellator.getBuffer()} plus
 * {@code buffer.begin(..)} became {@code Tessellator.begin(..)}, per-vertex {@code next()}
 * disappeared, and {@code Tessellator.draw()} became
 * {@code BufferRenderer.drawWithGlobalProgram(buffer.end())} - while the vertex emission in
 * between stayed the same. Wrapping only the three changed points keeps every caller's geometry
 * as a single shared copy instead of one per version.
 *
 * <p>Render thread only, and not reentrant: the tessellator is a singleton, so a batch must be
 * {@link #draw() drawn} before the next one begins. A captured batch (see {@link #beginGui}) owns
 * its vertices instead of the tessellator's and is exempt from that.
 */
public final class Mesh {
    //#if MC>=12108
    //$$ /** Captured vertex layout: x, y, z, u, v, r, g, b, a. */
    //$$ private static final int STRIDE = 9;
    //#endif

    private final BufferBuilder buffer;
    //#if MC>=12108
    //$$ private final GuiRenderState guiState;
    //$$ private final boolean textured;
    //$$ private final float[] pending;
    //$$ private float[] vertices;
    //$$ private int vertexCount;
    //#endif

    private Mesh(final BufferBuilder buffer) {
        this.buffer = buffer;
        //#if MC>=12108
        //$$ this.guiState = null;
        //$$ this.textured = false;
        //$$ this.pending = null;
        //#endif
    }

    //#if MC>=12108
    //$$ private Mesh(final GuiRenderState guiState, final boolean textured) {
    //$$     this.buffer = null;
    //$$     this.guiState = guiState;
    //$$     this.textured = textured;
    //$$     this.pending = new float[STRIDE];
    //$$     this.vertices = new float[STRIDE * 16];
    //$$ }
    //#endif

    /** Starts a batch in {@code format}; finish it with {@link #draw()}. */
    public static Mesh begin(final VertexFormat.DrawMode mode, final VertexFormat format) {
        //#if MC>=12100
        //$$ return new Mesh(Tessellator.getInstance().begin(mode, format));
        //#else
        final BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.begin(mode, format);
        return new Mesh(buffer);
        //#endif
    }

    /**
     * Starts a batch of flat GUI-space geometry; finish it with {@code drawGui}.
     *
     * <p>1.21.6 turned the GUI retained-mode: a screen or HUD callback only records elements into
     * a {@code GuiRenderState}, and the renderer replays the whole list afterwards under its own
     * projection. A batch that draws itself on the spot there paints with the world pass' matrices
     * and is then covered by everything vanilla recorded, so from that version the vertices are
     * captured and handed to the state instead. Older versions - and canvas batches, which target
     * {@link OffscreenCanvas}' own framebuffer rather than the screen - still draw immediately.
     */
    public static Mesh beginGui(final VertexFormat.DrawMode mode, final VertexFormat format) {
        //#if MC>=12108
        //$$ final GuiRenderState guiState = RenderUtil.guiState();
        //$$ if (guiState != null) {
        //$$     return new Mesh(guiState, format == tintedTextureFormat());
        //$$ }
        //#endif
        return begin(mode, format);
    }

    /**
     * The vertex format for a textured quad carrying a per-vertex tint. 1.20.5 reordered the
     * elements (and renamed the constant accordingly), so callers must emit texture and colour
     * through {@link #tintedVertex} rather than ordering the calls themselves.
     */
    public static VertexFormat tintedTextureFormat() {
        //#if MC>=12100
        //$$ return VertexFormats.POSITION_TEXTURE_COLOR;
        //#else
        return VertexFormats.POSITION_COLOR_TEXTURE;
        //#endif
    }

    /** Position-only vertex, for formats whose remaining elements the caller adds next. */
    public Mesh vertex(final Matrix4f model, final float x, final float y, final float z) {
        //#if MC>=12108
        //$$ if (buffer == null) {
        //$$     // A captured batch is replayed without a matrix, so the transform is applied here.
        //$$     final Vector3f position = model.transformPosition(x, y, z, new Vector3f());
        //$$     pending[0] = position.x;
        //$$     pending[1] = position.y;
        //$$     pending[2] = position.z;
        //$$     return this;
        //$$ }
        //#endif
        buffer.vertex(model, x, y, z);
        return this;
    }

    public Mesh texture(final float u, final float v) {
        //#if MC>=12108
        //$$ if (buffer == null) {
        //$$     pending[3] = u;
        //$$     pending[4] = v;
        //$$     return this;
        //$$ }
        //#endif
        buffer.texture(u, v);
        return this;
    }

    public Mesh color(final float r, final float g, final float b, final float a) {
        //#if MC>=12108
        //$$ if (buffer == null) {
        //$$     pending[5] = r;
        //$$     pending[6] = g;
        //$$     pending[7] = b;
        //$$     pending[8] = a;
        //$$     return this;
        //$$ }
        //#endif
        buffer.color(r, g, b, a);
        return this;
    }

    /** A complete vertex in {@link #tintedTextureFormat()}, emitted in this version's element order. */
    public Mesh tintedVertex(
        final Matrix4f model,
        final float x, final float y, final float z,
        final float u, final float v,
        final float r, final float g, final float b, final float a
    ) {
        vertex(model, x, y, z);
        //#if MC>=12100
        //$$ texture(u, v);
        //$$ color(r, g, b, a);
        //#else
        color(r, g, b, a);
        texture(u, v);
        //#endif
        return next();
    }

    /** Ends the vertex under construction. A no-op where the buffer commits vertices itself. */
    public Mesh next() {
        //#if MC>=12108
        //$$ if (buffer == null) {
        //$$     if (vertexCount * STRIDE == vertices.length) {
        //$$         vertices = Arrays.copyOf(vertices, vertices.length * 2);
        //$$     }
        //$$     System.arraycopy(pending, 0, vertices, vertexCount * STRIDE, STRIDE);
        //$$     vertexCount++;
        //$$     return this;
        //$$ }
        //#endif
        //#if MC<12100
        buffer.next();
        //#endif
        return this;
    }

    /** Uploads and draws the batch. */
    public void draw() {
        //#if MC>=12105
        //$$ throw new IllegalStateException("1.21.5+ draws require an explicit render pipeline");
        //#elseif MC>=12100
        //$$ BufferRenderer.drawWithGlobalProgram(buffer.end());
        //#else
        Tessellator.getInstance().draw();
        //#endif
    }

    //#if MC>=12105
    //$$ /** Finishes a {@link #beginGui} batch: recorded for the GUI renderer, or drawn on the spot. */
    //$$ public void drawGui(final RenderPipeline pipeline) {
    //#if MC>=260100
    //$$     if (buffer == null) {
    //$$         guiState.addGuiElement(new CapturedGuiElement(
    //$$             pipeline,
    //$$             RenderUtil.guiTextureSetup(textured),
    //$$             RenderUtil.guiScissor(),
    //$$             vertices,
    //$$             vertexCount,
    //$$             textured
    //$$         ));
    //$$         return;
    //$$     }
    //#elseif MC>=12108
    //$$     if (buffer == null) {
    //$$         guiState.addSimpleElement(new CapturedGuiElement(
    //$$             pipeline,
    //$$             RenderUtil.guiTextureSetup(textured),
    //$$             RenderUtil.guiScissor(),
    //$$             vertices,
    //$$             vertexCount,
    //$$             textured
    //$$         ));
    //$$         return;
    //$$     }
    //#endif
    //$$     draw(pipeline);
    //$$ }

    //$$ /** Uploads and draws the batch through the pipeline-based 1.21.5 renderer. */
    //$$ public void draw(final RenderPipeline pipeline) {
    //$$     try (BuiltBuffer built = buffer.end()) {
    //$$         final GpuBuffer vertexBuffer = pipeline.getVertexFormat()
    //$$             .uploadImmediateVertexBuffer(built.getBuffer());
    //$$         final GpuBuffer indexBuffer;
    //$$         final VertexFormat.IndexType indexType;
    //$$         if (built.getSortedBuffer() == null) {
    //$$             final RenderSystem.ShapeIndexBuffer sequential = RenderSystem.getSequentialBuffer(
    //$$                 built.getDrawParameters().mode()
    //$$             );
    //$$             indexBuffer = sequential.getIndexBuffer(built.getDrawParameters().indexCount());
    //$$             indexType = sequential.getIndexType();
    //$$         } else {
    //$$             indexBuffer = pipeline.getVertexFormat()
    //$$                 .uploadImmediateIndexBuffer(built.getSortedBuffer());
    //$$             indexType = built.getDrawParameters().indexType();
    //$$         }
    //$$         final var target = RenderUtil.drawTarget();
    //#if MC>=12111
    //$$         final var dynamicTransforms = RenderSystem.getDynamicUniforms().write(
    //$$             RenderSystem.getModelViewMatrix(),
    //$$             new Vector4f(1f, 1f, 1f, 1f),
    //$$             new Vector3f(),
    //$$             new Matrix4f()
    //$$         );
    //#elseif MC>=12109
    //$$         // 1.21.9 dropped RenderSystem's model offset but kept the line-width argument that
    //$$         // 1.21.11 went on to drop as well.
    //$$         final var dynamicTransforms = RenderSystem.getDynamicUniforms().write(
    //$$             RenderSystem.getModelViewMatrix(),
    //$$             new Vector4f(1f, 1f, 1f, 1f),
    //$$             new Vector3f(),
    //$$             new Matrix4f(),
    //$$             RenderSystem.getShaderLineWidth()
    //$$         );
    //#elseif MC>=12108
    //$$         final var dynamicTransforms = RenderSystem.getDynamicUniforms().write(
    //$$             RenderSystem.getModelViewMatrix(),
    //$$             new Vector4f(1f, 1f, 1f, 1f),
    //$$             RenderSystem.getModelOffset(),
    //$$             RenderSystem.getTextureMatrix(),
    //$$             RenderSystem.getShaderLineWidth()
    //$$         );
    //#endif
    //#if MC>=12108
    //$$         try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
    //$$             () -> "Conflux Map immediate " + pipeline.getLocation(),
    //$$             target.getColorAttachmentView(),
    //$$             OptionalInt.empty(),
    //$$             target.useDepthAttachment ? target.getDepthAttachmentView() : null,
    //$$             OptionalDouble.empty()
    //$$         )) {
    //#else
    //$$         try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
    //$$             target.getColorAttachment(),
    //$$             OptionalInt.empty(),
    //$$             target.useDepthAttachment ? target.getDepthAttachment() : null,
    //$$             OptionalDouble.empty()
    //$$         )) {
    //#endif
    //$$             pass.setPipeline(pipeline);
    //#if MC>=12108
    //$$             RenderSystem.bindDefaultUniforms(pass);
    //$$             pass.setUniform("DynamicTransforms", dynamicTransforms);
    //#endif
    //$$             pass.setVertexBuffer(0, vertexBuffer);
    //$$             RenderUtil.applyScissor(pass);
    //#if MC>=12111
    //$$             if (pipeline.getSamplers().contains("Sampler0")
    //$$                 && RenderUtil.boundTexture() != null
    //$$                 && RenderUtil.boundSampler() != null) {
    //$$                 pass.bindTexture("Sampler0", RenderUtil.boundTexture(), RenderUtil.boundSampler());
    //$$             }
    //#else
    //$$             for (int i = 0; i < RenderSystem.TEXTURE_COUNT; i++) {
    //$$                 final var texture = RenderSystem.getShaderTexture(i);
    //$$                 if (texture != null) {
    //$$                     pass.bindSampler("Sampler" + i, texture);
    //$$                 }
    //$$             }
    //#endif
    //$$             pass.setIndexBuffer(indexBuffer, indexType);
    //#if MC>=12108
    //$$             pass.drawIndexed(0, 0, built.getDrawParameters().indexCount(), 1);
    //#else
    //$$             pass.drawIndexed(0, built.getDrawParameters().indexCount());
    //#endif
    //$$         }
    //$$     }
    //$$ }
    //#endif

    //#if MC>=12108
    //$$ /**
    //$$  * One captured GUI batch, waiting for the GUI renderer's pass.
    //$$  *
    //$$  * <p>The renderer decides the batch's depth and hands over a shared vertex consumer, so the
    //$$  * vertices are replayed flat and already in screen space. {@link #bounds()} is what lets
    //$$  * vanilla keep draw order: it starts a fresh layer whenever a new element overlaps one
    //$$  * already recorded, which is the only thing standing between the map and the markers on top
    //$$  * of it once elements get sorted into texture batches.
    //$$  */
    //#if MC>=260100
    //$$ private static final class CapturedGuiElement implements GuiElementRenderState {
    //$$     private final RenderPipeline pipeline;
    //$$     private final TextureSetup textureSetup;
    //$$     private final ScreenRectangle scissorArea;
    //$$     private final ScreenRectangle bounds;
    //$$     private final float[] vertices;
    //$$     private final int vertexCount;
    //$$     private final boolean textured;
    //$$
    //$$     CapturedGuiElement(
    //$$         final RenderPipeline pipeline,
    //$$         final TextureSetup textureSetup,
    //$$         final ScreenRectangle scissorArea,
    //$$         final float[] vertices,
    //$$         final int vertexCount,
    //$$         final boolean textured
    //$$     ) {
    //$$         this.pipeline = pipeline;
    //$$         this.textureSetup = textureSetup;
    //$$         this.scissorArea = scissorArea;
    //$$         this.vertices = vertices;
    //$$         this.vertexCount = vertexCount;
    //$$         this.textured = textured;
    //$$         this.bounds = boundsOf(vertices, vertexCount, scissorArea);
    //$$     }
    //$$
    //$$     @Override
    //$$     public RenderPipeline pipeline() {
    //$$         return pipeline;
    //$$     }
    //$$
    //$$     @Override
    //$$     public TextureSetup textureSetup() {
    //$$         return textureSetup;
    //$$     }
    //$$
    //$$     @Override
    //$$     public ScreenRectangle scissorArea() {
    //$$         return scissorArea;
    //$$     }
    //$$
    //$$     @Override
    //$$     public ScreenRectangle bounds() {
    //$$         return bounds;
    //$$     }
    //$$
    //$$     @Override
    //$$     public void buildVertices(final VertexConsumer consumer) {
    //$$         // 1.21.11 drops the depth argument and offsets the whole batch itself.
    //$$         emit(consumer, 0f);
    //$$     }
    //$$
    //$$     private void emit(final VertexConsumer consumer, final float depth) {
    //$$         for (int i = 0; i < vertexCount; i++) {
    //$$             final int offset = i * STRIDE;
    //$$             consumer.addVertex(vertices[offset], vertices[offset + 1], depth);
    //$$             if (textured) {
    //$$                 consumer.setUv(vertices[offset + 3], vertices[offset + 4]);
    //$$             }
    //$$             consumer.setColor(
    //$$                 vertices[offset + 5], vertices[offset + 6],
    //$$                 vertices[offset + 7], vertices[offset + 8]
    //$$             );
    //$$         }
    //$$     }
    //$$
    //$$     private static ScreenRectangle boundsOf(
    //$$         final float[] vertices,
    //$$         final int vertexCount,
    //$$         final ScreenRectangle scissorArea
    //$$     ) {
    //$$         if (vertexCount == 0) {
    //$$             return ScreenRectangle.empty();
    //$$         }
    //$$         float minX = Float.POSITIVE_INFINITY;
    //$$         float minY = Float.POSITIVE_INFINITY;
    //$$         float maxX = Float.NEGATIVE_INFINITY;
    //$$         float maxY = Float.NEGATIVE_INFINITY;
    //$$         for (int i = 0; i < vertexCount; i++) {
    //$$             final float x = vertices[i * STRIDE];
    //$$             final float y = vertices[i * STRIDE + 1];
    //$$             minX = Math.min(minX, x);
    //$$             minY = Math.min(minY, y);
    //$$             maxX = Math.max(maxX, x);
    //$$             maxY = Math.max(maxY, y);
    //$$         }
    //$$         final int left = (int) Math.floor(minX);
    //$$         final int top = (int) Math.floor(minY);
    //$$         final ScreenRectangle rect = new ScreenRectangle(
    //$$             left, top, (int) Math.ceil(maxX) - left, (int) Math.ceil(maxY) - top
    //$$         );
    //$$         return scissorArea == null ? rect : rect.intersection(scissorArea);
    //$$     }
    //$$ }
    //#else
    //$$ private static final class CapturedGuiElement implements SimpleGuiElementRenderState {
    //$$     private final RenderPipeline pipeline;
    //$$     private final TextureSetup textureSetup;
    //$$     private final ScreenRect scissorArea;
    //$$     private final ScreenRect bounds;
    //$$     private final float[] vertices;
    //$$     private final int vertexCount;
    //$$     private final boolean textured;
    //$$
    //$$     CapturedGuiElement(
    //$$         final RenderPipeline pipeline,
    //$$         final TextureSetup textureSetup,
    //$$         final ScreenRect scissorArea,
    //$$         final float[] vertices,
    //$$         final int vertexCount,
    //$$         final boolean textured
    //$$     ) {
    //$$         this.pipeline = pipeline;
    //$$         this.textureSetup = textureSetup;
    //$$         this.scissorArea = scissorArea;
    //$$         this.vertices = vertices;
    //$$         this.vertexCount = vertexCount;
    //$$         this.textured = textured;
    //$$         this.bounds = boundsOf(vertices, vertexCount, scissorArea);
    //$$     }
    //$$
    //$$     @Override
    //$$     public RenderPipeline pipeline() {
    //$$         return pipeline;
    //$$     }
    //$$
    //$$     @Override
    //$$     public TextureSetup textureSetup() {
    //$$         return textureSetup;
    //$$     }
    //$$
    //$$     @Override
    //$$     public ScreenRect scissorArea() {
    //$$         return scissorArea;
    //$$     }
    //$$
    //$$     @Override
    //$$     public ScreenRect bounds() {
    //$$         return bounds;
    //$$     }
    //$$
    //#if MC>=12109
    //$$     @Override
    //$$     public void setupVertices(final VertexConsumer consumer) {
    //$$         // 1.21.11 drops the depth argument and offsets the whole batch itself.
    //$$         emit(consumer, 0f);
    //$$     }
    //#else
    //$$     @Override
    //$$     public void setupVertices(final VertexConsumer consumer, final float depth) {
    //$$         emit(consumer, depth);
    //$$     }
    //#endif
    //$$
    //$$     private void emit(final VertexConsumer consumer, final float depth) {
    //$$         for (int i = 0; i < vertexCount; i++) {
    //$$             final int offset = i * STRIDE;
    //$$             consumer.vertex(vertices[offset], vertices[offset + 1], depth);
    //$$             if (textured) {
    //$$                 consumer.texture(vertices[offset + 3], vertices[offset + 4]);
    //$$             }
    //$$             consumer.color(
    //$$                 vertices[offset + 5], vertices[offset + 6],
    //$$                 vertices[offset + 7], vertices[offset + 8]
    //$$             );
    //$$         }
    //$$     }
    //$$
    //$$     private static ScreenRect boundsOf(
    //$$         final float[] vertices,
    //$$         final int vertexCount,
    //$$         final ScreenRect scissorArea
    //$$     ) {
    //$$         if (vertexCount == 0) {
    //$$             return ScreenRect.empty();
    //$$         }
    //$$         float minX = Float.POSITIVE_INFINITY;
    //$$         float minY = Float.POSITIVE_INFINITY;
    //$$         float maxX = Float.NEGATIVE_INFINITY;
    //$$         float maxY = Float.NEGATIVE_INFINITY;
    //$$         for (int i = 0; i < vertexCount; i++) {
    //$$             final float x = vertices[i * STRIDE];
    //$$             final float y = vertices[i * STRIDE + 1];
    //$$             minX = Math.min(minX, x);
    //$$             minY = Math.min(minY, y);
    //$$             maxX = Math.max(maxX, x);
    //$$             maxY = Math.max(maxY, y);
    //$$         }
    //$$         final int left = (int) Math.floor(minX);
    //$$         final int top = (int) Math.floor(minY);
    //$$         final ScreenRect rect = new ScreenRect(
    //$$             left, top, (int) Math.ceil(maxX) - left, (int) Math.ceil(maxY) - top
    //$$         );
    //$$         return scissorArea == null ? rect : rect.intersection(scissorArea);
    //$$     }
    //$$ }
    //#endif
    //#endif
}
