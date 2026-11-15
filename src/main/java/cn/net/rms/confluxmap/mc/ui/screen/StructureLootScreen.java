package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.predict.ChestLootPreview;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.LootItemResolver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Previews the deterministic vanilla roll of one predicted structure's chests. The roll runs
 * once on open on a worker via {@link StructureMarkerService#lootPreviewAsync}; the render
 * thread only consumes the completed result and degrades gracefully (unknown items fall back
 * to text slots, unrolled chests show a note instead of a grid). Each chest renders as a
 * vanilla-container-style panel: a titled header and a 9-column slot grid painted with plain
 * fills, so no container screen machinery or widget texture is involved.
 */
final class StructureLootScreen extends ConfluxScreen {
    private static final int TITLE_Y = 16;
    private static final int POSITION_Y = 30;
    private static final int LIST_TOP = 44;
    private static final int LIST_BOTTOM_MARGIN = 36;
    private static final int PANEL_WIDTH = 176;
    private static final int TITLE_STRIP = 20;
    private static final int SLOT_SIZE = 18;
    private static final int GRID_COLUMNS = 9;
    private static final int NOTE_HEIGHT = 22;
    private static final int PANEL_GAP = 8;
    private static final int SCROLL_STEP = SLOT_SIZE;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFBBBBBB;
    private static final int FAILURE_COLOR = 0xFFFF7777;
    private static final String TABLE_KEY_PREFIX = "confluxmap.loot_table.chests.";

    // Vanilla container palette: gray panel with a beveled edge over inset dark slots.
    private static final int PANEL_BORDER = 0xFF000000;
    private static final int PANEL_BODY = 0xFFC6C6C6;
    private static final int PANEL_EDGE_LIGHT = 0xFFFFFFFF;
    private static final int PANEL_EDGE_DARK = 0xFF555555;
    private static final int PANEL_TEXT = 0xFF404040;
    private static final int SLOT_SHADE = 0xFF373737;
    private static final int SLOT_BODY = 0xFF8B8B8B;
    private static final int SLOT_HOVER = 0x80FFFFFF;
    private static final int COUNT_COLOR = 0xFFFFFFFF;

    private static final int[] ROMAN_VALUES = {1000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_NUMERALS = {
        "M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"
    };

    private final Screen parent;
    private final StructureIndex.Marker marker;
    private final StructureMarkerService service;
    private final LootItemResolver resolver = new LootItemResolver();
    private List<Panel> panels;
    private String failureKey;
    private int scrollOffset;
    private boolean queryStarted;

    StructureLootScreen(
        final StructureIndex.Marker marker,
        final StructureMarkerService service,
        final Screen parent
    ) {
        super(Texts.translatable(
            "confluxmap.screen.structure_loot.title",
            Texts.translatable(marker.translationKey()).getString()
        ));
        this.marker = marker;
        this.service = service;
        this.parent = parent;
    }

    /**
     * The entry the location menu opens. The menu runs while the fullscreen map is showing,
     * so the live screen is the return target; the package-private constructor stays open for
     * callers that need an explicit parent.
     */
    public static StructureLootScreen create(
        final StructureIndex.Marker marker,
        final StructureMarkerService service
    ) {
        return new StructureLootScreen(
            marker, service, MinecraftAccess.screen(MinecraftClient.getInstance())
        );
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            height - 28,
            100,
            20,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
        if (!queryStarted) {
            queryStarted = true;
            submitQuery();
        }
    }

    private void submitQuery() {
        try {
            service.lootPreviewAsync(marker)
                .handle((found, fault) -> fault == null ? found : List.<ChestLootPreview>of())
                .thenAccept(found ->
                    MinecraftClient.getInstance().execute(() -> acceptResults(found)));
        } catch (final RejectedExecutionException e) {
            failureKey = "confluxmap.screen.structure_loot.failed";
        }
    }

    private void acceptResults(final List<ChestLootPreview> found) {
        // Results only land while this screen is live; a replaced or faded-out screen drops them.
        if (MinecraftAccess.screen(MinecraftClient.getInstance()) != this) {
            return;
        }
        panels = buildPanels(found, resolver);
    }

    @Override
    public void onClose() {
        requestClose(parent);
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), TITLE_Y, TEXT_COLOR);
        drawCentered(
            draw,
            CandidateListUi.coordinateText(marker.blockX(), marker.blockZ()),
            POSITION_Y,
            SECONDARY_COLOR
        );
        if (failureKey != null) {
            drawCentered(draw, Texts.translatable(failureKey).getString(), LIST_TOP, FAILURE_COLOR);
            return;
        }
        if (panels == null) {
            drawCentered(
                draw,
                Texts.translatable("confluxmap.screen.structure_loot.loading").getString(),
                LIST_TOP,
                SECONDARY_COLOR
            );
            return;
        }
        if (panels.isEmpty()) {
            drawCentered(
                draw,
                Texts.translatable("confluxmap.screen.structure_loot.empty").getString(),
                LIST_TOP,
                SECONDARY_COLOR
            );
            return;
        }
        renderPanels(draw, mouseX, mouseY);
    }

    private void renderPanels(final GuiDraw draw, final int mouseX, final int mouseY) {
        scrollOffset = Math.max(
            0, Math.min(scrollOffset, Math.max(0, contentHeight() - viewportHeight()))
        );
        final VisibleStart start = visibleStart();
        final int left = panelLeft();
        final int bottom = height - LIST_BOTTOM_MARGIN;
        // Fills go first, icons and text after flushGui: on buffered GUI versions
        // (1.20-1.21.7) a queued fill would paint over the immediately drawn item icons.
        final PanelHit hover = panelAt(mouseX, mouseY);
        int y = LIST_TOP - start.skipped();
        for (int index = start.index(); index < panels.size() && y < bottom; index++) {
            final Panel panel = panels.get(index);
            fillPanel(
                draw, panel, left, y,
                hover != null && hover.panel() == panel ? hover.slot() : -1
            );
            y += panelHeight(panel) + PANEL_GAP;
        }
        drawListScrollbar(
            draw,
            left + PANEL_WIDTH + 4,
            LIST_TOP,
            viewportHeight(),
            contentHeight(),
            viewportHeight(),
            scrollOffset
        );
        draw.flushGui();
        y = LIST_TOP - start.skipped();
        for (int index = start.index(); index < panels.size() && y < bottom; index++) {
            final Panel panel = panels.get(index);
            paintPanel(draw, panel, left, y);
            y += panelHeight(panel) + PANEL_GAP;
        }
    }

    private void fillPanel(
        final GuiDraw draw,
        final Panel panel,
        final int left,
        final int top,
        final int hoveredSlot
    ) {
        final int bottom = top + panelHeight(panel);
        draw.fill(left, top, left + PANEL_WIDTH, bottom, PANEL_BORDER);
        draw.fill(left + 1, top + 1, left + PANEL_WIDTH - 1, bottom - 1, PANEL_BODY);
        draw.fill(left + 1, top + 1, left + PANEL_WIDTH - 1, top + 2, PANEL_EDGE_LIGHT);
        draw.fill(left + 1, top + 1, left + 2, bottom - 1, PANEL_EDGE_LIGHT);
        draw.fill(left + 1, bottom - 2, left + PANEL_WIDTH - 1, bottom - 1, PANEL_EDGE_DARK);
        draw.fill(left + PANEL_WIDTH - 2, top + 1, left + PANEL_WIDTH - 1, bottom - 1, PANEL_EDGE_DARK);
        if (!panel.rolled()) {
            return;
        }
        for (int slot = 0; slot < gridRows(panel) * GRID_COLUMNS; slot++) {
            final int x = gridLeft(left) + (slot % GRID_COLUMNS) * SLOT_SIZE;
            final int y = top + TITLE_STRIP + (slot / GRID_COLUMNS) * SLOT_SIZE;
            draw.fill(x, y, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_SHADE);
            draw.fill(x + 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, SLOT_BODY);
            draw.fill(x + 1, y + SLOT_SIZE - 1, x + SLOT_SIZE, y + SLOT_SIZE, PANEL_EDGE_LIGHT);
            draw.fill(x + SLOT_SIZE - 1, y + 1, x + SLOT_SIZE, y + SLOT_SIZE, PANEL_EDGE_LIGHT);
            if (slot == hoveredSlot) {
                draw.fill(x + 1, y + 1, x + SLOT_SIZE - 1, y + SLOT_SIZE - 1, SLOT_HOVER);
            }
        }
    }

    private void paintPanel(final GuiDraw draw, final Panel panel, final int left, final int top) {
        // Vanilla container titles and slot counts: no shadow on titles, shadow on counts.
        final String name = tableName(panel.table());
        draw.drawText(this.textRenderer, name, left + 7, top + 6, PANEL_TEXT);
        final String position = panel.blockX() + ", " + panel.blockY() + ", " + panel.blockZ();
        final int positionWidth = this.textRenderer.getWidth(position);
        draw.drawText(
            this.textRenderer, position, left + PANEL_WIDTH - 7 - positionWidth, top + 6, PANEL_TEXT
        );
        if (!panel.rolled()) {
            draw.drawText(
                this.textRenderer,
                Texts.translatable("confluxmap.screen.structure_loot.roll_unavailable").getString(),
                left + 7,
                top + TITLE_STRIP + 6,
                PANEL_TEXT
            );
            return;
        }
        final List<Slot> slots = panel.slots();
        for (int slot = 0; slot < slots.size(); slot++) {
            final Slot entry = slots.get(slot);
            final int x = gridLeft(left) + (slot % GRID_COLUMNS) * SLOT_SIZE;
            final int y = top + TITLE_STRIP + (slot / GRID_COLUMNS) * SLOT_SIZE;
            final ItemStack icon = entry.icon();
            if (icon != null) {
                draw.drawItemIcon(
                    MinecraftClient.getInstance(), icon, x + SLOT_SIZE / 2f, y + SLOT_SIZE / 2f, 16
                );
            } else {
                draw.drawTextWithShadow(this.textRenderer, entry.rawName(), x + 2, y + 4, PANEL_TEXT);
            }
            if (entry.count() > 1) {
                final String count = Integer.toString(entry.count());
                final int countWidth = this.textRenderer.getWidth(count);
                draw.drawTextOverIcons(
                    this.textRenderer, count,
                    x + SLOT_SIZE - countWidth - 1,
                    y + SLOT_SIZE - this.textRenderer.fontHeight,
                    COUNT_COLOR
                );
            }
        }
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final PanelHit hit = panelAt(mouseX, mouseY);
        if (hit == null || hit.slot() < 0) {
            return;
        }
        final Panel panel = hit.panel();
        final Slot entry = panel.slots().get(hit.slot());
        final ItemStack icon = entry.icon();
        final List<Text> lines = new ArrayList<>();
        if (icon != null) {
            final Text name = icon.getName();
            lines.add(Texts.literal(name.getString()));
        } else {
            lines.add(Texts.literal(entry.rawName()));
        }
        final List<ChestLootPreview.LootEnchantment> enchantments = entry.enchantments();
        for (int index = 0; index < enchantments.size(); index++) {
            final ChestLootPreview.LootEnchantment enchantment = enchantments.get(index);
            lines.add(styledLine(enchantmentLine(enchantment.name(), enchantment.level())));
        }
        if (!entry.effect().isEmpty()) {
            lines.add(styledLine(effectLine(entry.effect())));
        }
        draw.drawTooltip(this, this.textRenderer, lines, mouseX, mouseY);
    }

    private static MutableText styledLine(final String text) {
        // Off the literal() chain: a mapped call on a chain loses its rename on the 26.x line.
        final MutableText line = Texts.literal(text);
        final MutableText styled = line.formatted(Formatting.GRAY);
        return styled;
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
        if (amount != 0 && scrollableAt(mouseX, mouseY)) {
            scrollOffset = scrolledOffset(amount);
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    private boolean scrollableAt(final double mouseX, final double mouseY) {
        if (panels == null || panels.isEmpty()) {
            return false;
        }
        if (mouseY < LIST_TOP || mouseY >= height - LIST_BOTTOM_MARGIN) {
            return false;
        }
        final int left = panelLeft();
        return mouseX >= left && mouseX < left + PANEL_WIDTH + 6;
    }

    private int scrolledOffset(final double amount) {
        final int maxOffset = Math.max(0, contentHeight() - viewportHeight());
        return Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(amount) * SCROLL_STEP));
    }

    private PanelHit panelAt(final double mouseX, final double mouseY) {
        if (panels == null || panels.isEmpty()) {
            return null;
        }
        final int left = panelLeft();
        if (mouseX < left
            || mouseX >= left + PANEL_WIDTH
            || mouseY < LIST_TOP
            || mouseY >= height - LIST_BOTTOM_MARGIN) {
            return null;
        }
        final VisibleStart start = visibleStart();
        int top = LIST_TOP - start.skipped();
        for (int index = start.index(); index < panels.size(); index++) {
            final Panel panel = panels.get(index);
            final int height = panelHeight(panel);
            if (mouseY < top + height) {
                return new PanelHit(panel, hoveredSlotIn(panel, left, top, mouseX, mouseY));
            }
            top += height + PANEL_GAP;
        }
        return null;
    }

    private int hoveredSlotIn(
        final Panel panel,
        final int left,
        final int top,
        final double mouseX,
        final double mouseY
    ) {
        if (!panel.rolled()) {
            return -1;
        }
        final int column = (int) Math.floor(mouseX) - gridLeft(left);
        final int row = (int) Math.floor(mouseY) - top - TITLE_STRIP;
        if (column < 0 || column >= GRID_COLUMNS * SLOT_SIZE || row < 0) {
            return -1;
        }
        final int slot = (row / SLOT_SIZE) * GRID_COLUMNS + column / SLOT_SIZE;
        return slot < panel.slots().size() ? slot : -1;
    }

    /** Index and pixel skip of the first panel that pokes below {@code scrollOffset}. */
    private record VisibleStart(int index, int skipped) {
    }

    private record PanelHit(Panel panel, int slot) {
    }

    private VisibleStart visibleStart() {
        int top = 0;
        for (int index = 0; index < panels.size(); index++) {
            final int height = panelHeight(panels.get(index));
            if (top + height > scrollOffset) {
                return new VisibleStart(index, scrollOffset - top);
            }
            top += height + PANEL_GAP;
        }
        return new VisibleStart(panels.size(), 0);
    }

    private int contentHeight() {
        int height = 0;
        for (int index = 0; index < panels.size(); index++) {
            height += panelHeight(panels.get(index)) + PANEL_GAP;
        }
        return Math.max(0, height - PANEL_GAP);
    }

    private int viewportHeight() {
        return Math.max(1, height - LIST_TOP - LIST_BOTTOM_MARGIN);
    }

    private int panelLeft() {
        return (width - PANEL_WIDTH) / 2;
    }

    private int gridLeft(final int left) {
        return left + (PANEL_WIDTH - GRID_COLUMNS * SLOT_SIZE) / 2;
    }

    private static int gridRows(final Panel panel) {
        return Math.max(1, (panel.slots().size() + GRID_COLUMNS - 1) / GRID_COLUMNS);
    }

    private static int panelHeight(final Panel panel) {
        final int body = panel.rolled() ? gridRows(panel) * SLOT_SIZE : NOTE_HEIGHT;
        return TITLE_STRIP + body + 7;
    }

    private void drawCentered(final GuiDraw draw, final String text, final int y, final int color) {
        draw.drawTextWithShadow(
            this.textRenderer,
            text,
            width / 2f - this.textRenderer.getWidth(text) / 2f,
            y,
            color
        );
    }

    static List<Panel> buildPanels(
        final List<ChestLootPreview> previews,
        final LootItemResolver resolver
    ) {
        final List<Panel> panels = new ArrayList<>();
        for (int index = 0; index < previews.size(); index++) {
            final ChestLootPreview preview = previews.get(index);
            final ChestLootPreview.ChestLoot chest = preview.chest();
            final List<Slot> slots = new ArrayList<>();
            if (preview.rolled()) {
                final List<ChestLootPreview.LootStack> stacks = preview.stacks();
                for (int stack = 0; stack < stacks.size(); stack++) {
                    final ChestLootPreview.LootStack lootStack = stacks.get(stack);
                    final ItemStack icon = resolver.resolve(lootStack.itemName());
                    slots.add(new Slot(
                        icon,
                        icon == null ? lootStack.itemName() : null,
                        lootStack.count(),
                        lootStack.enchantments(),
                        lootStack.effect()
                    ));
                }
            }
            panels.add(new Panel(
                chest.lootTable(),
                chest.blockX(),
                chest.blockY(),
                chest.blockZ(),
                preview.rolled(),
                List.copyOf(slots)
            ));
        }
        return List.copyOf(panels);
    }

    /** Loot table id ("chests/x") to its lang key; a table outside chests/ has no key. */
    static String tableKey(final String table) {
        if (!table.startsWith("chests/")) {
            return "";
        }
        return TABLE_KEY_PREFIX + table.substring("chests/".length());
    }

    static String tableName(final String table) {
        return translatedOr(tableKey(table), table);
    }

    /**
     * Translates {@code key}, treating "renders as the key itself" as a miss: unknown keys
     * render verbatim both in tests (mod lang files are not loaded) and on clients, and both
     * must fall back.
     */
    static String translatedOr(final String key, final String fallback) {
        if (key.isEmpty()) {
            return fallback;
        }
        final String text = Texts.translatable(key).getString();
        return key.equals(text) ? fallback : text;
    }

    static String romanNumeral(final int value) {
        if (value <= 0 || value > 3999) {
            return Integer.toString(value);
        }
        final StringBuilder roman = new StringBuilder();
        int remaining = value;
        for (int index = 0; index < ROMAN_VALUES.length; index++) {
            while (remaining >= ROMAN_VALUES[index]) {
                roman.append(ROMAN_NUMERALS[index]);
                remaining -= ROMAN_VALUES[index];
            }
        }
        return roman.toString();
    }

    /** Ticks to vanilla's m:ss potion clock; no clock for instant effects (<= 0 ticks). */
    static String effectClock(final int ticks) {
        if (ticks <= 0) {
            return "";
        }
        return (ticks / 1200) + ":" + String.format("%02d", ticks / 20 % 60);
    }

    static String enchantmentLine(final String name, final int level) {
        return translatedOr("enchantment.minecraft." + name, name) + " " + romanNumeral(level);
    }

    /**
     * Formats the roll's effect descriptor ({@code "<effect_name> <duration>"} as one tooltip
     * line: the translated effect name with vanilla's "(m:ss)" clock when a duration exists.
     */
    static String effectLine(final String descriptor) {
        final int space = descriptor.lastIndexOf(' ');
        int ticks = -1;
        if (space >= 0) {
            try {
                ticks = Integer.parseInt(descriptor.substring(space + 1).trim());
            } catch (final NumberFormatException malformed) {
                ticks = -1;
            }
        }
        final boolean timed = space >= 0 && ticks >= 0;
        final String name = timed ? descriptor.substring(0, space) : descriptor;
        final String clock = effectClock(timed ? ticks : -1);
        final String id = name.startsWith("minecraft:")
            ? name.substring("minecraft:".length())
            : name;
        final String translated = translatedOr("effect.minecraft." + id, id);
        return translated + (clock.isEmpty() ? "" : " (" + clock + ")");
    }

    /**
     * One chest's preview: its loot table id, block position, roll state, and rolled slots.
     * {@code rolled=false} leaves {@code slots} empty - the panel shows a note instead of the
     * grid (table or version not rollable).
     */
    record Panel(String table, int blockX, int blockY, int blockZ, boolean rolled, List<Slot> slots) {
    }

    /**
     * One grid cell: the display icon (a count-1 stack; the badge is drawn like vanilla's),
     * the raw item name when the registry has no such item, the roll count, and the
     * enchantment and effect data the hover tooltip formats.
     */
    record Slot(
        ItemStack icon,
        String rawName,
        int count,
        List<ChestLootPreview.LootEnchantment> enchantments,
        String effect
    ) {
    }
}
