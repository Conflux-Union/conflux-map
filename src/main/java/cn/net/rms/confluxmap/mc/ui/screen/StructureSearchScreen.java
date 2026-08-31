package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.StructureIconCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Localized structure-type picker with visibility controls and candidate browsing. */
final class StructureSearchScreen extends ConfluxScreen {
    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;
    private static final int MASTER_TOP = 62;
    private static final int BULK_TOP = 86;
    private static final int LIST_TOP = 124;
    private static final int ROW_HEIGHT = 24;
    private static final int ICON_SIZE = 16;
    private static final int TOGGLE_WIDTH = 58;
    private static final int LOCATE_WIDTH = 62;
    private static final int BUTTON_GAP = 3;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 4;

    private final FullscreenMapScreen parent;
    private final StructureMarkerService structures;
    private final DimensionId dimension;
    private final ConfluxConfig config;
    private final CompanionSession companion;
    private final List<StructureIndex.StructureType> available;
    private final SplitMapPane mapPane;
    private final Map<StructureIndex.StructureType, ButtonWidget> visibilityButtons =
        new EnumMap<>(StructureIndex.StructureType.class);
    private final Map<StructureIndex.StructureType, ButtonWidget> locateButtons =
        new EnumMap<>(StructureIndex.StructureType.class);

    private TextFieldWidget searchField;
    private ButtonWidget masterButton;
    private ButtonWidget selectAllButton;
    private ButtonWidget selectNoneButton;
    private String observedQuery = "";
    private int scrollOffset;
    private int filteredCount;
    private int rowX;
    private int rowWidth;
    private int toggleWidth;
    private int locateWidth;
    private boolean draggingScrollBar;
    private double scrollBarGrabOffset;

