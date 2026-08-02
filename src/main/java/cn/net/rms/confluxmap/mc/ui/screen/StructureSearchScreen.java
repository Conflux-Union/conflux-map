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
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/** Localized structure-type picker that centers the fullscreen map on the nearest candidate. */
final class StructureSearchScreen extends ConfluxScreen {
    private static final int FIELD_WIDTH = 240;
    private static final int FIELD_HEIGHT = 20;
    private static final int MASTER_TOP = 62;
    private static final int LIST_TOP = 100;
    private static final int ROW_HEIGHT = 24;
    private static final int ICON_SIZE = 16;
    private static final int TOGGLE_WIDTH = 58;
    private static final int LOCATE_WIDTH = 62;
    private static final int BUTTON_GAP = 3;
    private static final int SEARCH_RADIUS = 100_000;

    private final FullscreenMapScreen parent;
    private final StructureMarkerService structures;
    private final DimensionId dimension;
    private final ConfluxConfig config;
    private final CompanionSession companion;
    private final List<StructureIndex.StructureType> available;
    private final Map<StructureIndex.StructureType, ButtonWidget> visibilityButtons =
        new EnumMap<>(StructureIndex.StructureType.class);
    private final Map<StructureIndex.StructureType, ButtonWidget> locateButtons =
        new EnumMap<>(StructureIndex.StructureType.class);

    private TextFieldWidget searchField;
    private ButtonWidget masterButton;
    private String observedQuery = "";
    private int scrollOffset;
    private int filteredCount;
    private int rowX;
    private int rowWidth;
    private String statusKey;
    private Object[] statusArgs = new Object[0];

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
        final int fieldWidth = Math.min(FIELD_WIDTH, Math.max(100, width - 32));
        searchField = new TextFieldWidget(
            this.textRenderer,
            width / 2 - fieldWidth / 2,
            38,
            fieldWidth,
            FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.structure_search.field")
        );
        searchField.setMaxLength(64);
        searchField.setText(observedQuery);
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        final int masterWidth = Math.min(240, Math.max(140, width - 32));
        masterButton = addDrawableChild(Widgets.button(
            width / 2 - masterWidth / 2,
            MASTER_TOP,
            masterWidth,
            20,
            masterLabel(),
            ignored -> toggleMasterVisibility()
        ));

        rowWidth = Math.min(420, Math.max(180, width - 24));
        rowX = width / 2 - rowWidth / 2;
        for (final StructureIndex.StructureType type : available) {
            final ButtonWidget visibility = addDrawableChild(Widgets.button(
                rowX + rowWidth - LOCATE_WIDTH - BUTTON_GAP - TOGGLE_WIDTH,
                LIST_TOP,
                TOGGLE_WIDTH,
                20,
                visibilityLabel(type),
                ignored -> toggleTypeVisibility(type)
            ));
            visibilityButtons.put(type, visibility);
            final ButtonWidget locate = addDrawableChild(Widgets.button(
                rowX + rowWidth - LOCATE_WIDTH,
                LIST_TOP,
                LOCATE_WIDTH,
                20,
                Texts.translatable("confluxmap.screen.structure_search.locate"),
                ignored -> locate(type)
            ));
            locateButtons.put(type, locate);
        }
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            height - 28,
            100,
            20,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
        updatePolicyAccess();
        updateRows();
    }

    @Override
    public void tick() {
        Widgets.tick(searchField);
        updatePolicyAccess();
        final String query = searchField == null ? "" : searchField.getText();
        if (!query.equals(observedQuery)) {
            observedQuery = query;
            scrollOffset = 0;
            statusKey = null;
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
        statusKey = "confluxmap.screen.structure_search.not_found";
        statusArgs = new Object[] {localizedName(type), SEARCH_RADIUS};
        final Optional<StructureIndex.Marker> marker = structures.findNearest(
            type,
            parent.centerBlockX(),
            parent.centerBlockZ(),
            SEARCH_RADIUS
        );
        if (marker.isPresent()) {
            parent.focusStructure(marker.get());
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
        }
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
        final boolean overList = mouseX >= rowX && mouseX <= rowX + rowWidth + 6
            && mouseY >= LIST_TOP && mouseY <= LIST_TOP + visibleRows() * ROW_HEIGHT;
        if (amount != 0 && overList && filteredCount > visibleRows()) {
            scrollOffset -= (int) Math.signum(amount);
            updateRows();
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(
            this.textRenderer,
            title,
            width / 2f - this.textRenderer.getWidth(title) / 2f,
            12,
            0xFFFFFFFF
        );
        final String prompt = Texts.translatable("confluxmap.screen.structure_search.prompt").getString();
        draw.drawTextWithShadow(
            this.textRenderer,
            prompt,
            width / 2f - this.textRenderer.getWidth(prompt) / 2f,
            87,
            0xFFBBBBBB
        );
        final List<StructureIndex.StructureType> filtered = filteredTypes();
        final int end = Math.min(filtered.size(), scrollOffset + visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            final StructureIndex.StructureType type = filtered.get(index);
            final int rowY = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            StructureIconCatalog.draw(draw, type, rowX, rowY + 2, ICON_SIZE, 0xFFFFFFFF);
            final int labelWidth = Math.max(
                8,
                rowWidth - ICON_SIZE - TOGGLE_WIDTH - LOCATE_WIDTH - BUTTON_GAP * 3
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
            draw.drawTextWithShadow(
                this.textRenderer,
                empty,
                width / 2f - this.textRenderer.getWidth(empty) / 2f,
                LIST_TOP + 6,
                0xFFAAAAAA
            );
        }
        drawListScrollbar(
            draw,
            rowX + rowWidth + 3,
            LIST_TOP,
            visibleRows() * ROW_HEIGHT - 4,
            filteredCount,
            visibleRows(),
            scrollOffset
        );
        if (statusKey != null) {
            final String status = Texts.translatable(statusKey, statusArgs).getString();
            draw.drawTextWithShadow(
                this.textRenderer,
                status,
                width / 2f - this.textRenderer.getWidth(status) / 2f,
                height - 42,
                0xFFFF7777
            );
        }
    }

    private static String localizedName(final StructureIndex.StructureType type) {
        return Texts.translatable(type.translationKey()).getString();
    }
}
