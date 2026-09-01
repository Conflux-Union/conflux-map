package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.BiomeTable;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.StructureIconCatalog;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;

/** Shared structure/biome picker with localized filtering and candidate browsing. */
final class StructureSearchScreen extends ConfluxScreen {
    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;
    private static final int MODE_TOP = 30;
    private static final int FIELD_TOP = 54;
    private static final int PROMPT_TOP = 78;
    private static final int MASTER_TOP = 92;
    private static final int LIST_TOP = 116;
    private static final int ROW_HEIGHT = 24;
    private static final int CHECKBOX_SIZE = 20;
    private static final int ICON_SIZE = 16;
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
    private final List<Identifier> biomes = new ArrayList<>();
    private final SplitMapPane mapPane;
    private final Map<StructureIndex.StructureType, ButtonWidget> visibilityButtons =
        new EnumMap<>(StructureIndex.StructureType.class);
    private final Map<StructureIndex.StructureType, ButtonWidget> locateButtons =
        new EnumMap<>(StructureIndex.StructureType.class);
    private final Map<Identifier, ButtonWidget> biomeLocateButtons = new HashMap<>();

    private TextFieldWidget searchField;
    private ButtonWidget modeButton;
    private ButtonWidget masterButton;
    private MapSearchMode mode;
    private String observedQuery = "";
    private int scrollOffset;
    private int filteredCount;
    private int rowX;
    private int rowWidth;
    private int locateWidth;
    private int panelContentWidth = 1;
    private boolean draggingScrollBar;
    private double scrollBarGrabOffset;

