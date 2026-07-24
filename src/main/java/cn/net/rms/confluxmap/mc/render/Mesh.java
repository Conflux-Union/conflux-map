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

//#if MC>=12105
//$$ import org.joml.Matrix4f;
//#if MC>=12108
//$$ import org.joml.Vector4f;
//#if MC>=12111
//$$ import org.joml.Vector3f;
//#endif
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
 * {@link #draw() drawn} before the next one begins.
 */
public final class Mesh {
    private final BufferBuilder buffer;

    private Mesh(final BufferBuilder buffer) {
        this.buffer = buffer;
    }

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
        buffer.vertex(model, x, y, z);
        return this;
    }

    public Mesh texture(final float u, final float v) {
        buffer.texture(u, v);
        return this;
    }

    public Mesh color(final float r, final float g, final float b, final float a) {
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
        buffer.vertex(model, x, y, z);
        //#if MC>=12100
        //$$ buffer.texture(u, v);
        //$$ buffer.color(r, g, b, a);
        //#else
        buffer.color(r, g, b, a);
        buffer.texture(u, v);
        //#endif
        return next();
    }

    /** Ends the vertex under construction. A no-op where the buffer commits vertices itself. */
    public Mesh next() {
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
}
