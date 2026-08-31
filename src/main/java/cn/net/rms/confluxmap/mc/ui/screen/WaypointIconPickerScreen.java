package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

/** Searchable grid of vanilla item icons for one waypoint marker. */
final class WaypointIconPickerScreen extends ConfluxScreen {
    private record Choice(WaypointIconSearch.Entry entry, ItemStack stack) {
    }

    private static final int FIELD_HEIGHT = 20;
    private static final int GRID_TOP = 68;
    private static final int CELL_SIZE = 24;
    private static final int BUTTON_SIZE = 22;
    private static final int ICON_SIZE = 16;
    private static final int MAX_GRID_WIDTH = 384;

    private final Screen parent;
    private final Consumer<String> onSelect;
    private final List<Choice> choices;
    private final Map<ButtonWidget, Choice> buttons = new LinkedHashMap<>();

    private TextFieldWidget searchField;
    private List<Choice> filtered = List.of();
    private String query = "";
    private int scrollRow;
    private int gridLeft;
    private int columns;

    WaypointIconPickerScreen(final Screen parent, final Consumer<String> onSelect) {
        super(Texts.translatable("confluxmap.screen.waypoint.icon_picker.title"));
        this.parent = parent;
        this.onSelect = onSelect;
        this.choices = loadChoices();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        buttons.clear();
        columns = Math.max(1, Math.min(MAX_GRID_WIDTH, width - 24) / CELL_SIZE);
        gridLeft = (width - columns * CELL_SIZE) / 2;
        searchField = new TextFieldWidget(
            this.textRenderer,
            width / 2 - Math.min(240, width - 24) / 2,
            38,
            Math.min(240, width - 24),
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.waypoint.icon_picker.search")
        );
        searchField.setMaxLength(64);
        searchField.setText(query);
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        for (final Choice choice : choices) {
            final ButtonWidget button = addDrawableChild(Widgets.button(
                gridLeft,
                GRID_TOP,
                BUTTON_SIZE,
                BUTTON_SIZE,
                itemChoiceButtonMessage(choice.entry().displayName()),
                ignored -> select(choice)
            ));
            button.visible = false;
            buttons.put(button, choice);
        }
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            height - 28,
            100,
            20,
            Texts.translatable("confluxmap.screen.waypoint.icon_picker.back"),
            ignored -> onClose()
        ));
        updateButtons();
    }

    @Override
    public void tick() {
        super.tick();
        Widgets.tick(searchField);
        final String current = searchField == null ? "" : searchField.getText();
        if (!current.equals(query)) {
            query = current;
            scrollRow = 0;
            updateButtons();
        }
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    private void select(final Choice choice) {
        onSelect.accept(choice.entry().itemId());
        onClose();
    }

    private void updateButtons() {
        final Map<String, Choice> byId = new LinkedHashMap<>();
        final List<WaypointIconSearch.Entry> entries = new ArrayList<>(choices.size());
        for (final Choice choice : choices) {
            entries.add(choice.entry());
            byId.put(choice.entry().itemId(), choice);
        }
        filtered = WaypointIconSearch.filter(entries, query).stream()
            .map(entry -> byId.get(entry.itemId()))
            .toList();
        final int visibleRows = visibleRows();
        scrollRow = Math.max(0, Math.min(scrollRow, Math.max(0, totalRows() - visibleRows)));
        for (final ButtonWidget button : buttons.keySet()) {
            button.visible = false;
        }
        final int start = scrollRow * columns;
        final int end = Math.min(filtered.size(), start + visibleRows * columns);
        for (int index = start; index < end; index++) {
            final int visibleIndex = index - start;
            final ButtonWidget button = buttonFor(filtered.get(index));
            Widgets.setX(button, gridLeft + visibleIndex % columns * CELL_SIZE);
            Widgets.setY(button, GRID_TOP + visibleIndex / columns * CELL_SIZE);
            button.visible = true;
        }
    }

    private ButtonWidget buttonFor(final Choice choice) {
        for (final Map.Entry<ButtonWidget, Choice> entry : buttons.entrySet()) {
            if (entry.getValue() == choice) {
                return entry.getKey();
            }
        }
        throw new IllegalStateException("missing item icon button");
    }

    private int visibleRows() {
        return Math.max(1, (height - GRID_TOP - 38) / CELL_SIZE);
    }

    private int totalRows() {
        return (filtered.size() + columns - 1) / columns;
    }

    @Override
    //#if MC>=12002
    //$$ public boolean mouseScrolled(
    //$$     final double mouseX,
    //$$     final double mouseY,
    //$$     final double horizontalAmount,
    //$$     final double amount
    //$$ ) {
    //#else
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
    //#endif
        if (amount != 0 && mouseY >= GRID_TOP && mouseY < height - 32) {
            final int maxRow = Math.max(0, totalRows() - visibleRows());
            scrollRow = Math.max(0, Math.min(maxRow, scrollRow + (amount < 0 ? 1 : -1)));
            updateButtons();
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 16, 0xFFFFFFFF);
        drawCentered(
            draw,
            Texts.translatable("confluxmap.screen.waypoint.icon_picker.prompt").getString(),
            28,
            0xFFBBBBBB
        );
        drawListScrollbar(
            draw,
            gridLeft + columns * CELL_SIZE + 2,
            GRID_TOP,
            visibleRows() * CELL_SIZE - 2,
            totalRows(),
            visibleRows(),
            scrollRow
        );
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        for (final Map.Entry<ButtonWidget, Choice> entry : buttons.entrySet()) {
            final ButtonWidget button = entry.getKey();
            if (!button.visible) {
                continue;
            }
            final int x = Widgets.x(button);
            final int y = Widgets.y(button);
            draw.fill(x + 2, y + 2, x + BUTTON_SIZE - 2, y + BUTTON_SIZE - 2, 0xFF181818);
            draw.drawItemIcon(
                MinecraftClient.getInstance(),
                entry.getValue().stack(),
                x + BUTTON_SIZE / 2f,
                y + BUTTON_SIZE / 2f,
                ICON_SIZE
            );
            if (button.isHovered()) {
                draw.drawTooltip(
                    this,
                    this.textRenderer,
                    Texts.literal(entry.getValue().entry().displayName()),
                    mouseX,
                    mouseY
                );
            }
        }
    }

    private void drawCentered(final GuiDraw draw, final String text, final int y, final int color) {
        draw.drawTextWithShadow(
            this.textRenderer,
            text,
            (width - this.textRenderer.getWidth(text)) / 2f,
            y,
            color
        );
    }

    private static List<Choice> loadChoices() {
        final List<Choice> result = new ArrayList<>();
        for (final Item item : Regs.items()) {
            final String id = Regs.itemId(item).toString();
            if (!id.startsWith("minecraft:")) {
                continue;
            }
            final ItemStack stack = new ItemStack(item);
            if (stack.isEmpty()) {
                continue;
            }
            result.add(new Choice(
                new WaypointIconSearch.Entry(id, stack.getName().getString()),
                stack
            ));
        }
        result.sort(Comparator.comparing(
            choice -> choice.entry().displayName(),
            String.CASE_INSENSITIVE_ORDER
        ));
        return List.copyOf(result);
    }

    static Text itemChoiceButtonMessage(final String displayName) {
        //#if MC>=260200
        //$$ return Texts.literal(displayName);
        //#else
        return Texts.literal("");
        //#endif
    }
}
