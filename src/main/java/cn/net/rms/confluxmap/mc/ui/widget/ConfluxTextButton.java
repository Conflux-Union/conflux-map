package cn.net.rms.confluxmap.mc.ui.widget;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.UiIcon;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.UiTextureRegion;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Identifier;

/** Shared text or icon button that keeps the Conflux style unless a text control is reskinned. */
public final class ConfluxTextButton extends ButtonWidget {
    private static final int ICON_SIZE = 16;
    private static final int BACKGROUND = 0xE0181818;
    private static final int HOVER_BACKGROUND = 0xF02A2A2A;
    private static final int DISABLED_BACKGROUND = 0xD0121212;
    private static final int BORDER = 0xFF8A8A8A;
    private static final int HOVER_BORDER = 0xFFFFFFFF;
    private static final int DISABLED_BORDER = 0xFF4A4A4A;
    private static final int TEXT = 0xFFFFFFFF;
    private static final int DISABLED_TEXT = 0xFF777777;
    private final Identifier icon;

    public ConfluxTextButton(
        final int x,
        final int y,
        final int width,
        final int height,
        final net.minecraft.text.Text message,
        final PressAction onPress
    ) {
        this(x, y, width, height, message, null, onPress);
    }

    public ConfluxTextButton(
        final int x,
        final int y,
        final int width,
        final int height,
        final net.minecraft.text.Text message,
        final Identifier icon,
        final PressAction onPress
    ) {
        //#if MC>=12111
        //$$ super(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
        //#elseif MC>=11904
        //$$ super(x, y, width, height, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
        //#else
        super(x, y, width, height, message, onPress);
        //#endif
        this.icon = icon;
    }

    @Override
    //#if MC>=260100
    //$$ protected void extractContents(
    //$$     final GuiGraphicsExtractor context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     if (useVanillaButtonStyle()) {
    //$$         extractDefaultSprite(context);
    //$$         drawForeground(GuiDraw.of(context));
    //$$         return;
    //$$     }
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#elseif MC>=12111
    //$$ protected void drawIcon(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     if (useVanillaButtonStyle()) {
    //$$         drawButton(context);
    //$$         drawForeground(GuiDraw.of(context));
    //$$         return;
    //$$     }
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#elseif MC>=12002
    //$$ protected void renderWidget(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     if (useVanillaButtonStyle()) {
    //$$         if (icon == null) {
    //$$             super.renderWidget(context, mouseX, mouseY, delta);
    //$$         } else {
    //$$             renderWithoutMessage(() -> super.renderWidget(context, mouseX, mouseY, delta));
    //$$             drawIcon(GuiDraw.of(context));
    //$$         }
    //$$         return;
    //$$     }
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#elseif MC>=12000
    //$$ protected void renderButton(
    //$$     final DrawContext context,
    //$$     final int mouseX,
    //$$     final int mouseY,
    //$$     final float delta
    //$$ ) {
    //$$     if (useVanillaButtonStyle()) {
    //$$         if (icon == null) {
    //$$             super.renderButton(context, mouseX, mouseY, delta);
    //$$         } else {
    //$$             renderWithoutMessage(() -> super.renderButton(context, mouseX, mouseY, delta));
    //$$             drawIcon(GuiDraw.of(context));
    //$$         }
    //$$         return;
    //$$     }
    //$$     drawContents(GuiDraw.of(context));
    //$$ }
    //#else
    public void renderButton(
        final MatrixStack matrices,
        final int mouseX,
        final int mouseY,
        final float delta
    ) {
        if (useVanillaButtonStyle()) {
            if (icon == null) {
                super.renderButton(matrices, mouseX, mouseY, delta);
            } else {
                renderWithoutMessage(() -> super.renderButton(matrices, mouseX, mouseY, delta));
                drawIcon(GuiDraw.of(matrices));
            }
            return;
        }
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

        drawForeground(draw);
    }

    private void drawForeground(final GuiDraw draw) {
        if (icon == null) {
            drawText(draw);
        } else {
            drawIcon(draw);
        }
    }

    private void renderWithoutMessage(final Runnable render) {
        final net.minecraft.text.Text message = getMessage();
        setMessage(Texts.literal(""));
        try {
            render.run();
        } finally {
            setMessage(message);
        }
    }

    private void drawIcon(final GuiDraw draw) {
        final ConfluxMapClient app = ConfluxMapClient.get();
        final UiResourceTheme theme = app == null ? null : app.uiResourceTheme();
        final UiIcon resolved = theme == null ? UiIcon.monochrome(icon) : theme.icon(icon);
        final UiTextureRegion texture = resolved.region();
        RenderUtil.bindTexture(MinecraftClient.getInstance(), texture.texture());
        RenderUtil.drawTintedQuad(
            draw.matrices(),
            Widgets.x(this) + (getWidth() - ICON_SIZE) / 2,
            Widgets.y(this) + (getHeight() - ICON_SIZE) / 2,
            ICON_SIZE,
            ICON_SIZE,
            texture.u0(),
            texture.v0(),
            texture.u1(),
            texture.v1(),
            active ? TEXT : DISABLED_TEXT
        );
    }

    private void drawText(final GuiDraw draw) {
        final int x = Widgets.x(this);
        final int y = Widgets.y(this);
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

    private static boolean useVanillaButtonStyle() {
        final ConfluxMapClient app = ConfluxMapClient.get();
        return app != null && app.uiResourceTheme() != null
            && app.uiResourceTheme().useVanillaButtonStyle();
    }
}
