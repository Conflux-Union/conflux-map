package cn.net.rms.confluxmap.mixin;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.HudAvoidanceLayout;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.core.config.ScoreboardHudAvoidance;
import cn.net.rms.confluxmap.mc.ui.hud.ScoreboardHudBounds;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
//#if MC>=260100
//$$ import net.minecraft.client.Minecraft;
//#else
import net.minecraft.client.MinecraftClient;
//#endif
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
//#if MC>=260100
//$$ import net.minecraft.world.scores.Objective;
//#else
import net.minecraft.scoreboard.ScoreboardObjective;
//#endif
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Measures vanilla's scoreboard without duplicating its layout rules, then moves the complete
 * sidebar away from the configured minimap on the following HUD frame.
 */
//#if MC>=260200
//$$ @Mixin(Hud.class)
//#elseif MC>=260100
//$$ @Mixin(Gui.class)
//#else
@Mixin(InGameHud.class)
//#endif
public abstract class InGameHudMixin {
    @Unique
    private boolean confluxmap$scoreboardTransformed;

    //#if MC>=260100
    //$$ @Inject(method = "extractRenderState", at = @At("HEAD"))
    //$$ private void confluxmap$beginHudFrame(final CallbackInfo ci) {
    //$$     final Minecraft client = Minecraft.getInstance();
    //$$     ScoreboardHudBounds.beginFrame(
    //$$         client.getWindow().getGuiScaledWidth(), client.getWindow().getGuiScaledHeight()
    //$$     );
    //$$ }
    //#elseif MC<260100
    @Inject(method = "render", at = @At("HEAD"))
    private void confluxmap$beginHudFrame(final CallbackInfo ci) {
        final MinecraftClient client = MinecraftClient.getInstance();
        ScoreboardHudBounds.beginFrame(
            client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight()
        );
    }
    //#endif

    @Inject(
        //#if MC>=260100
        //$$ method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
        //$$     + "Lnet/minecraft/world/scores/Objective;)V",
        //#elseif MC>=12000
        //$$ method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;"
        //$$     + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        //#else
        method = "renderScoreboardSidebar(Lnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        //#endif
        at = @At("HEAD")
    )
    //#if MC>=260100
    //$$ private void confluxmap$beforeScoreboard(
    //$$     final GuiGraphicsExtractor context,
    //$$     final Objective objective,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12000
    //$$ private void confluxmap$beforeScoreboard(
    //$$     final DrawContext context,
    //$$     final ScoreboardObjective objective,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#else
    private void confluxmap$beforeScoreboard(
        final MatrixStack matrices,
        final ScoreboardObjective objective,
        final CallbackInfo ci
    ) {
    //#endif
        confluxmap$scoreboardTransformed = false;
        //#if MC>=260100
        //$$ final ScoreboardHudAvoidance.Transform transform =
        //$$     confluxmap$scoreboardTransform(context.guiWidth(), context.guiHeight());
        //#elseif MC>=12000
        //$$ final ScoreboardHudAvoidance.Transform transform = confluxmap$scoreboardTransform(
        //$$     context.getScaledWindowWidth(), context.getScaledWindowHeight()
        //$$ );
        //#else
        final MinecraftClient client = MinecraftClient.getInstance();
        final ScoreboardHudAvoidance.Transform transform = confluxmap$scoreboardTransform(
            client.getWindow().getScaledWidth(), client.getWindow().getScaledHeight()
        );
        //#endif
        if (transform.isIdentity()) {
            ScoreboardHudBounds.recordAppliedTransform(ScoreboardHudAvoidance.Transform.IDENTITY);
            return;
        }
        //#if MC>=260100
        //$$ context.pose().pushMatrix();
        //$$ context.pose().translate(transform.translateX(), transform.translateY());
        //$$ context.pose().scale(transform.scale(), transform.scale());
        //#elseif MC>=12108
        //$$ context.getMatrices().pushMatrix();
        //$$ context.getMatrices().translate(transform.translateX(), transform.translateY());
        //$$ context.getMatrices().scale(transform.scale(), transform.scale());
        //#elseif MC>=12000
        //$$ context.getMatrices().push();
        //$$ context.getMatrices().translate(transform.translateX(), transform.translateY(), 0);
        //$$ context.getMatrices().scale(transform.scale(), transform.scale(), 1f);
        //#else
        matrices.push();
        matrices.translate(transform.translateX(), transform.translateY(), 0);
        matrices.scale(transform.scale(), transform.scale(), 1f);
        //#endif
        ScoreboardHudBounds.recordAppliedTransform(transform);
        confluxmap$scoreboardTransformed = true;
    }

