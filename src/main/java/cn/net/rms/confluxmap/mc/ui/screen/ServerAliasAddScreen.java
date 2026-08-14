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

/** Address entry form for {@link ServerAliasScreen}; the caller validates what is typed. */
final class ServerAliasAddScreen extends ConfluxScreen {
    private final Screen parent;
    private final Consumer<String> onSubmit;
    private TextFieldWidget addressField;
    private ButtonWidget doneButton;

    ServerAliasAddScreen(final Screen parent, final Consumer<String> onSubmit) {
        super(Texts.translatable("confluxmap.screen.server_alias.add_title"));
        this.parent = parent;
        this.onSubmit = onSubmit;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int fieldWidth = Math.min(240, width - 24);
        addressField = new TextFieldWidget(
            this.textRenderer, width / 2 - fieldWidth / 2, 62, fieldWidth, 20,
            Texts.translatable("confluxmap.screen.server_alias.address")
        );
        addressField.setMaxLength(128);
        addDrawableChild(addressField);
        setInitialFocus(addressField);
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
        Widgets.tick(addressField);
        refreshDone();
    }

    private void refreshDone() {
        doneButton.active = addressField != null && !addressField.getText().trim().isEmpty();
    }

    private void submit() {
        final String address = addressField.getText().trim();
        if (address.isEmpty()) {
            return;
        }
        onSubmit.accept(address);
        onClose();
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 24,
            0xFFFFFFFF
        );
        final String hint = Texts.translatable("confluxmap.screen.server_alias.add_hint").getString();
        draw.drawTextWithShadow(
            this.textRenderer, hint, width / 2f - this.textRenderer.getWidth(hint) / 2f, 44,
            0xFFBBBBBB
        );
    }
}
