package cn.net.rms.confluxmap.mc.ui.widget;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Widgets;
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;

/** High-contrast button shared by every Conflux screen. */
public final class ConfluxTextButton extends ButtonWidget {
    private static final int BACKGROUND = 0xE0181818;
    private static final int HOVER_BACKGROUND = 0xF02A2A2A;
    private static final int DISABLED_BACKGROUND = 0xD0121212;
    private static final int BORDER = 0xFF8A8A8A;
    private static final int HOVER_BORDER = 0xFFFFFFFF;
    private static final int DISABLED_BORDER = 0xFF4A4A4A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DISABLED_TEXT = 0xFF777777;

    public ConfluxTextButton(
        final int x,
        final int y,
        final int width,
        final int height,
        final net.minecraft.text.Text message,
        final PressAction onPress
    ) {
        //#if MC>=12111
        //$$ super(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
        //#elseif MC>=11904
        //$$ super(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
        //#else
        super(x, y, width, height, message, onPress);
        //#endif
    }

    @Override
    //#if MC>=260100
    //$$ protected void extractContents(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#elseif MC>=12111
    //$$ protected void drawIcon(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#elseif MC>=12002
    //$$ protected void renderWidget(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#elseif MC>=12000
    //$$ protected void renderButton(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#else
    public void renderButton(
        final MatrixStack matrices,
        final int mouseX,
        final int mouseY,
        final float delta
    ) {
        drawContents(GuiDraw.of(matrices));
    }
    //#endif

    private void drawContents(final GuiDraw draw) {
        final int x = Widgets.x(this);
        final int y = Widgets.y(this);
        final int right = x + getWidth();
        final int bottom = y + getHeight();
        final boolean highlighted = active && (isHovered() || isFocused());
        final int background = !active
            ? DISABLED_BACKGROUND
            : highlighted ? HOVER_BACKGROUND : BACKGROUND;
        final int border = !active ? DISABLED_BORDER : highlighted ? HOVER_BORDER : BORDER;
        draw.fill(x, y, right, bottom, background);
        draw.fill(x, y, right, y + 1, border);
        draw.fill(x, bottom - 1, right, bottom, border);
        draw.fill(x, y, x + 1, bottom, border);
        draw.fill(right - 1, y, right, bottom, border);

        final MinecraftClient client = MinecraftClient.getInstance();
        final net.minecraft.text.Text message = getMessage();
        final int availableWidth = Math.max(1, getWidth() - 8);
        //#if MC>=260100
        //$$ final String fitted = client.font.plainSubstrByWidth(message.getString(), availableWidth);
        //$$ final int textWidth = client.font.width(fitted);
        //$$ final int fontHeight = client.font.lineHeight;
        //$$ draw.drawTextWithShadow(
        //$$     client.font,
        //$$     fitted,
        //$$     x + (getWidth() - textWidth) / 2f,
        //$$     y + (getHeight() - fontHeight) / 2f,
        //$$     active ? TEXT : DISABLED_TEXT
        //$$ );
        //#else
        final String fitted = client.textRenderer.trimToWidth(message.getString(), availableWidth);
        final int textWidth = client.textRenderer.getWidth(fitted);
        draw.drawTextWithShadow(
            client.textRenderer,
            fitted,
            x + (getWidth() - textWidth) / 2f,
            y + (getHeight() - client.textRenderer.fontHeight) / 2f,
            active ? TEXT : DISABLED_TEXT
        );
        //#endif
    }
}
