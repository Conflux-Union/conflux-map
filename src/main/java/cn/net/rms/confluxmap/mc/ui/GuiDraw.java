package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.font.TextRenderer;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//$$ import net.minecraft.client.font.TextRenderer.TextLayerType;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

/** Version-neutral GUI draw state shared by screens, HUD callbacks, and marker renderers. */
public final class GuiDraw {
    //#if MC>=12000
    //$$ private final DrawContext context;
    //#else
    private final MatrixStack matrices;
    //#endif

    //#if MC>=12000
    //$$ private GuiDraw(final DrawContext context) {
    //$$     this.context = context;
    //$$ }
    //#else
    private GuiDraw(final MatrixStack matrices) {
        this.matrices = matrices;
    }
    //#endif

    //#if MC>=12000
    //$$ public static GuiDraw of(final DrawContext context) {
    //$$     return new GuiDraw(context);
    //$$ }
    //#else
    public static GuiDraw of(final MatrixStack matrices) {
        return new GuiDraw(matrices);
    }
    //#endif

    public MatrixStack matrices() {
        //#if MC>=12000
        //$$ return context.getMatrices();
        //#else
        return matrices;
        //#endif
    }

    public void renderBackground(
        final Screen screen,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        //#if MC>=12000
        //$$ screen.renderBackground(context, mouseX, mouseY, tickDelta);
        //#else
        screen.renderBackground(matrices);
        //#endif
    }

    public void drawTooltip(
        final Screen screen,
        final TextRenderer renderer,
        final Text text,
        final int mouseX,
        final int mouseY
    ) {
        //#if MC>=12000
        //$$ context.drawTooltip(renderer, text, mouseX, mouseY);
        //#else
        screen.renderTooltip(matrices, text, mouseX, mouseY);
        //#endif
    }

    public void fill(final int x1, final int y1, final int x2, final int y2, final int color) {
        RenderUtil.fillRect(matrices(), x1, y1, x2 - x1, y2 - y1, color);
    }

    //#if MC>=12000
    //$$ public DrawContext context() {
    //$$     return context;
    //$$ }
    //#endif

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final String text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=12000
        //$$ final int result = renderer.draw(
        //$$     text, x, y, color, true, matrices().peek().getPositionMatrix(),
        //$$     context.getVertexConsumers(), TextLayerType.NORMAL, 0, 15728880,
        //$$     renderer.isRightToLeft()
        //$$ );
        //$$ context.draw();
        //$$ return result;
        //#else
        return renderer.drawWithShadow(matrices, text, x, y, color);
        //#endif
    }

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final Text text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=12000
        //$$ final int result = renderer.draw(
        //$$     text, x, y, color, true, matrices().peek().getPositionMatrix(),
        //$$     context.getVertexConsumers(), TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ context.draw();
        //$$ return result;
        //#else
        return renderer.drawWithShadow(matrices, text, x, y, color);
        //#endif
    }

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final OrderedText text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=12000
        //$$ final int result = renderer.draw(
        //$$     text, x, y, color, true, matrices().peek().getPositionMatrix(),
        //$$     context.getVertexConsumers(), TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ context.draw();
        //$$ return result;
        //#else
        return renderer.drawWithShadow(matrices, text, x, y, color);
        //#endif
    }
}
