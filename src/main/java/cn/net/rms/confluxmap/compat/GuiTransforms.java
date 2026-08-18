package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.core.config.HudAmbient;
//#if MC<12000
import com.mojang.blaze3d.systems.RenderSystem;
//#endif
//#if MC>=260100
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//#elseif MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#else
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.math.Vector4f;
//#endif

/**
 * Reads the axis-aligned GUI transform currently on the matrix stack.
 *
 * <p>HUD elements are drawn with integer coordinates that say nothing about a transform another
 * mod installed around them, so measuring an element's real size means asking the matrix what
 * those coordinates map to. The GUI pose carries no rotation in practice, so translation and
 * per-axis scale describe it completely.
 */
public final class GuiTransforms {
    private GuiTransforms() {
    }

    //#if MC>=260100
    //$$ public static HudAmbient ambient(final GuiGraphicsExtractor context) {
    //$$     final var pose = context.pose();
    //$$     return new HudAmbient(pose.m20(), pose.m21(), pose.m00(), pose.m11());
    //$$ }
    //#elseif MC>=12108
    //$$ public static HudAmbient ambient(final DrawContext context) {
    //$$     final var pose = context.getMatrices();
    //$$     return new HudAmbient(pose.m20(), pose.m21(), pose.m00(), pose.m11());
    //$$ }
    //#elseif MC>=12000
    //$$ public static HudAmbient ambient(final DrawContext context) {
    //$$     final var model = context.getMatrices().peek().getPositionMatrix();
    //$$     return new HudAmbient(model.m30(), model.m31(), model.m00(), model.m11());
    //$$ }
    //#else
    public static HudAmbient ambient(final MatrixStack matrices) {
        return ambient(matrices.peek().getModel());
    }

    /**
     * The model-view stack, which 1.17-era HUD code applies on top of the element's own stack.
     *
     * <p>Toast rendering pushes here rather than onto the passed stack, so this is the transform
     * that wraps it.
     */
    public static HudAmbient modelViewAmbient() {
        return ambient(RenderSystem.getModelViewStack().peek().getModel());
    }

    private static HudAmbient ambient(final net.minecraft.util.math.Matrix4f model) {
        final Vector4f origin = new Vector4f(0f, 0f, 0f, 1f);
        origin.transform(model);
        final Vector4f unit = new Vector4f(1f, 1f, 0f, 1f);
        unit.transform(model);
        return new HudAmbient(
            origin.getX(),
            origin.getY(),
            unit.getX() - origin.getX(),
            unit.getY() - origin.getY()
        );
    }
    //#endif
}
