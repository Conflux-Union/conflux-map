package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
//#if MC>=12108
//$$ import net.minecraft.client.MinecraftClient;
//$$ import net.minecraft.client.render.VertexConsumerProvider;
//#endif
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
    private final MatrixStack matrices;
    //#if MC>=12000
    //$$ private final DrawContext context;
    //#endif

    //#if MC>=12000
    //$$ private GuiDraw(final DrawContext context) {
    //$$     this.context = context;
    //#if MC>=12108
    //$$     this.matrices = new MatrixStack();
    //$$     final var source = context.getMatrices();
    //$$     this.matrices.peek().getPositionMatrix()
    //$$         .m00(source.m00()).m01(source.m01())
    //$$         .m10(source.m10()).m11(source.m11())
    //$$         .m30(source.m20()).m31(source.m21());
    //#else
    //$$     this.matrices = context.getMatrices();
    //#endif
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
        return matrices;
    }

    public void renderBackground(
        final Screen screen,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        //#if MC>=12111
        // Screen.renderWithTooltip renders the background before invoking Screen.render.
        //#elseif MC>=12000
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
        //#if MC>=12108
        //$$ final VertexConsumerProvider.Immediate immediate = MinecraftClient.getInstance()
        //$$     .getBufferBuilders().getEntityVertexConsumers();
        //$$ renderer.draw(
        //$$     text, x, y, color, true, matrices.peek().getPositionMatrix(),
        //$$     immediate, TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ immediate.draw();
        //$$ return (int) x + renderer.getWidth(text);
        //#elseif MC>=12103
        //$$ final int[] result = {0};
        //$$ context.draw(vertexConsumers -> result[0] = renderer.draw(
        //$$     text, x, y, color, true, matrices().peek().getPositionMatrix(),
        //$$     vertexConsumers, TextLayerType.NORMAL, 0, 15728880
        //$$ ));
        //$$ return result[0];
        //#elseif MC>=12000
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
        //#if MC>=12108
        //$$ final VertexConsumerProvider.Immediate immediate = MinecraftClient.getInstance()
        //$$     .getBufferBuilders().getEntityVertexConsumers();
        //$$ renderer.draw(
        //$$     text, x, y, color, true, matrices.peek().getPositionMatrix(),
        //$$     immediate, TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ immediate.draw();
        //$$ return (int) x + renderer.getWidth(text);
        //#elseif MC>=12103
        //$$ final int[] result = {0};
        //$$ context.draw(vertexConsumers -> result[0] = renderer.draw(
        //$$     text, x, y, color, true, matrices().peek().getPositionMatrix(),
        //$$     vertexConsumers, TextLayerType.NORMAL, 0, 15728880
        //$$ ));
        //$$ return result[0];
        //#elseif MC>=12000
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
        //#if MC>=12108
        //$$ final VertexConsumerProvider.Immediate immediate = MinecraftClient.getInstance()
        //$$     .getBufferBuilders().getEntityVertexConsumers();
        //$$ renderer.draw(
        //$$     text, x, y, color, true, matrices.peek().getPositionMatrix(),
        //$$     immediate, TextLayerType.NORMAL, 0, 15728880
        //$$ );
        //$$ immediate.draw();
        //$$ return (int) x + renderer.getWidth(text);
        //#elseif MC>=12103
        //$$ final int[] result = {0};
        //$$ context.draw(vertexConsumers -> result[0] = renderer.draw(
        //$$     text, x, y, color, true, matrices().peek().getPositionMatrix(),
        //$$     vertexConsumers, TextLayerType.NORMAL, 0, 15728880
        //$$ ));
        //$$ return result[0];
        //#elseif MC>=12000
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
