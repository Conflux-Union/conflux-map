package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.net.WebMapClient;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;

import java.util.UUID;

final class WebMapImageScreen extends ConfluxScreen {
    private final Screen parent;
    private final UUID waypoint;
    private TextFieldWidget path;

    WebMapImageScreen(Screen parent, UUID waypoint) {
        super(Texts.translatable("confluxmap.webmap.image_upload"));
        this.parent = parent;
        this.waypoint = waypoint;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        int w = Math.min(300, width - 24);
        path =
                new TextFieldWidget(
                        this.textRenderer,
                        width / 2 - w / 2,
                        64,
                        w,
                        20,
                        Texts.literal("PNG/JPEG path"));
        path.setMaxLength(1024);
        addDrawableChild(path);
        setInitialFocus(path);
        addDrawableChild(
                Widgets.button(
                        width / 2 - 102,
                        92,
                        100,
                        20,
                        Texts.translatable("confluxmap.webmap.image_upload"),
                        b -> WebMapClient.upload(waypoint, path.getText())));
        addDrawableChild(
                Widgets.button(
                        width / 2 + 2,
                        92,
                        100,
                        20,
                        Texts.translatable("confluxmap.screen.waypoint.done"),
                        b -> onClose()));
    }

    @Override
    public void tick() {
        Widgets.tick(path);
    }

    @Override
    public void onClose() {
        requestClose(parent);
    }

    @Override
    protected void renderContents(GuiDraw draw, int x, int y, float delta) {
        draw.renderBackground(this, x, y, delta);
        String title = getTitle().getString();
        draw.drawTextWithShadow(
                this.textRenderer,
                title,
                width / 2f - this.textRenderer.getWidth(title) / 2f,
                24,
                0xFFFFFFFF);
        String hint = Texts.translatable("confluxmap.webmap.image_path").getString();
        draw.drawTextWithShadow(
                this.textRenderer,
                hint,
                width / 2f - this.textRenderer.getWidth(hint) / 2f,
                44,
                0xFFCCCCCC);
        String r = WebMapClient.result();
        draw.drawTextWithShadow(
                this.textRenderer,
                r,
                width / 2f - this.textRenderer.getWidth(r) / 2f,
                120,
                0xFFCCCCCC);
    }
}
