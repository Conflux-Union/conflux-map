package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.mc.ui.hud.ToastHudBounds;
//#if MC>=260100
//$$ import net.minecraft.client.gui.Font;
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//$$ import net.minecraft.client.gui.components.toasts.Toast;
//#else
import net.minecraft.client.toast.Toast;
//#if MC>=12103
//$$ import net.minecraft.client.font.TextRenderer;
//#else
import net.minecraft.client.toast.ToastManager;
//#endif
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#else
import net.minecraft.client.util.math.MatrixStack;
//#endif
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Measures each toast where the manager has already positioned it.
 *
 * <p>Toast width and height are per-toast in vanilla - a multi-line system toast is taller than
 * the default and a long one is wider - so the stack has to be measured rather than assumed. The
 * matrix at this point carries the manager's placement plus anything another mod installed, so
 * the toast's own {@code 0,0,width,height} box maps straight to screen pixels.
 */
//#if MC>=260100
//$$ @Mixin(targets = "net.minecraft.client.gui.components.toasts.ToastManager$ToastInstance")
//#else
@Mixin(targets = "net.minecraft.client.toast.ToastManager$Entry")
//#endif
public abstract class ToastEntryMixin {
    //#if MC>=260100
    //$$ @Redirect(
    //$$     method = "extractRenderState",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/gui/components/toasts/Toast;extractRenderState"
    //$$             + "(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
    //$$             + "Lnet/minecraft/client/gui/Font;J)V"
    //$$     )
    //$$ )
    //$$ private void confluxmap$measureToast(
    //$$     final Toast toast,
    //$$     final GuiGraphicsExtractor context,
    //$$     final Font font,
    //$$     final long time
    //$$ ) {
    //$$     ToastHudBounds.include(toast.width(), toast.height(), GuiTransforms.ambient(context));
    //$$     toast.extractRenderState(context, font, time);
    //$$ }
    //#elseif MC>=12103
    //$$ @Redirect(
    //$$     method = "draw",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/toast/Toast;draw"
    //$$             + "(Lnet/minecraft/client/gui/DrawContext;"
    //$$             + "Lnet/minecraft/client/font/TextRenderer;J)V"
    //$$     )
    //$$ )
    //$$ private void confluxmap$measureToast(
    //$$     final Toast toast,
    //$$     final DrawContext context,
    //$$     final TextRenderer textRenderer,
    //$$     final long time
    //$$ ) {
    //$$     ToastHudBounds.include(
    //$$         toast.getWidth(), toast.getHeight(), GuiTransforms.ambient(context)
    //$$     );
    //$$     toast.draw(context, textRenderer, time);
    //$$ }
    //#elseif MC>=12000
    //$$ @Redirect(
    //$$     method = "draw",
    //$$     at = @At(
    //$$         value = "INVOKE",
    //$$         target = "Lnet/minecraft/client/toast/Toast;draw"
    //$$             + "(Lnet/minecraft/client/gui/DrawContext;"
    //$$             + "Lnet/minecraft/client/toast/ToastManager;J)"
    //$$             + "Lnet/minecraft/client/toast/Toast$Visibility;"
    //$$     )
    //$$ )
    //$$ private Toast.Visibility confluxmap$measureToast(
    //$$     final Toast toast,
    //$$     final DrawContext context,
    //$$     final ToastManager manager,
    //$$     final long time
    //$$ ) {
    //$$     ToastHudBounds.include(
    //$$         toast.getWidth(), toast.getHeight(), GuiTransforms.ambient(context)
    //$$     );
    //$$     return toast.draw(context, manager, time);
    //$$ }
    //#else
    @Redirect(
        method = "draw",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/toast/Toast;draw"
                + "(Lnet/minecraft/client/util/math/MatrixStack;"
                + "Lnet/minecraft/client/toast/ToastManager;J)"
                + "Lnet/minecraft/client/toast/Toast$Visibility;"
        )
    )
    private Toast.Visibility confluxmap$measureToast(
        final Toast toast,
        final MatrixStack matrices,
        final ToastManager manager,
        final long time
    ) {
        // 1.17-era toasts are positioned on the model-view stack, so the element's own stack alone
        // would not say where the toast landed.
        final HudAmbient pose =
            GuiTransforms.ambient(matrices).then(GuiTransforms.modelViewAmbient());
        ToastHudBounds.include(toast.getWidth(), toast.getHeight(), pose);
        return toast.draw(matrices, manager, time);
    }
    //#endif
}
