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
    //$$     super.render(context, mouseX, mouseY, tickDelta);
    //$$     renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
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
