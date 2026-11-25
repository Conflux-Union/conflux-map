package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.SearchOrder;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.StructureIconCatalog;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionException;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Configurable candidate query and result actions for one structure type. */
final class StructureCandidateScreen extends CandidateQueryScreen<StructureIndex.Marker> {
    private static final int ICON_SIZE = 16;

    private final FullscreenMapScreen map;
    private final StructureMarkerService structures;
    private final DimensionId dimension;
    private final StructureIndex.StructureType type;
    private List<StructureIndex.Marker> results = List.of();
    private ButtonWidget variantButton;
    private OptionalInt selectedVariant = OptionalInt.empty();

    StructureCandidateScreen(
        final StructureSearchScreen picker,
        final FullscreenMapScreen map,
        final StructureMarkerService structures,
        final DimensionId dimension,
        final StructureIndex.StructureType type
    ) {
        super(map, picker, Texts.translatable(
            "confluxmap.screen.structure_candidates.title", localizedName(type)
        ));
        this.map = map;
        this.structures = structures;
        this.dimension = dimension;
        this.type = type;
    }

    @Override
    protected List<StructureIndex.Marker> results() {
        return results;
    }

    @Override
    protected int controlButtonCount() {
        return type.variantCodes().isEmpty() ? 2 : 3;
    }

    @Override
    protected void addControlButtons(
        final SplitMapLayout layout, final int left, final int controlWidth, final int y
    ) {
        if (type.variantCodes().isEmpty()) {
            variantButton = null;
            return;
        }
        variantButton = addDrawableChild(Widgets.button(
            left,
            y,
            controlWidth,
            FIELD_HEIGHT,
            Texts.translatable(
                "confluxmap.screen.structure_candidates.variant",
                Texts.translatable(
                    StructureVariantPickerScreen.labelKey(type, selectedVariant)
                )
            ),
            ignored -> chooseVariant()
        ));
    }

    @Override
    protected Text searchButtonLabel() {
        return Texts.translatable("confluxmap.screen.structure_candidates.search");
    }

    @Override
    protected void afterTick() {
        updateAccess();
    }

    private void updateAccess() {
        final boolean allowed = structures.availableTypes(dimension).contains(type) && !searching;
        searchButton.active = allowed;
        if (variantButton != null) {
            variantButton.active = allowed;
        }
        if (orderButton != null) {
            orderButton.active = allowed;
        }
        for (final ButtonWidget button : waypointButtons) {
            button.active = allowed;
        }
    }

    @Override
    public void tick() {
        super.tick();
        updateAccess();
    }

    private void chooseVariant() {
        MinecraftAccess.setScreen(
            MinecraftClient.getInstance(),
            new StructureVariantPickerScreen(
                this,
                type,
                selectedVariant,
                selected -> {
                    selectedVariant = selected;
                    scrollOffset = 0;
                    submitSearch();
                }
            )
        );
    }

    @Override
    protected void submitSearch() {
        final int generation = ++queryGeneration;
        final int queryCenterX = centerX;
        final int queryCenterZ = centerZ;
        final int queryRadius = radius;
        final int queryLimit = limit;
        final OptionalInt queryVariant = selectedVariant;
        final SearchOrder queryOrder = order;
        searching = true;
        statusKey = "confluxmap.screen.structure_candidates.searching";
        scrollOffset = 0;
        rebuild();
        try {
            ConfluxMapClient.get().executors().workers().execute(() -> {
                final StructureIndex.CandidateSearch found;
                try {
                    found = structures.searchCandidates(
                        type, queryCenterX, queryCenterZ, queryRadius, queryLimit,
                        queryVariant, queryOrder, () -> generation != queryGeneration
                    );
                } catch (final RuntimeException e) {
                    MinecraftClient.getInstance().execute(() -> acceptFailure(generation));
                    return;
                }
                MinecraftClient.getInstance().execute(() -> acceptResults(generation, found));
            });
        } catch (final RejectedExecutionException e) {
            searching = false;
            statusKey = "confluxmap.screen.structure_candidates.failed";
            rebuild();
        }
    }

    private void acceptFailure(final int generation) {
        if (generation != queryGeneration
            || MinecraftAccess.screen(MinecraftClient.getInstance()) != this) {
            return;
        }
        searching = false;
        statusKey = "confluxmap.screen.structure_candidates.failed";
        rebuild();
    }

    private void acceptResults(final int generation, final StructureIndex.CandidateSearch found) {
        if (generation != queryGeneration
            || MinecraftAccess.screen(MinecraftClient.getInstance()) != this) {
            return;
        }
        results = found.markers();
        searching = false;
        statusKey = found.complete()
            ? (results.isEmpty() ? "confluxmap.screen.structure_candidates.not_found" : null)
            : "confluxmap.screen.structure_candidates.incomplete";
        if (!results.isEmpty()) {
            focusRow(results.get(0));
        }
        rebuild();
    }

    @Override
    protected boolean canFocusRow() {
        return structures.availableTypes(dimension).contains(type);
    }

    @Override
    protected void focusRow(final StructureIndex.Marker marker) {
        map.focusStructure(marker);
    }

    @Override
    protected String rowText(final StructureIndex.Marker marker) {
        return Texts.translatable(marker.translationKey()).getString()
            + " · "
            + CandidateListUi.coordinateText(marker.blockX(), marker.blockZ())
            + " · "
            + Texts.translatable(
                "confluxmap.value.blocks",
                CandidateListUi.distanceInBlocks(
                    marker.blockX(), marker.blockZ(), centerX, centerZ
                )
            ).getString();
    }

    @Override
    protected void createWaypoint(final StructureIndex.Marker marker) {
        map.createWaypointForStructure(marker, this);
    }

    @Override
    protected void drawRow(final GuiDraw draw, final CandidateListUi listUi, final int index) {
        final StructureIndex.Marker marker = results.get(index);
        StructureIconCatalog.draw(
            draw,
            marker.type(),
            marker.variant(),
            rowX(),
            listUi.rowY(index) + 2,
            ICON_SIZE,
            0xFFFFFFFF
        );
        draw.drawTextWithShadow(
            this.textRenderer,
            fitToWidth(rowText(marker), rowTextWidth(listUi)),
            rowX() + ICON_SIZE + GAP,
            listUi.rowY(index) + 6,
            0xFFFFFFFF
        );
    }

    @Override
    protected int statusColor() {
        return 0xFFFF7777;
    }

    @Override
    protected String[] statusTextKeys() {
        return new String[] {
            "confluxmap.screen.structure_candidates.center",
            "confluxmap.screen.structure_candidates.bounds",
            "confluxmap.screen.structure_candidates.invalid",
            "confluxmap.screen.structure_candidates.not_found"
        };
    }

    @Override
    protected int rowTextWidth(final CandidateListUi listUi) {
        return Math.max(8, listUi.textWidth() - ICON_SIZE - GAP);
    }

    private static String localizedName(final StructureIndex.StructureType type) {
        return Texts.translatable(type.translationKey()).getString();
    }
}
