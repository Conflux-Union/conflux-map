package cn.net.rms.confluxmap.mixin;

//#if MC<12000
import com.mojang.blaze3d.systems.RenderSystem;
//#endif
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudAvoidanceLayout;
import cn.net.rms.confluxmap.core.config.HudTransform;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapInformationLayout;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.mc.ui.hud.ToastHudBounds;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import net.minecraft.client.MinecraftClient;
//#if MC>=260100
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//$$ import net.minecraft.client.gui.components.toasts.ToastManager;
//#else
import net.minecraft.client.toast.ToastManager;
//#endif
//#if MC>=12000 && MC<260100
//$$ import net.minecraft.client.gui.DrawContext;
//#elseif MC<12000
import net.minecraft.client.util.math.MatrixStack;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Moves vanilla toast notifications below an overlapping minimap.
 *
 * <p>The stack is measured by {@link ToastEntryMixin} on the previous frame rather than assumed to
 * be one default-sized toast, so a wide or multi-line notification is placed with its real size.
 */
@Mixin(ToastManager.class)
public abstract class ToastHudMixin {
    @Unique
    private boolean confluxmap$toastsShifted;

    @Inject(
        //#if MC>=260100
        //$$ method = "extractRenderState",
        //#else
        method = "draw",
        //#endif
        at = @At("HEAD")
    )
    //#if MC>=260100
    //$$ private void confluxmap$beforeToasts(
    //$$     final GuiGraphicsExtractor context,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12000
    //$$ private void confluxmap$beforeToasts(final DrawContext context, final CallbackInfo ci) {
    //#else
    private void confluxmap$beforeToasts(final MatrixStack matrices, final CallbackInfo ci) {
    //#endif
        confluxmap$toastsShifted = false;
        //#if MC>=260100
        //$$ final int screenWidth = context.guiWidth();
        //$$ final int screenHeight = context.guiHeight();
        //$$ final HudAmbient ambient = GuiTransforms.ambient(context);
        //#elseif MC>=12000
        //$$ final int screenWidth = context.getScaledWindowWidth();
        //$$ final int screenHeight = context.getScaledWindowHeight();
        //$$ final HudAmbient ambient = GuiTransforms.ambient(context);
        //#else
        final MinecraftClient client = MinecraftClient.getInstance();
        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        // The shift is pushed onto the model-view stack below, so that is the transform it has to
        // be rebased against.
        final HudAmbient ambient = GuiTransforms.modelViewAmbient();
        //#endif
        ToastHudBounds.beginFrame(screenWidth, screenHeight);
        final float shift = confluxmap$verticalPush(ambient, screenWidth, screenHeight);
        if (shift == 0f) {
            return;
        }
        //#if MC>=260100
        //$$ context.pose().pushMatrix();
        //$$ context.pose().translate(0, shift);
        //#elseif MC>=12108
        //$$ context.getMatrices().pushMatrix();
        //$$ context.getMatrices().translate(0, shift);
        //#elseif MC>=12000
        //$$ context.getMatrices().push();
        //$$ context.getMatrices().translate(0, shift, 0);
        //#else
        final MatrixStack modelView = RenderSystem.getModelViewStack();
        modelView.push();
        modelView.translate(0, shift, 0);
        RenderSystem.applyModelViewMatrix();
        //#endif
        confluxmap$toastsShifted = true;
    }

    @Inject(
        //#if MC>=260100
        //$$ method = "extractRenderState",
        //#else
        method = "draw",
        //#endif
        at = @At("RETURN")
    )
    //#if MC>=260100
    //$$ private void confluxmap$afterToasts(
    //$$     final GuiGraphicsExtractor context,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12000
    //$$ private void confluxmap$afterToasts(final DrawContext context, final CallbackInfo ci) {
    //#else
    private void confluxmap$afterToasts(final MatrixStack matrices, final CallbackInfo ci) {
    //#endif
        if (!confluxmap$toastsShifted) {
            return;
        }
        //#if MC>=260100
        //$$ context.pose().popMatrix();
        //#elseif MC>=12108
        //$$ context.getMatrices().popMatrix();
        //#elseif MC>=12000
        //$$ context.getMatrices().pop();
        //#else
        final MatrixStack modelView = RenderSystem.getModelViewStack();
        modelView.pop();
        RenderSystem.applyModelViewMatrix();
        //#endif
        confluxmap$toastsShifted = false;
    }

    /** Returns the translation to push, in the coordinates of the matrix {@code ambient} sits on. */
    @Unique
    private static float confluxmap$verticalPush(
        final HudAmbient ambient,
        final int screenWidth,
        final int screenHeight
    ) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final MinecraftClient client = MinecraftClient.getInstance();
        if (app == null || client.player == null) {
            return 0f;
        }
        final ConfluxConfig config = app.config();
        if (!config.minimapHudAvoidance) {
            return 0f;
        }
        final var screen = MinecraftAccess.screen(client);
        if (!MinimapHudVisibility.shouldRender(
            config.minimapEnabled,
            app.gameBridge().session().active(),
            screen instanceof FullscreenMapScreen,
            MinecraftAccess.isContainerScreen(screen),
            MinecraftAccess.isFullDebugOverlayVisible(client)
        )) {
            return 0f;
        }

        final MinimapPlacement.Layout minimap = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        final int informationHeight = MinimapInformationLayout.height(
            config.showCoordinates, config.showBiome, config.showLayerIndicator
        );
        final int shift = HudAvoidanceLayout.toastShift(
            config.minimapHudAvoidance,
            screenHeight,
            minimap,
            informationHeight,
            ToastHudBounds.previousFrame(screenWidth, screenHeight)
        );
        final HudTransform transform = HudTransform.ofVerticalShift(shift);
        ToastHudBounds.recordAppliedTransform(transform);
        return transform.rebased(ambient).translateY();
    }
}
