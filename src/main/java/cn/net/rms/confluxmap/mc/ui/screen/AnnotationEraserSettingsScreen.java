package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.text.Text;

/** Non-pausing editor for the fullscreen annotation eraser diameter. */
public final class AnnotationEraserSettingsScreen extends ConfluxScreen {
    private static final int CONTROL_WIDTH = 240;

    private final Screen parent;
    private final ConfluxConfig config;

    public AnnotationEraserSettingsScreen(final Screen parent, final ConfluxConfig config) {
        super(Texts.translatable("confluxmap.screen.annotation.eraser.title"));
        this.parent = parent;
        this.config = config;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int controlWidth = Math.min(CONTROL_WIDTH, width - 24);
        final int left = width / 2 - controlWidth / 2;
        addDrawableChild(new EraserSizeSlider(left, 70, controlWidth, 20, config));
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            104,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            ignored -> saveAndReturn()
        ));
    }

    private void saveAndReturn() {
        ConfluxMapClient.get().configIo().save(config);
        MinecraftClient.getInstance().setScreen(parent);
    }

    @Override
    public void onClose() {
        saveAndReturn();
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final Text prompt = Texts.translatable("confluxmap.screen.annotation.eraser.prompt");
        draw.drawTextWithShadow(
            this.textRenderer,
            getTitle(),
            width / 2f - this.textRenderer.getWidth(getTitle()) / 2f,
            24,
            0xFFFFFFFF
        );
        draw.drawTextWithShadow(
            this.textRenderer,
            prompt,
            width / 2f - this.textRenderer.getWidth(prompt) / 2f,
            50,
            0xFFB8B8B8
        );
    }

    private static final class EraserSizeSlider extends SliderWidget {
        private final ConfluxConfig config;

        EraserSizeSlider(
            final int x,
            final int y,
            final int width,
            final int height,
            final ConfluxConfig config
        ) {
            super(
                x,
                y,
                width,
                height,
                Text.of(""),
                (config.annotationEraserSize - ConfluxConfig.MIN_ANNOTATION_ERASER_SIZE)
                    / (double) (
                        ConfluxConfig.MAX_ANNOTATION_ERASER_SIZE
                            - ConfluxConfig.MIN_ANNOTATION_ERASER_SIZE
                    )
            );
            this.config = config;
            updateMessage();
        }

        private int currentValue() {
            return ConfluxConfig.MIN_ANNOTATION_ERASER_SIZE + (int) Math.round(value * (
                ConfluxConfig.MAX_ANNOTATION_ERASER_SIZE - ConfluxConfig.MIN_ANNOTATION_ERASER_SIZE
            ));
        }

        @Override
        protected void updateMessage() {
            setMessage(Texts.translatable("confluxmap.screen.annotation.eraser.size", currentValue()));
        }

        @Override
        protected void applyValue() {
            config.annotationEraserSize = currentValue();
        }
    }
}
