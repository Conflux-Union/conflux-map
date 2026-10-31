package cn.net.rms.confluxmap.mc.ui.world;

import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.Mesh;
import cn.net.rms.confluxmap.mc.render.WorldMatrices;
import net.minecraft.client.render.VertexFormats;

import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=12109 && MC<260100
//$$ import net.minecraft.client.render.RenderLayer;
//$$ import net.minecraft.client.render.VertexConsumer;
//#if MC>=12111
//$$ import net.minecraft.client.render.RenderLayers;
//#endif
//#endif
//#if MC>=260200
//$$ import com.mojang.blaze3d.vertex.VertexConsumer;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector;
//$$ import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases;
//$$ import net.minecraft.client.renderer.feature.CustomFeatureRenderer;
//$$ import net.minecraft.client.renderer.rendertype.RenderTypes;
//#endif
import net.minecraft.client.util.math.MatrixStack;
//#if MC>=12105
//$$ import net.minecraft.client.gl.RenderPipelines;
//#endif
//#if MC>=12108 && MC<12109
//$$ import com.mojang.blaze3d.buffers.GpuBuffer;
//$$ import com.mojang.blaze3d.buffers.GpuBufferSlice;
//$$ import com.mojang.blaze3d.buffers.Std140Builder;
//$$ import net.minecraft.client.render.fog.FogRenderer;
//$$ import org.lwjgl.system.MemoryStack;
//#endif

/**
 * Owns the beam pass's per-version state brackets and submission. {@link #beginPass} and
 * {@link #endPass} bracket one beam render: before 1.21.5 the beam needs depth-test-on,
 * depth-write-off, cull-off; before 1.21.9 it also reinstalls the world projection and the
 * view matrix captured at LAST (END runs after a deferred pack's finalize, which leaves the
 * global projection pointing at composite passes and after vanilla popped the world view);
 * every version draws additively through {@link #beginAdditiveTriangles()}, whose
 * 1.21.8 fog-slice bracket lives here. The beam geometry itself arrives from
 * {@link WaypointBeamPainter}; this class only emits it - raw tessellator triangles before
 * 1.21.9, shared-entity-consumer batches through the vanilla dragon-rays layer on
 * 1.21.9-1.21.11, raw triangles at renderLevel's RETURN on 26.1, and submitted custom nodes
 * in the AFTER_TERRAIN phase from 26.2 on, where the open translucent pass forbids issuing
 * draws on the spot.
 */
final class BeamPass implements AutoCloseable, WaypointBeamPainter.Sink {
    /** The pass frame, valid between {@link #beginPass} and {@link #endPass}. */
    private OverlayFrame frame;
    //#if MC>=12108 && MC<12109
    //$$ /** Fog whose ranges sit past any real distance, so the lightning shader's fog factor stays 0. */
    //$$ private static GpuBuffer beamNoFadeFog;
    //$$ private static GpuBufferSlice beamPreviousFog;
    //#endif

    static BeamPass of() {
        return new BeamPass();
    }

    private BeamPass() {
    }

    void beginPass(final OverlayFrame frame) {
        this.frame = frame;
        //#if MC<12105
        RenderSystem.enableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        //#endif
        //#if MC<12109
        WorldMatrices.applyWorldProjection(frame.projectionMatrix);
        WorldMatrices.pushWorldViewSnapshot();
        //#elseif MC>=260100 && MC<260200
        //$$ // The 26.1 RETURN draw runs after vanilla popped the world view but before anything
        //$$ // swapped the world projection away, so only the view snapshot gets reinstalled.
        //$$ WorldMatrices.pushWorldViewSnapshot();
        //#endif
        beginAdditiveTriangles();
    }

    void draw(
        final double worldX,
        final double worldZ,
        final double bottomY,
        final double topY,
        final int colorArgb,
        final double horizontalDistance,
        final double maxDistance,
        final float visibilityAlpha
    ) {
        WaypointBeamPainter.drawBeam(
            this, frame.matrices, frame.cameraPos, worldX, worldZ, bottomY, topY,
            colorArgb, horizontalDistance, maxDistance, visibilityAlpha
        );
    }

