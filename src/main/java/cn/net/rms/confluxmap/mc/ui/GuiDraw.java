package cn.net.rms.confluxmap.mc.ui;

import cn.net.rms.confluxmap.mc.render.RenderUtil;
import com.mojang.blaze3d.systems.RenderSystem;
//#if MC>=12108
//$$ import org.joml.Matrix3x2fStack;
//$$ import org.joml.Matrix4f;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//$$ import net.minecraft.client.font.TextRenderer.TextLayerType;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.item.ItemStack;
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
    //$$     RenderUtil.setGuiState(context.state);
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

    /** Draws the item's normal 16px GUI model, scaled and centered on one radar marker. */
    public void drawItemIcon(
        final MinecraftClient client,
        final ItemStack stack,
        final float centerX,
        final float centerY,
        final float size
    ) {
        final float scale = size / 16f;
        final float left = centerX - size / 2f;
        final float top = centerY - size / 2f;
        //#if MC>=12108
        //$$ final Matrix3x2fStack pose = context.getMatrices();
        //$$ pose.pushMatrix();
        //$$ try {
        //$$     pose.translate(left, top);
        //$$     pose.scale(scale, scale);
        //#if MC>=260100
        //$$     context.item(stack, 0, 0);
        //#else
        //$$     context.drawItem(stack, 0, 0);
        //#endif
        //$$ } finally {
        //$$     pose.popMatrix();
        //$$ }
        //#elseif MC>=12000
        //$$ matrices.push();
        //$$ try {
        //$$     matrices.translate(left, top, 0);
        //$$     matrices.scale(scale, scale, 1f);
        //$$     context.drawItem(stack, 0, 0);
        //$$ } finally {
        //$$     matrices.pop();
        //$$ }
        //#else
        final MatrixStack modelView = RenderSystem.getModelViewStack();
        modelView.push();
        try {
            modelView.translate(left, top, 0);
            modelView.scale(scale, scale, 1f);
            RenderSystem.applyModelViewMatrix();
            client.getItemRenderer().renderInGui(stack, 0, 0);
        } finally {
            modelView.pop();
            RenderSystem.applyModelViewMatrix();
        }
        //#endif
    }

    public void renderBackground(
        final Screen screen,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        //#if MC>=12106
        //$$ // 1.21.6 hoisted the background out of Screen.render into Screen.renderWithTooltip,
        //$$ // which already ran it before renderContents; drawing it again doubles applyBlur.
        //#elseif MC>=12002
        //$$ screen.renderBackground(context, mouseX, mouseY, tickDelta);
        //#elseif MC>=12000
        //$$ screen.renderBackground(context);
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
        //#if MC>=12000
        //$$ context.fill(x1, y1, x2, y2, color);
        //#else
        RenderUtil.fillRect(matrices(), x1, y1, x2 - x1, y2 - y1, color);
        //#endif
    }

    //#if MC>=12000
    //$$ public DrawContext context() {
    //$$     return context;
    //$$ }
    //#endif

    //#if MC>=12108
    //$$ /**
    //$$  * Runs {@code draw} with this mod's accumulated transform installed as the context's own 2D
    //$$  * pose, the glyph origin folded into it.
    //$$  *
    //$$  * <p>From 1.21.6 text is recorded rather than drawn, so it has to go through the context to
    //$$  * be layered against the rest of the GUI at all - and the context carries a 2D pose instead
    //$$  * of a MatrixStack, so a rotated or scaled caller (minimap markers, waypoint labels) would
    //$$  * otherwise lose its transform. Folding the origin into the pose also keeps the sub-pixel
    //$$  * placement that the context's integer coordinates drop.
    //$$  */
    //$$ private void withTextPose(final float x, final float y, final Runnable draw) {
    //$$     final Matrix3x2fStack pose = context.getMatrices();
    //$$     pose.pushMatrix();
    //$$     try {
    //$$         final Matrix4f model = matrices.peek().getPositionMatrix();
    //$$         pose.set(model.m00(), model.m01(), model.m10(), model.m11(), model.m30(), model.m31());
    //$$         pose.translate(x, y);
    //$$         draw.run();
    //$$     } finally {
    //$$         pose.popMatrix();
    //$$     }
    //$$ }
    //#endif

    public int drawTextWithShadow(
        final TextRenderer renderer,
        final String text,
        final float x,
        final float y,
        final int color
    ) {
        //#if MC>=260100
        //$$ withTextPose(x, y, () -> context.text(renderer, text, 0, 0, color));
        //$$ return (int) x + renderer.width(text);
        //#elseif MC>=12108
        //$$ withTextPose(x, y, () -> context.drawTextWithShadow(renderer, text, 0, 0, color));
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
        //#if MC>=260100
        //$$ withTextPose(x, y, () -> context.text(renderer, text, 0, 0, color));
        //$$ return (int) x + renderer.width(text);
        //#elseif MC>=12108
        //$$ withTextPose(x, y, () -> context.drawTextWithShadow(renderer, text, 0, 0, color));
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
        //#if MC>=260100
        //$$ withTextPose(x, y, () -> context.text(renderer, text, 0, 0, color));
        //$$ return (int) x + renderer.width(text);
        //#elseif MC>=12108
        //$$ withTextPose(x, y, () -> context.drawTextWithShadow(renderer, text, 0, 0, color));
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