    StructureSearchScreen(
        final FullscreenMapScreen parent,
        final StructureMarkerService structures,
        final DimensionId dimension,
        final List<StructureIndex.StructureType> available
    ) {
        super(Texts.translatable("confluxmap.screen.structure_search.title"));
        this.parent = parent;
        this.structures = structures;
        this.dimension = dimension;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.config = app.config();
        this.companion = app.companionSession();
        this.mapPane = new SplitMapPane(parent);
        this.available = new ArrayList<>(available);
        this.available.sort(Comparator.comparing(StructureSearchScreen::localizedName));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        visibilityButtons.clear();
        locateButtons.clear();
        final SplitMapLayout layout = splitLayout();
        final int fieldWidth = Math.min(FIELD_WIDTH, layout.panelContentWidth());
        searchField = new TextFieldWidget(
            this.textRenderer,
            layout.panelCenterX() - fieldWidth / 2,
            38,
            fieldWidth,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.structure_search.field")
        );
        searchField.setMaxLength(64);
        searchField.setText(observedQuery);
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        final int masterWidth = Math.min(240, layout.panelContentWidth());
        masterButton = addDrawableChild(Widgets.button(
            layout.panelCenterX() - masterWidth / 2,
            MASTER_TOP,
            masterWidth,
            20,
            masterLabel(),
            ignored -> toggleMasterVisibility()
        ));
        final int bulkWidth = (masterWidth - BUTTON_GAP) / 2;
        selectAllButton = addDrawableChild(Widgets.button(
            layout.panelCenterX() - masterWidth / 2,
            BULK_TOP,
            bulkWidth,
            20,
            Texts.translatable("confluxmap.screen.structure_search.select_all"),
            ignored -> setFilteredVisibility(true)
        ));
        selectNoneButton = addDrawableChild(Widgets.button(
            layout.panelCenterX() - masterWidth / 2 + bulkWidth + BUTTON_GAP,
            BULK_TOP,
            masterWidth - bulkWidth - BUTTON_GAP,
            20,
            Texts.translatable("confluxmap.screen.structure_search.select_none"),
            ignored -> setFilteredVisibility(false)
        ));

        final int listWidth = Math.max(
            1, layout.panelContentWidth() - SCROLLBAR_GAP - SCROLLBAR_WIDTH
        );
        rowWidth = Math.min(420, listWidth);
        rowX = layout.panelContentLeft() + (listWidth - rowWidth) / 2;
        updateActionWidths();
        for (final StructureIndex.StructureType type : available) {
            final ButtonWidget visibility = addDrawableChild(Widgets.button(
                rowX + rowWidth - locateWidth - BUTTON_GAP - toggleWidth,
                LIST_TOP,
                toggleWidth,
                20,
                visibilityLabel(type),
                ignored -> toggleTypeVisibility(type)
            ));
            visibilityButtons.put(type, visibility);
            final ButtonWidget locate = addDrawableChild(Widgets.button(
                rowX + rowWidth - locateWidth,
                LIST_TOP,
                locateWidth,
                20,
                Texts.translatable("confluxmap.screen.structure_search.locate"),
                ignored -> locate(type)
            ));
            locateButtons.put(type, locate);
        }
        final int backWidth = Math.min(100, layout.panelContentWidth());
        addDrawableChild(Widgets.button(
            layout.panelCenterX() - backWidth / 2,
            height - 28,
            backWidth,
            20,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
        updatePolicyAccess();
        updateRows();
    }

    private void updateActionWidths() {
        final int labelAndSpacing = ICON_SIZE + BUTTON_GAP * 3 + 16;
        final int available = Math.max(2, rowWidth - labelAndSpacing);
        locateWidth = Math.min(LOCATE_WIDTH, Math.max(1, (available + 1) / 2));
        toggleWidth = Math.min(TOGGLE_WIDTH, Math.max(1, available - locateWidth));
    }

    private SplitMapLayout splitLayout() {
        return new SplitMapLayout(width, height);
    }

    @Override
    public void tick() {
        Widgets.tick(searchField);
        updatePolicyAccess();
        final String query = searchField == null ? "" : searchField.getText();
        if (!query.equals(observedQuery)) {
            observedQuery = query;
            scrollOffset = 0;
            updateRows();
        }
    }

    @Override
    public void onClose() {
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    private void locate(final StructureIndex.StructureType type) {
        if (!companion.structureSearchAllowed()) {
            return;
        }
        MinecraftAccess.setScreen(
            MinecraftClient.getInstance(),
            new StructureCandidateScreen(this, parent, structures, dimension, type)
        );
    }

    private void toggleMasterVisibility() {
        if (!companion.structureSearchAllowed()) {
            return;
        }
        config.predictionShowStructures = !config.predictionShowStructures;
        masterButton.setMessage(masterLabel());
        saveConfig();
    }

    private void toggleTypeVisibility(final StructureIndex.StructureType type) {
        if (!companion.structureSearchAllowed()) {
            return;
        }
        final boolean visible = isVisible(type);
        config.predictionStructureVisibility.setVisible(
            structures.mcVersion(), dimension, type, !visible
        );
        visibilityButtons.get(type).setMessage(visibilityLabel(type));
        saveConfig();
    }

    private void setFilteredVisibility(final boolean visible) {
        if (!companion.structureSearchAllowed()) {
            return;
        }
        final List<StructureIndex.StructureType> targets = filteredTypes();
        if (targets.isEmpty()) {
            return;
        }
        for (final StructureIndex.StructureType type : targets) {
            config.predictionStructureVisibility.setVisible(
                structures.mcVersion(), dimension, type, visible
            );
            visibilityButtons.get(type).setMessage(visibilityLabel(type));
        }
        saveConfig();
    }

    private boolean isVisible(final StructureIndex.StructureType type) {
        return config.predictionStructureVisibility.isVisible(
            structures.mcVersion(), dimension, type
        );
    }

    private Text masterLabel() {
        return Texts.translatable(
            "confluxmap.screen.structure_search.master",
            Texts.translatable(config.predictionShowStructures ? "confluxmap.value.on" : "confluxmap.value.off")
                .getString()
        );
    }

    private Text visibilityLabel(final StructureIndex.StructureType type) {
        return Texts.translatable(isVisible(type) ? "confluxmap.value.on" : "confluxmap.value.off");
    }

    private void saveConfig() {
        ConfluxMapClient.get().configIo().save(config);
    }

    private void updatePolicyAccess() {
        final boolean allowed = companion.structureSearchAllowed();
        final String reasonKey = allowed
            ? null
            : "confluxmap.map.structure_search.disabled_by_server";
        if (masterButton != null) {
            masterButton.active = allowed;
            setDisabledTooltip(masterButton, reasonKey);
        }
        if (selectAllButton != null) {
            selectAllButton.active = allowed;
            setDisabledTooltip(selectAllButton, reasonKey);
        }
        if (selectNoneButton != null) {
            selectNoneButton.active = allowed;
            setDisabledTooltip(selectNoneButton, reasonKey);
        }
        for (final ButtonWidget button : visibilityButtons.values()) {
            button.active = allowed;
            setDisabledTooltip(button, reasonKey);
        }
        for (final ButtonWidget button : locateButtons.values()) {
            button.active = allowed;
            setDisabledTooltip(button, reasonKey);
        }
    }

    private void updateRows() {
        if (visibilityButtons.isEmpty()) {
            filteredCount = 0;
            return;
        }
        final List<StructureIndex.StructureType> filtered = filteredTypes();
        filteredCount = filtered.size();
        final int visibleRows = visibleRows();
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, filtered.size() - visibleRows)));
        for (final ButtonWidget button : visibilityButtons.values()) {
            button.visible = false;
        }
        for (final ButtonWidget button : locateButtons.values()) {
            button.visible = false;
        }
        final int end = Math.min(filtered.size(), scrollOffset + visibleRows);
        for (int index = scrollOffset; index < end; index++) {
            final StructureIndex.StructureType type = filtered.get(index);
            final int y = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            final ButtonWidget visibility = visibilityButtons.get(type);
            final ButtonWidget locate = locateButtons.get(type);
            Widgets.setY(visibility, y);
            Widgets.setY(locate, y);
            visibility.visible = true;
            locate.visible = true;
        }
    }

