package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.GuiTransforms;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.HudAmbient;
import cn.net.rms.confluxmap.core.config.HudAvoidanceLayout;
import cn.net.rms.confluxmap.core.config.HudTransform;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.mc.ui.hud.VanillaStatusEffectLayout;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
//#if MC>=12100
//$$ import com.llamalad7.mixinextras.injector.wrapmethod.WrapMethod;
//$$ import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
//#endif
import net.minecraft.client.MinecraftClient;
//#if MC>=260200
//$$ import net.minecraft.client.gui.Hud;
//#elseif MC>=260100
//$$ import net.minecraft.client.gui.Gui;
//$$ import net.minecraft.client.gui.GuiGraphicsExtractor;
//$$ import net.minecraft.client.DeltaTracker;
//#else
import net.minecraft.client.gui.hud.InGameHud;
//#endif
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
//#if MC>=12100
//$$ import net.minecraft.client.render.RenderTickCounter;
//#elseif MC<12000
import net.minecraft.client.util.math.MatrixStack;
//#endif
//#if MC>=260100
//$$ import net.minecraft.world.effect.MobEffectInstance;
//#else
import net.minecraft.entity.effect.StatusEffectInstance;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
//#if MC<12100
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
//#endif

/** Moves vanilla status-effect icons while leaving the configured minimap position untouched. */
//#if MC>=260200
//$$ @Mixin(Hud.class)
//#elseif MC>=260100
//$$ @Mixin(Gui.class)
//#else
@Mixin(InGameHud.class)
//#endif
public abstract class StatusEffectHudMixin {
    //#if MC>=12100
    //$$ @WrapMethod(
        //#if MC>=260100
        //$$ method = "extractEffects"
        //#else
        //$$ method = "renderStatusEffectOverlay"
        //#endif
    //$$ )
    //#if MC>=260100
    //$$ private void confluxmap$renderStatusEffects(
    //$$     final GuiGraphicsExtractor context,
    //$$     final DeltaTracker tickCounter,
    //$$     final Operation<Void> original
    //$$ ) {
    //$$     final float shift = confluxmap$horizontalPush(
    //$$         GuiTransforms.ambient(context), context.guiWidth(), context.guiHeight()
    //$$     );
    //#else
    //$$ private void confluxmap$renderStatusEffects(
    //$$     final DrawContext context,
    //$$     final RenderTickCounter tickCounter,
    //$$     final Operation<Void> original
    //$$ ) {
    //$$     final float shift = confluxmap$horizontalPush(
    //$$         GuiTransforms.ambient(context),
    //$$         context.getScaledWindowWidth(),
    //$$         context.getScaledWindowHeight()
    //$$     );
    //#endif
    //$$     if (shift == 0f) {
    //$$         original.call(context, tickCounter);
    //$$         return;
    //$$     }
    //#if MC>=260100
    //$$     context.pose().pushMatrix();
    //$$     context.pose().translate(shift, 0);
    //#elseif MC>=12108
    //$$     context.getMatrices().pushMatrix();
    //$$     context.getMatrices().translate(shift, 0);
    //#else
    //$$     context.getMatrices().push();
    //$$     context.getMatrices().translate(shift, 0, 0);
    //#endif
    //$$     try {
    //$$         original.call(context, tickCounter);
    //$$     } finally {
    //#if MC>=260100
    //$$         context.pose().popMatrix();
    //#elseif MC>=12108
    //$$         context.getMatrices().popMatrix();
    //#else
    //$$         context.getMatrices().pop();
    //#endif
    //$$     }
    //$$ }
    //#else
    @Unique
    private boolean confluxmap$statusEffectsShifted;

