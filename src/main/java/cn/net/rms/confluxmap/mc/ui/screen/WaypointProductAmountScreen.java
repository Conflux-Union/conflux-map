package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.function.Consumer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

final class WaypointProductAmountScreen extends ConfluxScreen {
    private final Screen parent;
    private final String itemName;
    private final Consumer<Integer> apply;
    private TextFieldWidget input;
    private ButtonWidget done;
    private final Integer initial;
    private boolean valid;
    private Integer amount;

    WaypointProductAmountScreen(Screen parent, String item, Integer initial, Consumer<Integer> apply) {
        super(Texts.translatable("confluxmap.waypoint_details.set_amount"));
        this.parent = parent;
        this.itemName = WaypointDetailsText.itemName(item);
        this.initial = initial;
        this.apply = apply;
    }

    @Override protected void init() {
        final String draft = input != null ? input.getText() : initial == null ? "" : initial.toString();
        final int x = (width - 180) / 2;
        final int y = height / 2;
        input = new TextFieldWidget(this.textRenderer, x, y, 180, 20, getTitle());
        input.setMaxLength(10);
        input.setText(draft);
        addDrawableChild(input);
        done = addDrawableChild(Widgets.button(x, y + 26, 88, 20, Texts.translatable("gui.done"), b -> commit()));
        addDrawableChild(Widgets.button(x + 92, y + 26, 88, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"), b -> onClose()));
        Widgets.setChangedListener(input, this::changed);
        setInitialFocus(input);
        changed(draft);
        setEnterAction(() -> valid, this::commit);
    }

    private void changed(String text) {
        amount = null;
        valid = text.isEmpty();
        if (text.matches("[0-9]{1,10}")) {
            long parsed = Long.parseLong(text);
            if (parsed <= Integer.MAX_VALUE) {
                amount = (int) parsed;
                valid = true;
            }
        }
        done.active = valid;
    }

    private void commit() {
        if (!valid || isClosing()) return;
        apply.accept(amount);
        requestClose(parent);
    }

    @Override public void tick() { super.tick(); Widgets.tick(input); }

    @Override protected void renderContents(GuiDraw draw, int mouseX, int mouseY, float delta) {
        draw.renderBackground(this, mouseX, mouseY, delta);
        int x = (width - 180) / 2, y = height / 2;
        draw.drawTextWithShadow(this.textRenderer, this.textRenderer.trimToWidth(itemName, 180), x, y - 35, 0xFFFFFFFF);
        draw.drawTextWithShadow(this.textRenderer, Texts.translatable("confluxmap.waypoint_details.amount_hint").getString(),
            x, y - 19, valid ? 0xFFBBBBBB : 0xFFFF8888);
    }

    @Override public void onClose() { requestClose(parent); }
    @Override public boolean isPauseScreen() { return false; }
}
