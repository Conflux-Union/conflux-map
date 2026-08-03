package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationStore;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Small non-pausing editor for the optional text attached to one annotation. */
public final class AnnotationLabelScreen extends ConfluxScreen {
    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;

    private final Screen parent;
    private final AnnotationStore store;
    private final UUID annotationId;
    private final String initialLabel;
    private TextFieldWidget labelField;

    public AnnotationLabelScreen(
        final Screen parent,
        final AnnotationStore store,
        final Annotation annotation
    ) {
        super(Texts.translatable("confluxmap.screen.annotation.label.title"));
        this.parent = parent;
        this.store = store;
        this.annotationId = annotation.id();
        this.initialLabel = annotation.label();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int fieldWidth = Math.min(FIELD_WIDTH, width - 24);
        labelField = new TextFieldWidget(
            this.textRenderer,
            width / 2 - fieldWidth / 2,
            70,
            fieldWidth,
            FIELD_HEIGHT,
            Texts.literal("")
        );
        labelField.setMaxLength(Annotation.MAX_LABEL_LENGTH);
        labelField.setText(initialLabel);
        addDrawableChild(labelField);
        setInitialFocus(labelField);

        addDrawableChild(Widgets.button(
            width / 2 - 104,
            104,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            ignored -> saveAndReturn()
        ));
        setEnterAction(() -> true, this::saveAndReturn);
        addDrawableChild(Widgets.button(
            width / 2 + 4,
            104,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            ignored -> returnToParent()
        ));
    }

    private void saveAndReturn() {
        store.get(annotationId).ifPresent(annotation -> store.update(annotation.withLabel(labelField.getText())));
        returnToParent();
    }

    private void returnToParent() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public void onClose() {
        returnToParent();
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        draw.drawTextWithShadow(
            this.textRenderer,
            Texts.translatable("confluxmap.screen.annotation.label.prompt"),
            width / 2f - this.textRenderer.getWidth(
                Texts.translatable("confluxmap.screen.annotation.label.prompt")
            ) / 2f,
            50,
            0xFFFFFFFF
        );
    }
}