    @Inject(
        //#if MC>=260100
        //$$ method = "extractEffects",
        //#else
        method = "renderStatusEffectOverlay",
        //#endif
        at = @At("HEAD")
    )
    //#if MC>=260100
    //$$ private void confluxmap$beforeStatusEffects(
    //$$     final GuiGraphicsExtractor context,
    //$$     final DeltaTracker tickCounter,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12100
    //$$ private void confluxmap$beforeStatusEffects(
    //$$     final DrawContext context,
    //$$     final RenderTickCounter tickCounter,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12000
    //$$ private void confluxmap$beforeStatusEffects(
    //$$     final DrawContext context,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#else
    private void confluxmap$beforeStatusEffects(final MatrixStack matrices, final CallbackInfo ci) {
    //#endif
        confluxmap$statusEffectsShifted = false;
        //#if MC>=260100
        //$$ final float shift = confluxmap$horizontalPush(
        //$$     GuiTransforms.ambient(context), context.guiWidth(), context.guiHeight()
        //$$ );
        //#elseif MC>=12000
        //$$ final float shift = confluxmap$horizontalPush(
        //$$     GuiTransforms.ambient(context),
        //$$     context.getScaledWindowWidth(),
        //$$     context.getScaledWindowHeight()
        //$$ );
        //#else
        final MinecraftClient client = MinecraftClient.getInstance();
        final float shift = confluxmap$horizontalPush(
            GuiTransforms.ambient(matrices),
            client.getWindow().getScaledWidth(),
            client.getWindow().getScaledHeight()
        );
        //#endif
        if (shift == 0f) {
            return;
        }
        //#if MC>=260100
        //$$ context.pose().pushMatrix();
        //$$ context.pose().translate(shift, 0);
        //#elseif MC>=12108
        //$$ context.getMatrices().pushMatrix();
        //$$ context.getMatrices().translate(shift, 0);
        //#elseif MC>=12000
        //$$ context.getMatrices().push();
        //$$ context.getMatrices().translate(shift, 0, 0);
        //#else
        matrices.push();
        matrices.translate(shift, 0, 0);
        //#endif
        confluxmap$statusEffectsShifted = true;
    }

    @Inject(
        //#if MC>=260100
        //$$ method = "extractEffects",
        //#else
        method = "renderStatusEffectOverlay",
        //#endif
        at = @At("RETURN")
    )
    //#if MC>=260100
    //$$ private void confluxmap$afterStatusEffects(
    //$$     final GuiGraphicsExtractor context,
    //$$     final DeltaTracker tickCounter,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12100
    //$$ private void confluxmap$afterStatusEffects(
    //$$     final DrawContext context,
    //$$     final RenderTickCounter tickCounter,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12000
    //$$ private void confluxmap$afterStatusEffects(
    //$$     final DrawContext context,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#else
    private void confluxmap$afterStatusEffects(final MatrixStack matrices, final CallbackInfo ci) {
    //#endif
        if (!confluxmap$statusEffectsShifted) {
            return;
        }
        //#if MC>=260100
        //$$ context.pose().popMatrix();
        //#elseif MC>=12108
        //$$ context.getMatrices().popMatrix();
        //#elseif MC>=12000
        //$$ context.getMatrices().pop();
        //#else
        matrices.pop();
        //#endif
        confluxmap$statusEffectsShifted = false;
    }
    //#endif

    /**
     * Returns the translation to push, in the coordinates of the matrix {@code ambient} sits on.
     *
     * <p>The row rectangles are mapped through {@code ambient} first, so an overlay another mod
     * rescaled is compared against the minimap at its rendered size, and the resulting shift is
     * rebased so that scale does not also stretch the shift itself.
     */
    @Unique
    private static float confluxmap$horizontalPush(
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
            MinecraftAccess.isContainerScreen(screen)
        )) {
            return 0f;
        }

        int beneficialCount = 0;
        int harmfulCount = 0;
        //#if MC>=260100
        //$$ for (final MobEffectInstance effect : client.player.getActiveEffects()) {
        //$$     if (!effect.showIcon()) {
        //$$         continue;
        //$$     }
        //$$     if (effect.getEffect().value().isBeneficial()) {
        //$$         beneficialCount++;
        //$$     } else {
        //$$         harmfulCount++;
        //$$     }
        //$$ }
        //#else
        for (final StatusEffectInstance effect : client.player.getStatusEffects()) {
            if (!effect.shouldShowIcon()) {
                continue;
            }
            //#if MC>=12100
            //$$ final boolean beneficial = effect.getEffectType().value().isBeneficial();
            //#else
            final boolean beneficial = effect.getEffectType().isBeneficial();
            //#endif
            if (beneficial) {
                beneficialCount++;
            } else {
                harmfulCount++;
            }
        }
        //#endif

        final MinimapPlacement.Layout configuredMinimap = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        final boolean demo = client.isDemo();
        final int shift = HudAvoidanceLayout.statusEffectShift(
            config.minimapHudAvoidance,
            configuredMinimap,
            ambient.apply(VanillaStatusEffectLayout.row(
                screenWidth, VanillaStatusEffectLayout.beneficialTop(demo), beneficialCount
            )),
            ambient.apply(VanillaStatusEffectLayout.row(
                screenWidth, VanillaStatusEffectLayout.harmfulTop(demo), harmfulCount
            ))
        );
        return HudTransform.ofHorizontalShift(shift).rebased(ambient).translateX();
    }
}
