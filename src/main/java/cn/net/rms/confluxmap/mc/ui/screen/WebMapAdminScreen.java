package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.net.WebMapClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

import java.util.*;

final class WebMapAdminScreen extends ConfluxScreen {
    private final Screen parent;
    private final Map<String, ButtonWidget> rows = new LinkedHashMap<>();
    private static final String[] KEYS = {
        "sharePlayers",
        "allowPlayerSearch",
        "allowWaypointSearch",
        "showWaypointImages",
        "waypointImageMode",
        "enableChat",
        "showGameChat",
        "allowWebChat"
    };

    WebMapAdminScreen(Screen parent) {
        super(Texts.translatable("confluxmap.webmap.admin"));
        this.parent = parent;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rows.clear();
        int columns = width >= 340 ? 2 : 1;
        int w = Math.min(164, (width - 16) / columns - 4);
        int start = width / 2 - (w * columns + 4 * (columns - 1)) / 2;
        int y = 36;
        for (int i = 0; i < KEYS.length; i++) {
            String key = KEYS[i];
            ButtonWidget b =
                    addDrawableChild(
                            Widgets.button(
                                    start + (i % columns) * (w + 4),
                                    y + (i / columns) * 22,
                                    w,
                                    20,
                                    Texts.literal(""),
                                    ignored -> {
                                        if (!WebMapClient.flag("admin")) return;
                                        String value = WebMapClient.value(key);
                                        if (key.equals("waypointImageMode")) {
                                            List<String> modes =
                                                    List.of(
                                                            "APPROVAL",
                                                            "OP_ONLY",
                                                            "EVERYONE",
                                                            "DISABLED");
                                            value =
                                                    modes.get(
                                                            (modes.indexOf(value) + 1)
                                                                    % modes.size());
                                        } else
                                            value = Boolean.toString(!Boolean.parseBoolean(value));
                                        WebMapClient.set(key, value);
                                    }));
            rows.put(key, b);
        }
        addDrawableChild(
                Widgets.button(
                        width / 2 - 50,
                        height - 26,
                        100,
                        20,
                        Texts.translatable("confluxmap.screen.waypoint.done"),
                        b -> onClose()));
        tick();
    }

    @Override
    public void tick() {
        for (var entry : rows.entrySet()) {
            String key = entry.getKey();
            ButtonWidget b = entry.getValue();
            b.active = WebMapClient.flag("admin");
            String value = WebMapClient.value(key);
            b.setMessage(
                    Texts.literal(
                            Texts.translatable("confluxmap.webmap." + key).getString()
                                    + ": "
                                    + value));
        }
    }

    @Override
    public void onClose() {
        requestClose(parent);
    }

    @Override
    protected void renderContents(GuiDraw draw, int mouseX, int mouseY, float delta) {
        draw.renderBackground(this, mouseX, mouseY, delta);
        String t = getTitle().getString();
        draw.drawTextWithShadow(
                this.textRenderer,
                t,
                width / 2f - this.textRenderer.getWidth(t) / 2f,
                12,
                0xFFFFFFFF);
        String r = WebMapClient.result();
        draw.drawTextWithShadow(
                this.textRenderer,
                r,
                width / 2f - this.textRenderer.getWidth(r) / 2f,
                height - 40,
                0xFFCCCCCC);
    }
}