    @Inject(
        //#if MC>=260100
        //$$ method = "displayScoreboardSidebar(Lnet/minecraft/client/gui/GuiGraphicsExtractor;"
        //$$     + "Lnet/minecraft/world/scores/Objective;)V",
        //#elseif MC>=12000
        //$$ method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;"
        //$$     + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        //#else
        method = "renderScoreboardSidebar(Lnet/minecraft/client/util/math/MatrixStack;"
            + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        //#endif
        at = @At("RETURN")
    )
    //#if MC>=260100
    //$$ private void confluxmap$afterScoreboard(
    //$$     final GuiGraphicsExtractor context,
    //$$     final Objective objective,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#elseif MC>=12000
    //$$ private void confluxmap$afterScoreboard(
    //$$     final DrawContext context,
    //$$     final ScoreboardObjective objective,
    //$$     final CallbackInfo ci
    //$$ ) {
    //#else
    private void confluxmap$afterScoreboard(
        final MatrixStack matrices,
        final ScoreboardObjective objective,
        final CallbackInfo ci
    ) {
    //#endif
        if (!confluxmap$scoreboardTransformed) {
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
        confluxmap$scoreboardTransformed = false;
    }

    @Unique
    private static ScoreboardHudAvoidance.Transform confluxmap$scoreboardTransform(
        final int screenWidth,
        final int screenHeight
    ) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final MinecraftClient client = MinecraftClient.getInstance();
        if (app == null || client.player == null) {
            return ScoreboardHudAvoidance.Transform.IDENTITY;
        }
        final ConfluxConfig config = app.config();
        final var screen = MinecraftAccess.screen(client);
        if (!MinimapHudVisibility.shouldRender(
            config.minimapEnabled,
            app.gameBridge().session().active(),
            screen instanceof FullscreenMapScreen,
            MinecraftAccess.isContainerScreen(screen)
        )) {
            return ScoreboardHudAvoidance.Transform.IDENTITY;
        }

        final MinimapPlacement.Layout minimap = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        return HudAvoidanceLayout.scoreboardTransform(
            config.minimapHudAvoidance,
            screenHeight,
            minimap,
            HudAvoidanceLayout.informationHeight(
                config.showCoordinates,
                config.showBiome,
                config.showLayerIndicator
            ),
            ScoreboardHudBounds.previousFrame(screenWidth, screenHeight)
        );
    }

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
        //#if MC>=12101 && MC<12103
        //$$ method = "method_55440([Lnet/minecraft/client/gui/hud/InGameHud$SidebarEntry;"
        //$$     + "Lnet/minecraft/client/gui/DrawContext;ILnet/minecraft/text/Text;I)V",
        //#else
        //$$ method = "renderScoreboardSidebar(Lnet/minecraft/client/gui/DrawContext;"
        //$$     + "Lnet/minecraft/scoreboard/ScoreboardObjective;)V",
        //#endif
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
            target = "Lnet/minecraft/client/gui/hud/InGameHud;"
                + "fill(Lnet/minecraft/client/util/math/MatrixStack;IIIII)V"
        )
    )
    private void confluxmap$captureScoreboardFill(
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
