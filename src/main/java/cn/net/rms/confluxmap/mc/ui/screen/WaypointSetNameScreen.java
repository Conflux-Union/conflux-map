package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.compat.Texts;
import java.util.Objects;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;

/** Small create/rename form for one player-owned local waypoint set. */
final class WaypointSetNameScreen extends ConfluxScreen {
    private static final int FIELD_WIDTH = 220;
    private static final int FIELD_HEIGHT = 20;

    private final Screen parent;
    private final WaypointStore boundStore;
    private final String existingName;
    private final Consumer<String> onSuccess;
    private TextFieldWidget nameField;
    private ButtonWidget doneButton;
    private String errorKey;

    WaypointSetNameScreen(
        final Screen parent,
        final WaypointStore boundStore,
        final String existingName,
        final Consumer<String> onSuccess
    ) {
        super(Texts.translatable(
            existingName == null
                ? "confluxmap.screen.waypoint_set.create"
                : "confluxmap.screen.waypoint_set.rename"
        ));
        this.parent = Objects.requireNonNull(parent, "parent");
        this.boundStore = Objects.requireNonNull(boundStore, "boundStore");
        this.existingName = existingName;
        this.onSuccess = Objects.requireNonNull(onSuccess, "onSuccess");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int centerX = width / 2;
        nameField = new TextFieldWidget(
            textRenderer,
            centerX - FIELD_WIDTH / 2,
            Math.max(58, height / 2 - 28),
            FIELD_WIDTH,
            FIELD_HEIGHT,
            Text.of("")
        );
        nameField.setMaxLength(32);
        nameField.setText(existingName == null ? "" : existingName);
        addDrawableChild(nameField);
        setInitialFocus(nameField);

        final int buttonY = Widgets.y(nameField) + 30;
        doneButton = addDrawableChild(Widgets.button(
            centerX - 104,
            buttonY,
            100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            button -> submit()
        ));
        addDrawableChild(Widgets.button(
            centerX + 4,
            buttonY,
            100,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
        refreshDoneButton();
    }

    @Override
    public void tick() {
        Widgets.tick(nameField);
        refreshDoneButton();
    }

    @Override
    public void onClose() {
        MinecraftClient.getInstance().setScreen(parent);
    }

    private void refreshDoneButton() {
        if (doneButton != null && nameField != null) {
            doneButton.active = !nameField.getText().trim().isEmpty();
        }
    }

    private void submit() {
        errorKey = null;
        final WaypointStore store = ConfluxMapClient.get().waypointService().current();
        if (store == null || store != boundStore) {
            errorKey = "confluxmap.screen.waypoint_set.error.unavailable";
            return;
        }
        final String requestedName = nameField.getText();
        final WaypointStore.MutationResult result = existingName == null
            ? store.createSet(requestedName)
            : store.renameSet(existingName, requestedName);
        if (result == WaypointStore.MutationResult.APPLIED
            || result == WaypointStore.MutationResult.NO_CHANGE) {
            onSuccess.accept(requestedName.trim());
            onClose();
            return;
        }
        errorKey = mutationErrorKey(result);
    }

    private static String mutationErrorKey(final WaypointStore.MutationResult result) {
        return switch (result) {
            case INVALID_NAME -> "confluxmap.screen.waypoint_set.error.invalid_name";
            case SET_ALREADY_EXISTS -> "confluxmap.screen.waypoint_set.error.exists";
            case READ_ONLY -> "confluxmap.screen.waypoint_set.error.read_only";
            default -> "confluxmap.screen.waypoint_set.error.unavailable";
        };
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 24, 0xFFFFFFFF);
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.waypoint_set.name").getString(),
            Widgets.y(nameField) - 12,
            0xFFCCCCCC
        );
        if (errorKey != null) {
            drawCentered(
                draw,
                Texts.translatable(errorKey).getString(),
                Widgets.y(nameField) + 56,
                0xFFFF7777
            );
        }
    }

    private void drawCentered(final GuiDraw draw, final String value, final int y, final int color) {
        final String text = textRenderer.trimToWidth(value, Math.max(40, width - 32));
        draw.drawTextWithShadow(textRenderer, text, width / 2f - textRenderer.getWidth(text) / 2f, y, color);
    }
}
