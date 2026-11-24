package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

final class MapCoordinateJumpScreen extends ConfluxScreen {
    private static final int WORLD_LIMIT = 30_000_000;
    private static final int GAP = 6;
    private static final int FIELD_HEIGHT = 20;
    private final FullscreenMapScreen parent;
    private TextFieldWidget xField;
    private TextFieldWidget zField;
    private ButtonWidget jumpButton;
    private int panelTop;
    private int panelLeft;
    private int fieldWidth;
    private boolean valid;
    private boolean returnedToMap;

    MapCoordinateJumpScreen(final FullscreenMapScreen parent) {
        super(Texts.translatable("confluxmap.map.coordinate_jump"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        final String x = xField == null ? Integer.toString(parent.centerBlockX()) : Widgets.text(xField);
        final String z = zField == null ? Integer.toString(parent.centerBlockZ()) : Widgets.text(zField);
        fieldWidth = Math.min(100, (width - GAP * 3) / 2);
        panelLeft = (width - 2 * fieldWidth - GAP) / 2;
        panelTop = Math.max(12, height / 2 - 52);
        xField = addField(panelLeft, "X", x);
        zField = addField(panelLeft + fieldWidth + GAP, "Z", z);
        jumpButton = addDrawableChild(Widgets.button(panelLeft, panelTop + 71, fieldWidth, FIELD_HEIGHT,
            Texts.translatable("confluxmap.map.coordinate_jump.go"), button -> jump()));
        addDrawableChild(Widgets.button(panelLeft + fieldWidth + GAP, panelTop + 71, fieldWidth, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.cancel"), button -> onClose()));
        setEnterAction(() -> valid, this::jump);
        updateForm();
        //#if MC>=11903
        //$$ setInitialFocus(xField);
        //#else
        setFocused(xField);
        //#endif
    }

    private TextFieldWidget addField(final int x, final String label, final String value) {
        final TextFieldWidget field = addDrawableChild(new TextFieldWidget(
            this.textRenderer, x, panelTop + 32, fieldWidth, FIELD_HEIGHT, Texts.literal(label)
        ));
        field.setMaxLength(16);
        Widgets.setText(field, value);
        Widgets.setChangedListener(field, ignored -> updateForm());
        return field;
    }

    static Integer parseCoordinate(final String text) {
        try {
            final int block = Integer.parseInt(text.strip());
            return block >= -WORLD_LIMIT && block <= WORLD_LIMIT ? block : null;
        } catch (final NumberFormatException invalid) {
            return null;
        }
    }

    private void updateForm() {
        valid = parseCoordinate(Widgets.text(xField)) != null && parseCoordinate(Widgets.text(zField)) != null;
        jumpButton.active = valid;
    }

    private void jump() {
        if (!valid) {
            return;
        }
        parent.jumpToCoordinates(parseCoordinate(Widgets.text(xField)), parseCoordinate(Widgets.text(zField)));
        onClose();
    }

    @Override
    protected boolean usesVanillaBackground() {
        return false;
    }

    @Override
    protected void renderBackdrop(final GuiDraw draw, final float tickDelta) {
        parent.renderCoordinateJumpBackdrop(draw, width, height, tickDelta);
        draw.fillPopup(0, 0, width, height, 0x35000000);
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float delta) {
        draw.fillPopup(panelLeft - 10, panelTop - 9, width - panelLeft + 10,
            panelTop + 100, 0xF023272B);
        drawCentered(draw, getTitle().getString(), width / 2, panelTop, 0xFFFFFFFF);
        drawCentered(draw, "X", panelLeft + fieldWidth / 2, panelTop + 19, 0xFFFFFFFF);
        drawCentered(draw, "Z", panelLeft + fieldWidth + GAP + fieldWidth / 2, panelTop + 19, 0xFFFFFFFF);
        if (!valid) {
            drawCentered(draw,
                Texts.translatable("confluxmap.map.coordinate_jump.invalid").getString(),
                width / 2, panelTop + 56, 0xFFFF8888);
        }
    }

    private void drawCentered(
        final GuiDraw draw, final String text, final int centerX, final int y, final int color
    ) {
        draw.drawTextWithShadow(this.textRenderer, text, centerX - this.textRenderer.getWidth(text) / 2, y, color);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    public void onClose() {
        returnedToMap = true;
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public void removed() {
        parent.coordinateJumpRemoved(this, returnedToMap);
        super.removed();
    }
}
