package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.mc.ui.hud.ScoreboardHudBounds;
//#if MC>=260100
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//#if MC>=260200
//$$ import net.minecraft.client.gui.Hud;
//#else
//$$ import net.minecraft.client.gui.Gui;
//#endif
//#else
import net.minecraft.client.gui.hud.InGameHud;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#else
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.util.math.MatrixStack;
//#endif
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Observes vanilla's scoreboard background draws instead of duplicating its layout rules. This
 * keeps the minimap avoidance in step with score count, title width, number format, GUI scale, and
 * window size without moving the scoreboard itself.
 */
//#if MC>=260200
//$$ @Mixin(Hud.class)
//#elseif MC>=260100
//$$ @Mixin(Gui.class)
//#else
@Mixin(InGameHud.class)
//#endif
public abstract class InGameHudMixin {
    //#if MC>=260100
    //$$ @Inject(method = "extractRenderState", at = @At("HEAD"))
    //$$ private void confluxmap$beginHudFrame(final CallbackInfo ci) {
    //$$     ScoreboardHudBounds.beginFrame();
    //$$ }
    //#elseif MC<260100
    @Inject(method = "render", at = @At("HEAD"))
    private void confluxmap$beginHudFrame(final CallbackInfo ci) {
        ScoreboardHudBounds.beginFrame();
    }
    //#endif

    //#if MC>=260100
    //$$ @Redirect(
    //$$     method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
    //$$         + "Lnet/minecraft/world/scores/Objective;)V",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;fill(IIIII)V"
    //$$     )
    //$$ )
    //$$ private void confluxmap$captureScoreboardFill(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int x1,
    //$$     final int y1,
    //$$     final int x2,
    //$$     final int y2,
    //$$     final int color
    //$$ ) {
    //$$     ScoreboardHudBounds.include(x1, y1, x2, y2);
    //$$     context.fill(x1, y1, x2, y2, color);
    //$$ }
    //#elseif MC>=12000
    //$$ @Redirect(
    //$$     method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;"
    //$$         + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/gui/DrawContext;fill(IIIII)V"
    //$$     )
    //$$ )
    //$$ private void confluxmap$captureScoreboardFill(
    //$$     final DrawContext context,
    //$$     final int x1,
    //$$     final int y1,
    //$$     final int x2,
    //$$     final int y2,
    //$$     final int color
    //$$ ) {
    //$$     ScoreboardHudBounds.include(x1, y1, x2, y2);
    //$$     context.fill(x1, y1, x2, y2, color);
    //$$ }
    //#else
    @Redirect(
        method = "renderScoreboardSidebar(Lnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawableHelper;"
                + "fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"
        )
    )
    private static void confluxmap$captureScoreboardFill(
        final MatrixStack matrices,
        final int x1,
        final int y1,
        final int x2,
        final int y2,
        final int color
    ) {
        ScoreboardHudBounds.include(x1, y1, x2, y2);
        DrawableHelper.fill(matrices, x1, y1, x2, y2, color);
    }
    //#endif
}