    StructureSearchScreen(
        final FullscreenMapScreen parent,
        final StructureMarkerService structures,
        final DimensionId dimension,
        final List<StructureIndex.StructureType> available
    ) {
        super(Texts.translatable("confluxmap.screen.map_search.title"));
        this.parent = parent;
        this.structures = structures;
        this.dimension = dimension;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.config = app.config();
        this.companion = app.companionSession();
        this.mapPane = new SplitMapPane(parent);
        this.available = new ArrayList<>(available);
        this.available.sort(Comparator.comparing(StructureSearchScreen::localizedName));
        if (MinecraftClient.getInstance().world != null) {
            for (final Identifier id : Regs.biomes(MinecraftClient.getInstance().world).getIds()) {
                final OptionalInt cubiomesId = id.getNamespace().equals("minecraft")
                    ? CubiomesBiomeIds.idForName(id.getPath())
                    : OptionalInt.empty();
                if (cubiomesId.isEmpty() || BiomeTable.supports(dimension, cubiomesId.getAsInt())) {
                    biomes.add(id);
                }
            }
        }
        biomes.sort(Comparator.comparing(StructureSearchScreen::localizedBiomeName));
        mode = companion.structureSearchAllowed() && !available.isEmpty()
            ? MapSearchMode.STRUCTURE
            : MapSearchMode.BIOME;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        if (mode == MapSearchMode.STRUCTURE
            && companion.structureSearchAllowed()
            && !config.predictionShowStructures) {
            config.predictionShowStructures = true;
            saveConfig();
        }
        visibilityButtons.clear();
        locateButtons.clear();
        biomeLocateButtons.clear();
        panelContentWidth = requiredPanelContentWidth();
        final SplitMapLayout layout = splitLayout();
        final int modeWidth = Math.min(160, layout.panelContentWidth());
        modeButton = addDrawableChild(Widgets.button(
            layout.panelCenterX() - modeWidth / 2,
            MODE_TOP,
            modeWidth,
            FIELD_HEIGHT,
            modeLabel(),
            ignored -> switchMode()
        ));
        final int fieldWidth = Math.min(FIELD_WIDTH, layout.panelContentWidth());
        searchField = new TextFieldWidget(
            this.textRenderer,
            layout.panelCenterX() - fieldWidth / 2,
            FIELD_TOP,
            fieldWidth,
            FIELD_HEIGHT,
            Texts.translatable(
                mode == MapSearchMode.STRUCTURE
                    ? "confluxmap.screen.structure_search.field"
                    : "confluxmap.screen.biome_search.field"
            )
        );
        searchField.setMaxLength(64);
        searchField.setText(observedQuery);
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        final int listWidth = Math.max(
            1, layout.panelContentWidth() - SCROLLBAR_GAP - SCROLLBAR_WIDTH
        );
        rowWidth = Math.min(420, listWidth);
        rowX = layout.panelContentLeft() + (listWidth - rowWidth) / 2;
        masterButton = addDrawableChild(Widgets.button(
            rowX,
            MASTER_TOP,
            CHECKBOX_SIZE,
            CHECKBOX_SIZE,
            masterCheckboxLabel(),
            ignored -> toggleFilteredVisibility()
        ));
        updateActionWidths();
        for (final StructureIndex.StructureType type : available) {
            final ButtonWidget visibility = addDrawableChild(Widgets.button(
                rowX,
                LIST_TOP,
                CHECKBOX_SIZE,
                CHECKBOX_SIZE,
                checkboxLabel(type),
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
        for (final Identifier biome : biomes) {
            final ButtonWidget locate = addDrawableChild(Widgets.button(
                rowX + rowWidth - locateWidth,
                LIST_TOP,
                locateWidth,
                20,
                Texts.translatable("confluxmap.screen.structure_search.locate"),
                ignored -> locate(biome)
            ));
            biomeLocateButtons.put(biome, locate);
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
        final int labelAndSpacing = mode == MapSearchMode.STRUCTURE
            ? CHECKBOX_SIZE + ICON_SIZE + BUTTON_GAP * 4 + 16
            : BUTTON_GAP * 2 + 16;
        locateWidth = Math.min(
            LOCATE_WIDTH,
            Math.max(1, rowWidth - labelAndSpacing)
        );
    }

    private int requiredPanelContentWidth() {
        int longestNameWidth = 0;
        for (final StructureIndex.StructureType type : available) {
            longestNameWidth = Math.max(
                longestNameWidth,
                this.textRenderer.getWidth(localizedName(type))
            );
        }
        for (final Identifier biome : biomes) {
            longestNameWidth = Math.max(
                longestNameWidth,
                this.textRenderer.getWidth(localizedBiomeName(biome))
            );
        }
        final int locateLabelWidth = Math.min(
            LOCATE_WIDTH,
            this.textRenderer.getWidth(
                Texts.translatable("confluxmap.screen.structure_search.locate")
            ) + 8
        );
        final int rowLeadWidth = mode == MapSearchMode.STRUCTURE
            ? CHECKBOX_SIZE + ICON_SIZE + BUTTON_GAP * 2
            : 0;
        final int rowContentWidth = rowLeadWidth + locateLabelWidth
            + longestNameWidth + BUTTON_GAP * 4 + SCROLLBAR_GAP + SCROLLBAR_WIDTH;
        return Math.max(
            rowContentWidth,
            Math.max(
                this.textRenderer.getWidth(getTitle()) + 16,
                Math.max(
                    this.textRenderer.getWidth(
                        Texts.translatable("confluxmap.screen.structure_search.prompt")
                    ) + 16,
                    this.textRenderer.getWidth(
                        Texts.translatable("confluxmap.screen.structure_search.field")
                    ) + 16
                )
            )
        );
    }

    private SplitMapLayout splitLayout() {
        return new SplitMapLayout(width, height, panelContentWidth);
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

    private void switchMode() {
        mode = mode.toggle();
        observedQuery = "";
        scrollOffset = 0;
        clearChildren();
        init();
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

    private void locate(final Identifier biome) {
        MinecraftAccess.setScreen(
            MinecraftClient.getInstance(),
            new BiomeCandidateScreen(this, parent, dimension, biome)
        );
    }

    private void toggleTypeVisibility(final StructureIndex.StructureType type) {
        if (!companion.structureSearchAllowed()) {
            return;
        }
        final boolean visible = isVisible(type);
        config.predictionStructureVisibility.setVisible(
            structures.mcVersion(), dimension, type, !visible
        );
        visibilityButtons.get(type).setMessage(checkboxLabel(type));
        masterButton.setMessage(masterCheckboxLabel());
        saveConfig();
    }

    private void toggleFilteredVisibility() {
        setFilteredVisibility(selectionState().visibilityAfterToggle());
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
            visibilityButtons.get(type).setMessage(checkboxLabel(type));
        }
        masterButton.setMessage(masterCheckboxLabel());
        saveConfig();
    }

    private boolean isVisible(final StructureIndex.StructureType type) {
        return config.predictionStructureVisibility.isVisible(
            structures.mcVersion(), dimension, type
        );
    }

    private StructureSelectionState selectionState() {
        final List<StructureIndex.StructureType> filtered = filteredTypes();
        int selected = 0;
        for (final StructureIndex.StructureType type : filtered) {
            if (isVisible(type)) {
                selected++;
            }
        }
        return new StructureSelectionState(selected, filtered.size());
    }

    private Text masterCheckboxLabel() {
        return Texts.literal(selectionState().mark());
    }

    private Text checkboxLabel(final StructureIndex.StructureType type) {
        return Texts.literal(isVisible(type) ? "✓" : "");
    }

    private void saveConfig() {
        ConfluxMapClient.get().configIo().save(config);
    }

    private void updatePolicyAccess() {
        final boolean allowed = companion.structureSearchAllowed();
        final boolean currentModeAllowed = mode.allowed(allowed);
        final String reasonKey = allowed
            ? null
            : "confluxmap.map.structure_search.disabled_by_server";
        if (masterButton != null) {
            masterButton.active = selectionState().enabled(currentModeAllowed);
            setDisabledTooltip(masterButton, reasonKey);
        }
        for (final ButtonWidget button : visibilityButtons.values()) {
            button.active = currentModeAllowed;
            setDisabledTooltip(button, reasonKey);
        }
        for (final ButtonWidget button : locateButtons.values()) {
            button.active = currentModeAllowed;
            setDisabledTooltip(button, reasonKey);
        }
        for (final ButtonWidget button : biomeLocateButtons.values()) {
            button.active = true;
        }
    }

    private void updateRows() {
        final List<StructureIndex.StructureType> filtered = filteredTypes();
        final List<Identifier> filteredBiomes = filteredBiomes();
        filteredCount = mode.itemCount(filtered.size(), filteredBiomes.size());
        masterButton.visible = mode == MapSearchMode.STRUCTURE;
        if (mode == MapSearchMode.STRUCTURE) {
            final StructureSelectionState selection = selectionState();
            masterButton.setMessage(Texts.literal(selection.mark()));
            masterButton.active = selection.enabled(companion.structureSearchAllowed());
        }
        final int visibleRows = visibleRows();
        scrollOffset = Math.max(
            0, Math.min(scrollOffset, Math.max(0, filteredCount - visibleRows))
        );
        for (final ButtonWidget button : visibilityButtons.values()) {
            button.visible = false;
        }
        for (final ButtonWidget button : locateButtons.values()) {
            button.visible = false;
        }
        for (final ButtonWidget button : biomeLocateButtons.values()) {
            button.visible = false;
        }
        if (mode == MapSearchMode.STRUCTURE) {
            final int end = Math.min(filtered.size(), scrollOffset + visibleRows());
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
        } else {
            final int end = Math.min(filteredBiomes.size(), scrollOffset + visibleRows());
            for (int index = scrollOffset; index < end; index++) {
                final ButtonWidget locate = biomeLocateButtons.get(filteredBiomes.get(index));
                Widgets.setY(locate, LIST_TOP + (index - scrollOffset) * ROW_HEIGHT);
                locate.visible = true;
            }
        }
    }

    private List<StructureIndex.StructureType> filteredTypes() {
        if (mode != MapSearchMode.STRUCTURE) {
            return List.of();
        }
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

    private List<Identifier> filteredBiomes() {
        if (mode != MapSearchMode.BIOME) {
            return List.of();
        }
        final String query = observedQuery.trim().toLowerCase(Locale.ROOT);
        if (query.isEmpty()) {
            return List.copyOf(biomes);
        }
        final List<Identifier> filtered = new ArrayList<>();
        for (final Identifier biome : biomes) {
            final String name = localizedBiomeName(biome).toLowerCase(Locale.ROOT);
            final String id = biome.toString().replace('_', ' ').toLowerCase(Locale.ROOT);
            if (name.contains(query) || id.contains(query)) {
                filtered.add(biome);
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
        final String prompt = Texts.translatable(
            mode == MapSearchMode.STRUCTURE
                ? "confluxmap.screen.structure_search.prompt"
                : "confluxmap.screen.biome_search.prompt"
        ).getString();
        drawPanelCentered(draw, prompt, PROMPT_TOP, 0xFFBBBBBB, layout);
        final List<StructureIndex.StructureType> filtered = filteredTypes();
        final List<Identifier> filteredBiomes = filteredBiomes();
        final int itemCount = mode == MapSearchMode.STRUCTURE
            ? filtered.size()
            : filteredBiomes.size();
        final int end = Math.min(itemCount, scrollOffset + visibleRows());
        StructureSearchScrollBar.drawListSurface(
            draw,
            rowX - 4,
            LIST_TOP - 2,
            rowWidth + StructureSearchScrollBar.trackWidth() + 10,
            visibleRows() * ROW_HEIGHT + 2,
            ROW_HEIGHT
        );
        for (int index = scrollOffset; index < end; index++) {
            final int rowY = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            if (mode == MapSearchMode.STRUCTURE) {
                final StructureIndex.StructureType type = filtered.get(index);
                final int iconX = rowX + CHECKBOX_SIZE + BUTTON_GAP;
                StructureIconCatalog.draw(draw, type, iconX, rowY + 2, ICON_SIZE, 0xFFFFFFFF);
                final int labelWidth = Math.max(
                    8,
                    rowWidth - CHECKBOX_SIZE - ICON_SIZE - locateWidth - BUTTON_GAP * 4
                );
                final String name = this.textRenderer.trimToWidth(localizedName(type), labelWidth);
                draw.drawTextWithShadow(
                    this.textRenderer,
                    name,
                    iconX + ICON_SIZE + BUTTON_GAP,
                    rowY + 6,
                    isVisible(type) ? 0xFFFFFFFF : 0xFF888888
                );
            } else {
                final String name = this.textRenderer.trimToWidth(
                    localizedBiomeName(filteredBiomes.get(index)),
                    Math.max(8, rowWidth - locateWidth - BUTTON_GAP * 2)
                );
                draw.drawTextWithShadow(
                    this.textRenderer, name, rowX, rowY + 6, 0xFFFFFFFF
                );
            }
        }
        if (filteredCount == 0) {
            final String empty = Texts.translatable(
                mode == MapSearchMode.STRUCTURE
                    ? "confluxmap.screen.structure_search.empty"
                    : "confluxmap.screen.biome_search.empty"
            ).getString();
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

    private Text modeLabel() {
        return Texts.translatable(
            mode == MapSearchMode.STRUCTURE
                ? "confluxmap.screen.map_search.mode.structure"
                : "confluxmap.screen.map_search.mode.biome"
        );
    }

    static String localizedBiomeName(final Identifier biome) {
        final String key = Util.createTranslationKey("biome", biome);
        final String translated = Texts.translatable(key).getString();
        return translated.equals(key) ? biome.toString() : translated;
    }
}
