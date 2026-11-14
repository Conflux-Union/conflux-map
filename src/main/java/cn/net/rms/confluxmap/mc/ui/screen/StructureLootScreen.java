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

/**
 * Previews the deterministic vanilla roll of one predicted structure's chests. The roll runs
 * once on open on a worker via {@link StructureMarkerService#lootPreviewAsync}; the render
 * thread only consumes the completed result and degrades gracefully (unknown items fall back
 * to text rows, unrolled chests show a note instead of stacks).
 */
final class StructureLootScreen extends ConfluxScreen {
    private static final int ROW_HEIGHT = 20;
    private static final int ICON_SIZE = 16;
    private static final int GAP = 4;
    private static final int TITLE_Y = 16;
    private static final int POSITION_Y = 30;
    private static final int LIST_TOP = 44;
    private static final int LIST_BOTTOM_MARGIN = 36;
    private static final int SCREEN_MARGIN = 16;
    private static final int MAX_CONTENT_WIDTH = 360;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SECONDARY_COLOR = 0xFFBBBBBB;
    private static final int DETAIL_COLOR = 0xFF999999;
    private static final int FAILURE_COLOR = 0xFFFF7777;

    private final Screen parent;
    private final StructureIndex.Marker marker;
    private final StructureMarkerService service;
    private final LootItemResolver resolver = new LootItemResolver();
    private List<Row> rows;
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
        rows = buildRows(found, resolver);
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
        if (rows == null) {
            drawCentered(
                draw,
                Texts.translatable("confluxmap.screen.structure_loot.loading").getString(),
                LIST_TOP,
                SECONDARY_COLOR
            );
            return;
        }
        if (rows.isEmpty()) {
            drawCentered(
                draw,
                Texts.translatable("confluxmap.screen.structure_loot.empty").getString(),
                LIST_TOP,
                SECONDARY_COLOR
            );
            return;
        }
        renderRows(draw);
    }

    private void renderRows(final GuiDraw draw) {
        // A resize changes visibleRows; re-clamp so a stale offset cannot blank the list.
        scrollOffset = Math.max(
            0, Math.min(scrollOffset, Math.max(0, rows.size() - visibleRows()))
        );
        final int left = rowLeft();
        final int end = Math.min(rows.size(), scrollOffset + visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            renderRow(draw, rows.get(index), left, LIST_TOP + (index - scrollOffset) * ROW_HEIGHT);
        }
        drawListScrollbar(
            draw,
            left + contentWidth() + GAP,
            LIST_TOP,
            visibleRows() * ROW_HEIGHT,
            rows.size(),
            visibleRows(),
            scrollOffset
        );
    }

    private void renderRow(final GuiDraw draw, final Row row, final int left, final int y) {
        // Row accessors stay out of chains: a mapped call whose receiver or argument is a
        // chain loses its rename on the 26.x line (getWidth/getName would not map).
        final String text = row.text();
        final String secondary = row.secondary();
        final ItemStack icon = row.icon();
        switch (row.kind()) {
            case CHEST -> {
                draw.drawTextWithShadow(this.textRenderer, text, left, textY(y), TEXT_COLOR);
                draw.drawTextWithShadow(
                    this.textRenderer,
                    secondary,
                    left + this.textRenderer.getWidth(text) + 10,
                    textY(y),
                    DETAIL_COLOR
                );
            }
            case STACK -> {
                final int countX;
                if (icon != null) {
                    draw.drawItemIcon(
                        MinecraftClient.getInstance(),
                        icon,
                        left + ICON_SIZE / 2f,
                        y + ROW_HEIGHT / 2f,
                        ICON_SIZE
                    );
                    countX = left + ICON_SIZE + GAP;
                } else {
                    draw.drawTextWithShadow(this.textRenderer, text, left, textY(y), TEXT_COLOR);
                    countX = left + this.textRenderer.getWidth(text) + GAP;
                }
                draw.drawTextWithShadow(
                    this.textRenderer,
                    Integer.toString(row.count()),
                    countX,
                    textY(y),
                    icon != null ? TEXT_COLOR : SECONDARY_COLOR
                );
            }
            case DETAIL -> draw.drawTextWithShadow(
                this.textRenderer, text, left + ICON_SIZE + GAP, textY(y), DETAIL_COLOR
            );
        }
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final Row row = rowAt(mouseX, mouseY);
        if (row == null || row.icon() == null) {
            return;
        }
        final ItemStack icon = row.icon();
        draw.drawTooltip(this, this.textRenderer, icon.getName(), mouseX, mouseY);
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
        if (amount != 0 && rowAt(mouseX, mouseY) != null) {
            scrollOffset = scrolledOffset(amount);
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    private Row rowAt(final double mouseX, final double mouseY) {
        if (rows == null || rows.isEmpty()) {
            return null;
        }
        if (mouseY < LIST_TOP || mouseX < rowLeft() || mouseX >= rowLeft() + contentWidth()) {
            return null;
        }
        final int row = (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
        if (row >= visibleRows()) {
            return null;
        }
        final int index = scrollOffset + row;
        return index < rows.size() ? rows.get(index) : null;
    }

    private int scrolledOffset(final double amount) {
        final int maxOffset = Math.max(0, rows.size() - visibleRows());
        return Math.max(0, Math.min(maxOffset, scrollOffset - (int) Math.signum(amount)));
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - LIST_BOTTOM_MARGIN) / ROW_HEIGHT);
    }

    private int contentWidth() {
        return Math.min(MAX_CONTENT_WIDTH, width - SCREEN_MARGIN * 2);
    }

    private int rowLeft() {
        return (width - contentWidth()) / 2;
    }

    private int textY(final int rowY) {
        return rowY + (ROW_HEIGHT - this.textRenderer.fontHeight) / 2;
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

    static List<Row> buildRows(final List<ChestLootPreview> previews, final LootItemResolver resolver) {
        final List<Row> rows = new ArrayList<>();
        for (final ChestLootPreview preview : previews) {
            final ChestLootPreview.ChestLoot chest = preview.chest();
            rows.add(new Row(
                Row.Kind.CHEST,
                chest.blockX() + ", " + chest.blockY() + ", " + chest.blockZ(),
                chest.lootTable(),
                null,
                0
            ));
            if (!preview.rolled()) {
                rows.add(new Row(
                    Row.Kind.DETAIL,
                    Texts.translatable("confluxmap.screen.structure_loot.roll_unavailable").getString(),
                    null,
                    null,
                    0
                ));
                continue;
            }
            for (final ChestLootPreview.LootStack stack : preview.stacks()) {
                final ItemStack icon = resolver.resolve(stack.itemName());
                rows.add(new Row(
                    Row.Kind.STACK,
                    icon == null ? stack.itemName() : null,
                    null,
                    icon,
                    stack.count()
                ));
                for (final ChestLootPreview.LootEnchantment enchantment : stack.enchantments()) {
                    rows.add(new Row(
                        Row.Kind.DETAIL,
                        enchantment.name() + " " + enchantment.level(),
                        null,
                        null,
                        0
                    ));
                }
                if (!stack.effect().isEmpty()) {
                    rows.add(new Row(Row.Kind.DETAIL, stack.effect(), null, null, 0));
                }
            }
        }
        return List.copyOf(rows);
    }

    /**
     * One display line of the loot list. CHEST: text = block position, secondary = loot table.
     * STACK: icon + count; a null icon means the item is unknown and text carries its raw name.
     * DETAIL: grey note under a chest or stack.
     */
    record Row(Kind kind, String text, String secondary, ItemStack icon, int count) {

        enum Kind { CHEST, STACK, DETAIL }
    }
}
