package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#else
import net.minecraft.client.util.math.MatrixStack;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Screen base that keeps the MatrixStack-to-DrawContext rewrite at one lifecycle seam. */
public abstract class ConfluxScreen extends Screen {
    //#if MC>=12000
    //$$ /** Screen.render owns the widget loop, but its implicit background must not cover renderContents. */
    //$$ private boolean renderingVanillaWidgets;
    //#endif

    protected ConfluxScreen(final Text title) {
        super(title);
    }

    //#if MC>=12000
    //$$ @Override
    //$$ public final void render(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     renderContents(draw, mouseX, mouseY, tickDelta);
    //$$     // Modern Screen.render invokes renderBackground before iterating its private widget list.
    //$$     renderingVanillaWidgets = true;
    //$$     try {
    //$$         super.render(context, mouseX, mouseY, tickDelta);
    //$$     } finally {
    //$$         renderingVanillaWidgets = false;
    //$$     }
    //$$     renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
    //$$ }
    //$$
    //$$ @Override
    //$$ public final void renderBackground(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     if (!renderingVanillaWidgets) {
    //$$         renderVanillaBackground(context, mouseX, mouseY, tickDelta);
    //$$     }
    //$$ }
    //$$
    //$$ protected void renderVanillaBackground(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float tickDelta
    //$$ ) {
    //$$     super.renderBackground(context, mouseX, mouseY, tickDelta);
    //$$ }
    //#else
    @Override
    public final void render(
        final MatrixStack matrices,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final GuiDraw draw = GuiDraw.of(matrices);
        renderContents(draw, mouseX, mouseY, tickDelta);
        super.render(matrices, mouseX, mouseY, tickDelta);
        renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
    }
    //#endif

    protected abstract void renderContents(GuiDraw draw, int mouseX, int mouseY, float tickDelta);

    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
    }
}
