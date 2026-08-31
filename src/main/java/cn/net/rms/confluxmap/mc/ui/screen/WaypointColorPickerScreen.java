package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Small dependency-free HSV picker for waypoint colors. */
final class WaypointColorPickerScreen extends ConfluxScreen {
    private static final int PICKER_SIZE = 128;
    private static final int HUE_WIDTH = 12;
    private static final int PICKER_GAP = 8;
    private static final int GRID_STEP = 2;

    private final Screen parent;
    private final WaypointColorPickerModel color;
    private final IntConsumer onApply;
    private TextFieldWidget hexField;
    private boolean draggingPicker;
    private boolean draggingHue;
    private boolean invalidHex;
    private String lastHexText;
    private float cachedPickerHue = Float.NaN;
    private List<RenderUtil.ColoredRect> pickerRects = List.of();

    WaypointColorPickerScreen(
        final Screen parent,
        final int initialColor,
        final IntConsumer onApply
    ) {
        super(Texts.translatable("confluxmap.screen.waypoint.color_picker"));
        this.parent = parent;
        this.color = new WaypointColorPickerModel(initialColor);
        this.onApply = onApply;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        cachedPickerHue = Float.NaN;
        pickerRects = List.of();
        final int centerX = width / 2;
        hexField = new TextFieldWidget(
            this.textRenderer, centerX - 50, pickerTop() + PICKER_SIZE + 22,
            100, 20, Text.of("")
        );
        hexField.setMaxLength(7);
        lastHexText = color.hex();
        hexField.setText(lastHexText);
        addDrawableChild(hexField);
        addDrawableChild(Widgets.button(
            centerX - 104, height - 32, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.color_apply"),
            button -> apply()
        ));
        addDrawableChild(Widgets.button(
            centerX + 4, height - 32, 100, 20,
            Texts.translatable("confluxmap.screen.waypoint.cancel"),
            button -> onClose()
        ));
        setEnterAction(() -> true, this::apply);
    }

    @Override
    public void tick() {
        super.tick();
        Widgets.tick(hexField);
        if (hexField != null) {
            final String current = hexField.getText();
            if (!current.equals(lastHexText)) {
                lastHexText = current;
                invalidHex = !color.setHex(current);
            }
        }
    }

