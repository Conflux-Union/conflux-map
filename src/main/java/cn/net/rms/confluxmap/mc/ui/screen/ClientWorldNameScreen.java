package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Create/rename form for one client-owned world profile. */
final class ClientWorldNameScreen extends ConfluxScreen {
    private final Screen parent;
    private final String initialName;
    private final Consumer<String> onSubmit;
    private TextFieldWidget nameField;
    private ButtonWidget doneButton;

    ClientWorldNameScreen(
        final Screen parent,
        final String initialName,
        final Consumer<String> onSubmit
    ) {
        super(Texts.translatable(initialName == null
            ? "confluxmap.screen.client_world.create_title"
            : "confluxmap.screen.client_world.rename_title"));
        this.parent = parent;
        this.initialName = initialName;
        this.onSubmit = onSubmit;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int fieldWidth = Math.min(240, width - 24);
        nameField = new TextFieldWidget(
            this.textRenderer, width / 2 - fieldWidth / 2, 62, fieldWidth, 20,
            Texts.translatable("confluxmap.screen.client_world.name")
        );
        nameField.setMaxLength(64);
        nameField.setText(initialName == null ? "" : initialName);
        addDrawableChild(nameField);
        setInitialFocus(nameField);
        doneButton = addDrawableChild(Widgets.button(
            width / 2 - 104, 94, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.done"),
            ignored -> submit()
        ));
        addDrawableChild(Widgets.button(
            width / 2 + 4, 94, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            ignored -> onClose()
        ));
        setEnterAction(() -> doneButton != null && doneButton.active, this::submit);
        refreshDone();
    }

    @Override
    public void tick() {
        Widgets.tick(nameField);
        refreshDone();
    }

    private void refreshDone() {
        doneButton.active = nameField != null && !nameField.getText().trim().isEmpty();
    }

    private void submit() {
        final String name = nameField.getText().trim();
        if (name.isEmpty()) {
            return;
        }
        onSubmit.accept(name);
        if (MinecraftAccess.screen(MinecraftClient.getInstance()) == this) {
            onClose();
        }
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 24, 0xFFFFFFFF
        );
        final String prompt = Texts.translatable("confluxmap.screen.client_world.name").getString();
        draw.drawTextWithShadow(
            this.textRenderer, prompt, width / 2f - this.textRenderer.getWidth(prompt) / 2f, 48, 0xFFBBBBBB
        );
    }
}
