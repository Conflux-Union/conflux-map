package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;

/** Non-pausing editor for the fullscreen annotation eraser diameter. */
public final class AnnotationEraserSettingsScreen extends ConfluxScreen {
    private static final int CONTROL_WIDTH = 240;

    private final Screen parent;
    private final ConfluxConfig config;
    private IntSliderInput eraserSizeInput;

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
        eraserSizeInput = new IntSliderInput(
            this.textRenderer,
            left,
            70,
            controlWidth,
            20,
            ConfluxConfig.MIN_ANNOTATION_ERASER_SIZE,
            ConfluxConfig.MAX_ANNOTATION_ERASER_SIZE,
            config.annotationEraserSize,
            value -> {
                config.annotationEraserSize = value;
                ConfluxMapClient.get().configIo().save(config);
            },
            value -> Texts.translatable("confluxmap.screen.annotation.eraser.size", value)
        );
        addDrawableChild(eraserSizeInput.slider());
        addDrawableChild(eraserSizeInput.input());
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            104,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            ignored -> saveAndReturn()
        ));
        setEnterAction(() -> true, this::saveAndReturn);
    }

    private void saveAndReturn() {
        ConfluxMapClient.get().configIo().save(config);
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public void onClose() {
        saveAndReturn();
    }

    @Override
    public void tick() {
        super.tick();
        if (eraserSizeInput != null) {
            eraserSizeInput.tick();
        }
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

}
