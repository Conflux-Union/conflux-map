package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.render.WorldMatrices;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Copies the world pass's projection matrix out at the exact call that installs it, without
 * drawing anything (the Xaero capture pattern). From 1.21.2 on, vanilla bakes the nausea
 * sway, view bobbing, and hurt tilt into that projection and keeps only an opaque GPU buffer
 * slice in RenderSystem, so this install call is the only place the plain matrix is
 * reachable; the world-pass hooks then pair it with the camera view for the HUD-phase item
 * labels. On the pre-1.21.2 lines the projection is plain (the sway lives in the view, which
 * the world-pass hooks already snapshot), so those lines register no handler and this mixin
 * is empty there.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {
    //#if MC>=12109 && MC<260100
    //$$ @ModifyArg(
    //$$     method = "renderWorld",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/render/RawProjectionMatrix;set(Lorg/joml/Matrix4f;)"
    //$$             + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
    //$$     )
    //$$ )
    //$$ private org.joml.Matrix4f confluxmap$captureSwayProjectionWorld(
    //$$     final org.joml.Matrix4f projection
    //$$ ) {
    //$$     WorldMatrices.captureHudProjection(projection);
    //$$     return projection;
    //$$ }
    //#elseif MC>=260100 && MC<260300
    //$$ @ModifyArg(
    //$$     method = "renderLevel",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)"
    //$$             + "Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"
    //$$     )
    //$$ )
    //$$ private org.joml.Matrix4f confluxmap$captureSwayProjectionLevel(
    //$$     final org.joml.Matrix4f projection
    //$$ ) {
    //$$     WorldMatrices.captureHudProjection(projection);
    //$$     return projection;
    //$$ }
    //#elseif MC>=260300
    //$$ @ModifyArg(
    //$$     method = "renderLevel",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)"
    //$$             + "Lcom/mojang/renderpearl/api/buffers/GpuBufferSlice;"
    //$$     )
    //$$ )
    //$$ private org.joml.Matrix4f confluxmap$captureSwayProjectionLevel(
    //$$     final org.joml.Matrix4f projection
    //$$ ) {
    //$$     WorldMatrices.captureHudProjection(projection);
    //$$     return projection;
    //$$ }
    //#endif
}
