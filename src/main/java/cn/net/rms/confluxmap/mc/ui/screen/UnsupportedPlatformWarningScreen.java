package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.nativepredict.PlatformClassifier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;

/** Fullscreen warning shown before entering an officially unsupported client platform. */
public final class UnsupportedPlatformWarningScreen extends ConfluxScreen {
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int MUTED_TEXT_COLOR = 0xFFB8B8B8;
    private static final int WARNING_TEXT_COLOR = 0xFFFFAA55;
    private static final int MAX_TEXT_WIDTH = 420;

    private final Screen parent;
    private final PlatformClassifier.Result platform;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private boolean dismissPermanently;
    private ButtonWidget dismissButton;

    public UnsupportedPlatformWarningScreen(
        final Screen parent,
        final PlatformClassifier.Result platform,
        final ConfluxConfig config,
        final ConfigIo configIo
    ) {
        super(Texts.translatable("confluxmap.screen.unsupported_platform.title"));
        this.parent = parent;
        this.platform = platform;
        this.config = config;
        this.configIo = configIo;
    }

    @Override
    protected void init() {
        final int buttonWidth = Math.min(260, Math.max(120, width - 32));
        final int left = width / 2 - buttonWidth / 2;
        dismissButton = addDrawableChild(Widgets.button(
            left,
            height - 58,
            buttonWidth,
            20,
            Texts.literal(""),
            ignored -> {
                dismissPermanently = !dismissPermanently;
                updateDismissButton();
            }
        ));
        updateDismissButton();
        addDrawableChild(Widgets.button(
            left,
            height - 32,
            buttonWidth,
            20,
            Texts.translatable("confluxmap.screen.unsupported_platform.continue"),
            ignored -> onClose()
        ));
        setEnterAction(() -> true, this::onClose);
    }

    private void updateDismissButton() {
        if (dismissButton != null) {
            dismissButton.setMessage(Texts.translatable(
                "confluxmap.screen.unsupported_platform.dismiss",
                dismissPermanently ? "[x]" : "[ ]"
            ));
        }
    }

    @Override
    public void onClose() {
        if (dismissPermanently && !config.unsupportedPlatformWarningDismissed) {
            config.unsupportedPlatformWarningDismissed = true;
            configIo.save(config);
        }
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 20, WARNING_TEXT_COLOR);

        int y = 46;
        y = drawTranslated(draw, "confluxmap.screen.unsupported_platform.scope", y, TEXT_COLOR);
        y = drawTranslated(
            draw,
            "confluxmap.screen.unsupported_platform.native",
            y + 5,
            WARNING_TEXT_COLOR
        );
        y = drawTranslated(
            draw,
            "confluxmap.screen.unsupported_platform.unverified",
            y + 5,
            TEXT_COLOR
        );
        y = drawWrapped(
            draw,
            Texts.translatable(
                "confluxmap.screen.unsupported_platform.detected",
                platform.displayName()
            ).getString(),
            y + 8,
            MUTED_TEXT_COLOR
        );
        y = drawTranslated(
            draw,
            "confluxmap.screen.unsupported_platform.supported",
            y + 5,
            MUTED_TEXT_COLOR
        );
        for (final String supported : PlatformClassifier.OFFICIALLY_SUPPORTED_PLATFORMS) {
            y = drawWrapped(draw, supported, y, MUTED_TEXT_COLOR);
        }
    }

    private int drawTranslated(
        final GuiDraw draw,
        final String translationKey,
        final int y,
        final int color
    ) {
        return drawWrapped(draw, Texts.translatable(translationKey).getString(), y, color);
    }

    private int drawWrapped(
        final GuiDraw draw,
        final String value,
        final int y,
        final int color
    ) {
        int lineY = y;
        final int textWidth = Math.min(MAX_TEXT_WIDTH, Math.max(80, width - 32));
        for (final OrderedText line : this.textRenderer.wrapLines(
            StringVisitable.plain(value),
            textWidth
        )) {
            draw.drawTextWithShadow(
                this.textRenderer,
                line,
                width / 2f - this.textRenderer.getWidth(line) / 2f,
                lineY,
                color
            );
            lineY += this.textRenderer.fontHeight + 1;
        }
        return lineY;
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = this.textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(
            this.textRenderer,
            text,
            width / 2f - this.textRenderer.getWidth(text) / 2f,
            y,
            color
        );
    }
}