    /**
     * One tube face as two triangles with opposite winding, the double-sided emission the
     * immediate path uses; from 26.2 on the two triangles ride one submitted node and vanilla
     * batches consecutive nodes into a single staged draw through the dragon-rays
     * RenderType, the same pipeline the immediate path draws with on older versions.
     */
    @Override
    public void beamSide(
        final MatrixStack matrices,
        final float x0, final float z0,
        final float x1, final float z1,
        final float bottom, final float top,
        final int color
    ) {
        //#if MC>=260200
        //$$ submitBeamSide(frame.beamSubmits, matrices.last().copy(), x0, z0, x1, z1, bottom, top, color);
        //#elseif MC>=12109 && MC<260100
        //$$ immediateBeamSide(matrices, x0, z0, x1, z1, bottom, top, color);
        //#else
        fillTriangle3D(matrices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
        fillTriangle3D(matrices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
        //#endif
    }

    void endPass() {
        restoreDefaultBlend();
        //#if MC>=12109 && MC<260100
        //$$ // The buffered dragon-rays layer must draw now, inside the world pass at the
        //$$ // BEFORE_TRANSLUCENT slot the raw draw used; left pending it would only flush at the
        //$$ // next layer switch, when the world matrices are already gone.
        //#if MC>=12111
        //$$ frame.immediate.draw(RenderLayers.dragonRays());
        //#else
        //$$ frame.immediate.draw(RenderLayer.getDragonRays());
        //#endif
        //#endif
        //#if MC<12109
        WorldMatrices.popModelView();
        WorldMatrices.restoreProjection();
        //#elseif MC>=260100 && MC<260200
        //$$ WorldMatrices.popModelView();
        //#endif
        //#if MC<12105
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        //#endif
        frame = null;
    }

    //#if MC>=12109 && MC<260100
    //$$ /** The vanilla dragon-rays layer; 1.21.11 relocated it from RenderLayer to RenderLayers. */
    //$$ private static RenderLayer beamLayer() {
    //#if MC>=12111
    //$$     return RenderLayers.dragonRays();
    //#else
    //$$     return RenderLayer.getDragonRays();
    //#endif
    //$$ }
    //$$
    //$$ /**
    //$$  * One tube face as two triangles with opposite winding, buffered into the shared entity
    //$$  * vertex consumers through the vanilla dragon-rays layer - the same captured path the
    //$$  * labels draw through and the same pipeline the raw mesh drew with. Submitting through
    //$$  * the render command queue instead was checked against 1.21.9 and 1.21.11 bytecode and
    //$$  * drops the geometry: vanilla drains that queue before the translucent sections render,
    //$$  * and the only later drain (the particles pass) only buffers, never flushes.
    //$$  */
    //$$ private void immediateBeamSide(
    //$$     final MatrixStack matrices,
    //$$     final float x0, final float z0,
    //$$     final float x1, final float z1,
    //$$     final float bottom, final float top,
    //$$     final int color
    //$$ ) {
    //$$     final VertexConsumer vertices = frame.immediate.getBuffer(beamLayer());
    //$$     emitBeamTriangle(matrices.peek(), vertices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
    //$$     emitBeamTriangle(matrices.peek(), vertices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
    //$$ }
    //$$
    //$$ /** One triangle written twice with opposite winding, the double-sided emission the immediate path uses. */
    //$$ private static void emitBeamTriangle(
    //$$     final MatrixStack.Entry entry,
    //$$     final VertexConsumer vertices,
    //$$     final float x0, final float y0, final float z0,
    //$$     final float x1, final float y1, final float z1,
    //$$     final float x2, final float y2, final float z2,
    //$$     final int argbColor
    //$$ ) {
    //$$     final float a = Argb.alpha(argbColor) / 255f;
    //$$     final float r = Argb.red(argbColor) / 255f;
    //$$     final float g = Argb.green(argbColor) / 255f;
    //$$     final float b = Argb.blue(argbColor) / 255f;
    //$$     vertices.vertex(entry, x0, y0, z0).color(r, g, b, a);
    //$$     vertices.vertex(entry, x1, y1, z1).color(r, g, b, a);
    //$$     vertices.vertex(entry, x2, y2, z2).color(r, g, b, a);
    //$$     vertices.vertex(entry, x2, y2, z2).color(r, g, b, a);
    //$$     vertices.vertex(entry, x1, y1, z1).color(r, g, b, a);
    //$$     vertices.vertex(entry, x0, y0, z0).color(r, g, b, a);
    //$$ }
    //#endif

    //#if MC>=260200
    //$$ private static void submitBeamSide(
    //$$     final FabricOrderedSubmitNodeCollector beamSubmits,
    //$$     final PoseStack.Pose pose,
    //$$     final float x0, final float z0,
    //$$     final float x1, final float z1,
    //$$     final float bottom, final float top,
    //$$     final int color
    //$$ ) {
    //$$     beamSubmits.submitCustom(SubmitRenderPhases.AFTER_TERRAIN, new CustomFeatureRenderer.Submit(
    //$$         pose, RenderTypes.dragonRays(),
    //$$         (transform, vertices) -> {
    //$$             emitBeamTriangle(transform, vertices, x0, bottom, z0, x1, bottom, z1, x1, top, z1, color);
    //$$             emitBeamTriangle(transform, vertices, x0, bottom, z0, x1, top, z1, x0, top, z0, color);
    //$$         }
    //$$     ));
    //$$ }
    //$$
    //$$ /** One triangle written twice with opposite winding, the double-sided emission the immediate path uses. */
    //$$ private static void emitBeamTriangle(
    //$$     final PoseStack.Pose pose,
    //$$     final VertexConsumer vertices,
    //$$     final float x0, final float y0, final float z0,
    //$$     final float x1, final float y1, final float z1,
    //$$     final float x2, final float y2, final float z2,
    //$$     final int color
    //$$ ) {
    //$$     vertices.addVertex(pose, x0, y0, z0).setColor(color);
    //$$     vertices.addVertex(pose, x1, y1, z1).setColor(color);
    //$$     vertices.addVertex(pose, x2, y2, z2).setColor(color);
    //$$     vertices.addVertex(pose, x2, y2, z2).setColor(color);
    //$$     vertices.addVertex(pose, x1, y1, z1).setColor(color);
    //$$     vertices.addVertex(pose, x0, y0, z0).setColor(color);
    //$$ }
    //#endif

    /**
     * Selects the flat position+color shader and additive-ish blending
     * ({@code src*alpha + dst*1}), for glow-style translucent 3D geometry like waypoint
     * beams (see {@code mc.ui.world.WaypointWorldRenderer}). Unlike {@code GuiShapes#fillTriangle}
     * and {@code GuiShapes#fillRect}, this does not reset itself on every draw call - callers issue
     * this once, draw as many {@link #fillTriangle3D} calls as needed, then restore normal
     * blending with {@link #restoreDefaultBlend()} when done.
     *
     * <p>On 1.21.6-1.21.8 the bracket also installs a fog slice that cannot fade the
     * geometry: the fog rework tightened the fog ranges and the lightning shader the beams
     * borrow multiplies the fog factor - it never blends toward the fog color - so a
     * distant full-height beam faded to zero everywhere. 1.21.5's single fog started far
     * enough to leave the beams bright; the no-fade slice restores that.
     */
    private static void beginAdditiveTriangles() {
        //#if MC<12105
        GuiShapes.useColorShader();
        RenderSystem.enableBlend();
        RenderSystem.blendFuncSeparate(
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.SRC_ALPHA,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.SrcFactor.ONE,
            com.mojang.blaze3d.platform.GlStateManager.DstFactor.ZERO
        );
        //#endif
        //#if MC>=12108 && MC<12109
        //$$ if (beamNoFadeFog == null) {
        //$$     beamNoFadeFog = RenderSystem.getDevice().createBuffer(
        //$$         () -> "Conflux Map waypoint no-fade fog",
        //$$         com.mojang.blaze3d.buffers.GpuBuffer.USAGE_UNIFORM
        //$$             | com.mojang.blaze3d.buffers.GpuBuffer.USAGE_COPY_DST,
        //$$         FogRenderer.FOG_UBO_SIZE
        //$$     );
        //$$     try (MemoryStack stack = MemoryStack.stackPush()) {
        //$$         final java.nio.ByteBuffer data = Std140Builder
        //$$             .onStack(stack, FogRenderer.FOG_UBO_SIZE)
        //$$             .putVec4(0f, 0f, 0f, 0f)
        //$$             .putFloat(Float.MAX_VALUE)
        //$$             .putFloat(Float.MAX_VALUE)
        //$$             .putFloat(Float.MAX_VALUE)
        //$$             .putFloat(Float.MAX_VALUE)
        //$$             .putFloat(Float.MAX_VALUE)
        //$$             .putFloat(Float.MAX_VALUE)
        //$$             .get();
        //$$         RenderSystem.getDevice().createCommandEncoder()
        //$$             .writeToBuffer(beamNoFadeFog.slice(), data);
        //$$     }
        //$$ }
        //$$ beamPreviousFog = RenderSystem.getShaderFog();
        //$$ RenderSystem.setShaderFog(beamNoFadeFog.slice(0, FogRenderer.FOG_UBO_SIZE));
        //#endif
    }

    /** Restores standard alpha blending after {@link #beginAdditiveTriangles()}. */
    private static void restoreDefaultBlend() {
        //#if MC<12105
        RenderSystem.defaultBlendFunc();
        //#endif
        //#if MC>=12108 && MC<12109
        //$$ RenderSystem.setShaderFog(beamPreviousFog);
        //$$ beamPreviousFog = null;
        //#endif
    }

    /**
     * Flat-colored filled triangle with three independent coordinates, for true 3D
     * world-space geometry drawn from a {@code WorldRenderEvents} callback (unlike
     * {@code GuiShapes#fillTriangle}, which always draws on the local matrix's Z=0 plane for flat
     * GUI shapes). Assumes the shader and blend state are already set up by the caller -
     * see {@link #beginAdditiveTriangles()}.
     */
    private static void fillTriangle3D(
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
        final Mesh mesh = Mesh.begin(Mesh.Mode.TRIANGLES, VertexFormats.POSITION_COLOR);
        mesh.vertex(model, x0, y0, z0).color(r, g, b, a).next();
        mesh.vertex(model, x1, y1, z1).color(r, g, b, a).next();
        mesh.vertex(model, x2, y2, z2).color(r, g, b, a).next();
        //#if MC>=12105
        //$$ mesh.vertex(model, x2, y2, z2).color(r, g, b, a).next();
        //$$ mesh.vertex(model, x1, y1, z1).color(r, g, b, a).next();
        //$$ mesh.vertex(model, x0, y0, z0).color(r, g, b, a).next();
        //$$ mesh.draw(RenderPipelines.RENDERTYPE_LIGHTNING_DRAGON_RAYS);
        //#else
        mesh.draw();
        //#endif
    }
    /**
     * The pass brackets restore every state they touch inline, so nothing here outlives
     * {@link #endPass}; close keeps the seam closeable as per-version native state moves
     * in behind it.
     */
    @Override
    public void close() {
    }
}