    private void apply() {
        if (hexField == null || !color.setHex(hexField.getText())) {
            invalidHex = true;
            return;
        }
        onApply.accept(color.colorArgb());
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseClicked(final Click click, final boolean doubledClick) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
    //#endif
        //#if MC>=12109
        //$$ if (super.mouseClicked(click, doubledClick)) {
        //#else
        if (super.mouseClicked(mouseX, mouseY, button)) {
        //#endif
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (insidePicker(mouseX, mouseY)) {
            draggingPicker = true;
            updatePicker(mouseX, mouseY);
            return true;
        }
        if (insideHue(mouseX, mouseY)) {
            draggingHue = true;
            updateHue(mouseY);
            return true;
        }
        return false;
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseDragged(final Click click, final double deltaX, final double deltaY) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseDragged(
        final double mouseX,
        final double mouseY,
        final int button,
        final double deltaX,
        final double deltaY
    ) {
    //#endif
        if (button == 0 && draggingPicker) {
            updatePicker(mouseX, mouseY);
            return true;
        }
        if (button == 0 && draggingHue) {
            updateHue(mouseY);
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseDragged(click, deltaX, deltaY);
        //#else
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        //#endif
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseReleased(final Click click) {
    //$$     final int button = click.button();
    //#else
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
    //#endif
        if (button == 0 && (draggingPicker || draggingHue)) {
            draggingPicker = false;
            draggingHue = false;
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    private void updatePicker(final double mouseX, final double mouseY) {
        color.selectSaturationValue(
            mouseX - pickerLeft(), mouseY - pickerTop(), PICKER_SIZE, PICKER_SIZE
        );
        syncHex();
    }

    private void updateHue(final double mouseY) {
        color.selectHue(mouseY, pickerTop(), PICKER_SIZE);
        syncHex();
    }

    private void syncHex() {
        invalidHex = false;
        if (hexField != null) {
            lastHexText = color.hex();
            hexField.setText(lastHexText);
        }
    }

    private boolean insidePicker(final double x, final double y) {
        return x >= pickerLeft() && x <= pickerLeft() + PICKER_SIZE
            && y >= pickerTop() && y <= pickerTop() + PICKER_SIZE;
    }

    private boolean insideHue(final double x, final double y) {
        final int left = pickerLeft() + PICKER_SIZE + PICKER_GAP;
        return x >= left && x <= left + HUE_WIDTH
            && y >= pickerTop() && y <= pickerTop() + PICKER_SIZE;
    }

    private int pickerLeft() {
        return width / 2 - (PICKER_SIZE + PICKER_GAP + HUE_WIDTH) / 2;
    }

    private int pickerTop() {
        return 38;
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 18, 0xFFFFFFFF);
        drawPicker(draw);
        final int previewLeft = width / 2 - 78;
        final int previewTop = pickerTop() + PICKER_SIZE + 22;
        draw.fill(previewLeft - 1, previewTop - 1, previewLeft + 21, previewTop + 21, 0xFFFFFFFF);
        draw.fill(previewLeft, previewTop, previewLeft + 20, previewTop + 20, color.colorArgb());
        drawCentered(
            draw,
            invalidHex
                ? Texts.translatable("confluxmap.screen.waypoint.color_invalid").getString()
                : Texts.translatable("confluxmap.screen.waypoint.color_hex").getString(),
            pickerTop() + PICKER_SIZE + 11,
            invalidHex ? 0xFFFF7777 : 0xFFB8B8B8
        );
    }

    private void drawPicker(final GuiDraw draw) {
        final int left = pickerLeft();
        final int top = pickerTop();
        if (Float.compare(cachedPickerHue, color.hue()) != 0 || pickerRects.isEmpty()) {
            cachedPickerHue = color.hue();
            final List<RenderUtil.ColoredRect> rects = new ArrayList<>();
            for (int y = 0; y < PICKER_SIZE; y += GRID_STEP) {
                final float value = 1.0f - y / (float) (PICKER_SIZE - 1);
                for (int x = 0; x < PICKER_SIZE; x += GRID_STEP) {
                    final float saturation = x / (float) (PICKER_SIZE - 1);
                    rects.add(new RenderUtil.ColoredRect(
                        left + x, top + y,
                        Math.min(GRID_STEP, PICKER_SIZE - x),
                        Math.min(GRID_STEP, PICKER_SIZE - y),
                        WaypointColorPickerModel.fromHsv(
                            cachedPickerHue, saturation, value
                        )
                    ));
                }
            }
            final int hueLeft = left + PICKER_SIZE + PICKER_GAP;
            for (int y = 0; y < PICKER_SIZE; y++) {
                rects.add(new RenderUtil.ColoredRect(
                    hueLeft, top + y, HUE_WIDTH, 1,
                    WaypointColorPickerModel.fromHsv(
                        y / (float) (PICKER_SIZE - 1), 1.0f, 1.0f
                    )
                ));
            }
            pickerRects = List.copyOf(rects);
        }
        RenderUtil.fillRects(draw.matrices(), pickerRects);
        final int hueLeft = left + PICKER_SIZE + PICKER_GAP;
        final int pickerX = left + Math.round(color.saturation() * (PICKER_SIZE - 1));
        final int pickerY = top + Math.round((1.0f - color.value()) * (PICKER_SIZE - 1));
        drawCrosshair(draw, pickerX, pickerY);
        final int hueY = top + Math.round(color.hue() * (PICKER_SIZE - 1));
        draw.fill(hueLeft - 2, hueY - 1, hueLeft + HUE_WIDTH + 2, hueY + 2, 0xFFFFFFFF);
        draw.fill(hueLeft - 1, hueY, hueLeft + HUE_WIDTH + 1, hueY + 1, 0xFF000000);
    }

    private static void drawCrosshair(final GuiDraw draw, final int x, final int y) {
        draw.fill(x - 5, y, x + 6, y + 1, 0xFF000000);
        draw.fill(x, y - 5, x + 1, y + 6, 0xFF000000);
        draw.fill(x - 4, y, x + 5, y + 1, 0xFFFFFFFF);
        draw.fill(x, y - 4, x + 1, y + 5, 0xFFFFFFFF);
    }

    private void drawCentered(
        final GuiDraw draw,
        final String text,
        final int y,
        final int colorArgb
    ) {
        draw.drawTextWithShadow(
            this.textRenderer,
            text,
            width / 2f - this.textRenderer.getWidth(text) / 2f,
            y,
            colorArgb
        );
    }
}
