package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.net.WebMapClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Edits a typed value in the game-channel server settings. */
final class ServerSettingScreen extends ConfluxScreen {
    private final ConfigScreen parent;
    private final String key;
    private final List<String> choices;
    private TextFieldWidget field;
    private ButtonWidget apply;
    private int min;
    private int max;
    private int top;

    ServerSettingScreen(final ConfigScreen parent, final String key, final List<String> choices) {
        super(Texts.translatable("confluxmap.webmap." + key));
        this.parent = parent;
        this.key = key;
        this.choices = choices;
    }

    static Text optionLabel(final String value) {
        return Texts.translatable("confluxmap.webmap.mode." + value);
    }

    @Override
    protected void init() {
        final int w = Math.min(width - 24, 230);
        final int x = (width - w) / 2;
        top = Math.max(8, height / 2 - (choices.isEmpty() ? 46 : 16 + choices.size() * 12));
        if (choices.isEmpty()) {
            min = key.equals("minRequestIntervalMs") ? 0 : 1;
            max = switch (key) {
                case "maxTilesPerRequest" -> 255;
                case "minRequestIntervalMs" -> 60_000;
                default -> Integer.parseInt(WebMapClient.setting("maxSharedWaypointsPerWorld"));
            };
            final String current = field == null ? WebMapClient.setting(key) : Widgets.text(field);
            field = addDrawableChild(new TextFieldWidget(this.textRenderer, x, top + 34, w, 20, getTitle()));
            field.setMaxLength(10);
            Widgets.setText(field, current);
            apply = addDrawableChild(Widgets.button(x, top + 58, w, 20,
                Texts.translatable("confluxmap.webmap.server_apply"), ignored -> applyNumber()));
            Widgets.setChangedListener(field, ignored -> updateApply());
            setEnterAction(() -> apply.active, this::applyNumber);
            updateApply();
            //#if MC>=11903
            //$$ setInitialFocus(field);
            //#else
            setFocused(field);
            //#endif
        } else {
            int y = top + 20;
            final String current = WebMapClient.setting(key);
            for (final String choice : choices) {
                final ButtonWidget button = addDrawableChild(Widgets.button(x, y, w, 20,
                    optionLabel(choice), ignored -> submit(choice)));
                button.active = WebMapClient.canEditSettings() && !choice.equals(current);
                y += 24;
            }
        }
    }

    private Integer selectedNumber() {
        try {
            final int value = Integer.parseInt(Widgets.text(field).strip());
            return value >= min && value <= max ? value : null;
        } catch (final NumberFormatException invalid) {
            return null;
        }
    }

    private void updateApply() {
        apply.active = WebMapClient.canEditSettings() && selectedNumber() != null;
    }

    private void applyNumber() {
        final Integer value = selectedNumber();
        if (value != null) submit(value.toString());
    }

    private void submit(final String value) {
        if (WebMapClient.canEditSettings()) {
            WebMapClient.setServerSetting(key, value);
            onClose();
        }
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float delta) {
        drawCentered(draw, getTitle(), top);
        if (choices.isEmpty()) {
            drawCentered(draw, Texts.translatable("confluxmap.webmap.server_number_range", min, max), top + 17);
        }
    }

    private void drawCentered(final GuiDraw draw, final Text text, final int y) {
        final String label = text.getString();
        draw.drawTextWithShadow(this.textRenderer, label, (width - this.textRenderer.getWidth(label)) / 2, y, 0xFFFFFFFF);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }
}