    private List<StructureIndex.StructureType> filteredTypes() {
        final String query = observedQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return List.copyOf(available);
        }
        final List<StructureIndex.StructureType> filtered = new ArrayList<>();
        for (final StructureIndex.StructureType type : available) {
            final String name = localizedName(type).toLowerCase(Locale.ROOT);
            final String id = type.id().replace('_', ' ').toLowerCase(Locale.ROOT);
            if (name.contains(query) || id.contains(query)) {
                filtered.add(type);
            }
        }
        return filtered;
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 38) / ROW_HEIGHT);
    }

    private ScrollBarModel scrollBar() {
        return ScrollBarModel.of(
            LIST_TOP,
            visibleRows() * ROW_HEIGHT - 4,
            filteredCount,
            visibleRows(),
            scrollOffset
        );
    }

    private int scrollBarX() {
        return rowX + rowWidth + SCROLLBAR_GAP;
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
        final SplitMapLayout layout = splitLayout();
        if (amount != 0 && layout.containsPanel(mouseX, mouseY)
            && filteredCount > visibleRows()) {
            scrollOffset -= (int) Math.signum(amount);
            updateRows();
            return true;
        }
        if (mapPane.mouseScrolled(mouseX, mouseY, amount, layout)) {
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
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
        final ScrollBarModel bar = scrollBar();
        if (button == 0 && bar.visible()
            && mouseX >= scrollBarX() && mouseX < scrollBarX() + SCROLLBAR_WIDTH
            && mouseY >= bar.trackTop() && mouseY < bar.trackTop() + bar.trackHeight()) {
            draggingScrollBar = true;
            scrollBarGrabOffset = mouseY >= bar.thumbTop()
                && mouseY < bar.thumbTop() + bar.thumbHeight()
                ? mouseY - bar.thumbTop()
                : bar.thumbHeight() / 2.0;
            updateScrollFromMouse(mouseY);
            return true;
        }
        //#if MC>=12109
        //$$ if (super.mouseClicked(click, doubledClick)) {
        //#else
        if (super.mouseClicked(mouseX, mouseY, button)) {
        //#endif
            return true;
        }
        return mapPane.mouseClicked(mouseX, mouseY, button, splitLayout());
    }

    @Override
    //#if MC>=12109
    //$$ public boolean mouseDragged(final Click click, final double deltaX, final double deltaY) {
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
        if (button == 0 && draggingScrollBar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        if (mapPane.mouseDragged(button, deltaX, deltaY)) {
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
        if (button == 0 && draggingScrollBar) {
            draggingScrollBar = false;
            return true;
        }
        if (mapPane.mouseReleased(button)) {
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    private void updateScrollFromMouse(final double mouseY) {
        scrollOffset = scrollBar().offsetForThumbTop(mouseY - scrollBarGrabOffset);
        updateRows();
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        final SplitMapLayout layout = splitLayout();
        mapPane.render(draw, mouseX, mouseY, tickDelta, layout);
        drawPanelCentered(draw, getTitle().getString(), 12, 0xFFFFFFFF, layout);
        final String prompt = Texts.translatable("confluxmap.screen.structure_search.prompt").getString();
        drawPanelCentered(draw, prompt, 111, 0xFFBBBBBB, layout);
        final List<StructureIndex.StructureType> filtered = filteredTypes();
        final int end = Math.min(filtered.size(), scrollOffset + visibleRows());
        StructureSearchScrollBar.drawListSurface(
            draw,
            rowX - 4,
            LIST_TOP - 2,
            rowWidth + StructureSearchScrollBar.trackWidth() + 10,
            visibleRows() * ROW_HEIGHT + 2,
            ROW_HEIGHT
        );
        for (int index = scrollOffset; index < end; index++) {
            final StructureIndex.StructureType type = filtered.get(index);
            final int rowY = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            StructureIconCatalog.draw(draw, type, rowX, rowY + 2, ICON_SIZE, 0xFFFFFFFF);
            final int labelWidth = Math.max(
                8,
                rowWidth - ICON_SIZE - toggleWidth - locateWidth - BUTTON_GAP * 3
            );
            final String name = this.textRenderer.trimToWidth(localizedName(type), labelWidth);
            draw.drawTextWithShadow(
                this.textRenderer,
                name,
                rowX + ICON_SIZE + BUTTON_GAP,
                rowY + 6,
                isVisible(type) ? 0xFFFFFFFF : 0xFF888888
            );
        }
        if (filteredCount == 0) {
            final String empty = Texts.translatable("confluxmap.screen.structure_search.empty").getString();
            drawPanelCentered(draw, empty, LIST_TOP + 6, 0xFFAAAAAA, layout);
        }
        final ScrollBarModel bar = scrollBar();
        if (bar.visible()) {
            draw.fill(
                scrollBarX(), bar.trackTop(),
                scrollBarX() + SCROLLBAR_WIDTH, bar.trackTop() + bar.trackHeight(),
                0x66333333
            );
            draw.fill(
                scrollBarX(), bar.thumbTop(),
                scrollBarX() + SCROLLBAR_WIDTH, bar.thumbTop() + bar.thumbHeight(),
                0xFFAAAAAA
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
        StructureSearchScrollBar.drawOverflowCues(
            draw,
            rowX - 4,
            LIST_TOP - 2,
            rowWidth + StructureSearchScrollBar.trackWidth() + 10,
            visibleRows() * ROW_HEIGHT + 2,
            filteredCount,
            visibleRows(),
            scrollOffset
        );
    }

    private void drawPanelCentered(
        final GuiDraw draw,
        final String text,
        final int y,
        final int color,
        final SplitMapLayout layout
    ) {
        final String visibleText = fitToWidth(text, layout.panelContentWidth());
        draw.drawTextWithShadow(
            this.textRenderer,
            visibleText,
            layout.panelCenterX() - this.textRenderer.getWidth(visibleText) / 2f,
            y,
            color
        );
    }

    private String fitToWidth(final String text, final int maxWidth) {
        //#if MC>=260100
        //$$ return this.font.plainSubstrByWidth(text, maxWidth);
        //#else
        return this.textRenderer.trimToWidth(text, maxWidth);
        //#endif
    }

    private static String localizedName(final StructureIndex.StructureType type) {
        return Texts.translatable(type.translationKey()).getString();
    }
}
