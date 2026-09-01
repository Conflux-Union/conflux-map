package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.BiomeCandidateSearch;
import cn.net.rms.confluxmap.core.predict.BiomeSearchService;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Identifier;

/** Candidate query and result actions for one biome. */
final class BiomeCandidateScreen extends ConfluxScreen {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]*");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[0-9]*");
    private static final int DEFAULT_RADIUS = 100_000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_RADIUS = 100_000;
    private static final int MAX_LIMIT = 100;
    private static final int FIELD_WIDTH = 92;
    private static final int FIELD_HEIGHT = 20;
    private static final int GAP = 4;

    private final StructureSearchScreen picker;
    private final FullscreenMapScreen map;
    private final DimensionId dimension;
    private final Identifier biome;
    private final SplitMapPane mapPane;
    private final List<ButtonWidget> mapButtons = new ArrayList<>();
    private final List<ButtonWidget> waypointButtons = new ArrayList<>();

    private int centerX;
    private int centerZ;
    private int radius = DEFAULT_RADIUS;
    private int limit = DEFAULT_LIMIT;
    private List<BiomeCandidateSearch.Candidate> results = List.of();
    private int scrollOffset;
    private int queryGeneration;
    private boolean initialQueryStarted;
    private boolean searching;
    private boolean draggingScrollBar;
    private double scrollBarGrabOffset;
    private TextFieldWidget centerXField;
    private TextFieldWidget centerZField;
    private TextFieldWidget radiusField;
    private TextFieldWidget limitField;
    private ButtonWidget searchButton;
    private int fieldWidth;
    private int panelContentWidth = 1;
    private String statusKey;

    BiomeCandidateScreen(
        final StructureSearchScreen picker,
        final FullscreenMapScreen map,
        final DimensionId dimension,
        final Identifier biome
    ) {
        super(Texts.translatable(
            "confluxmap.screen.biome_candidates.title",
            StructureSearchScreen.localizedBiomeName(biome)
        ));
        this.picker = picker;
        this.map = map;
        this.dimension = dimension;
        this.biome = biome;
        this.mapPane = new SplitMapPane(map);
        centerX = map.centerBlockX();
        centerZ = map.centerBlockZ();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rebuild();
        if (!initialQueryStarted) {
            initialQueryStarted = true;
            submitSearch();
        }
    }

    private void rebuild() {
        clearChildren();
        mapButtons.clear();
        waypointButtons.clear();
        panelContentWidth = requiredPanelContentWidth();
        final SplitMapLayout layout = splitLayout();
        fieldWidth = Math.min(FIELD_WIDTH, Math.max(1, (layout.panelContentWidth() - GAP) / 2));
        final int fieldsWidth = fieldWidth * 2 + GAP;
        final int left = layout.panelCenterX() - fieldsWidth / 2;
        final int right = left + fieldWidth + GAP;
        centerXField = integerField(left, 32, centerX, false);
        centerZField = integerField(right, 32, centerZ, false);
        radiusField = integerField(left, 64, radius, true);
        limitField = integerField(right, 64, limit, true);
        addDrawableChild(centerXField);
        addDrawableChild(centerZField);
        addDrawableChild(radiusField);
        addDrawableChild(limitField);
        searchButton = addDrawableChild(Widgets.button(
            layout.panelCenterX() - Math.min(100, layout.panelContentWidth()) / 2,
            88,
            Math.min(100, layout.panelContentWidth()),
            FIELD_HEIGHT,
            Texts.translatable(searchButtonKey(searching)),
            ignored -> search()
        ));

        final CandidateListUi listUi = candidateListUi();
        scrollOffset = listUi.scrollOffset();
        for (int index = 0; index < results.size(); index++) {
            final BiomeCandidateSearch.Candidate candidate = results.get(index);
            mapButtons.add(addDrawableChild(Widgets.button(
                listUi.actionX(),
                listUi.mapButtonY(index),
                listUi.actionWidth(),
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.map"),
                ignored -> focus(candidate)
            )));
            waypointButtons.add(addDrawableChild(Widgets.button(
                listUi.actionX(),
                listUi.waypointButtonY(index),
                listUi.actionWidth(),
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.waypoint"),
                ignored -> map.createWaypointForBiome(
                    StructureSearchScreen.localizedBiomeName(biome), candidate, this
                )
            )));
        }
        final int backWidth = Math.min(100, layout.panelContentWidth());
        addDrawableChild(Widgets.button(
            layout.panelCenterX() - backWidth / 2,
            height - 24,
            backWidth,
            20,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
        searchButton.active = !searching;
        updateRows();
    }

    private TextFieldWidget integerField(
        final int x,
        final int y,
        final int value,
        final boolean positive
    ) {
        final TextFieldWidget field = new TextFieldWidget(
            this.textRenderer, x, y, fieldWidth, FIELD_HEIGHT, Texts.literal("")
        );
        field.setMaxLength(11);
        final Pattern pattern = positive ? POSITIVE_INTEGER : INTEGER;
        //#if MC>=260100
        //$$ final String[] lastValid = {Integer.toString(value)};
        //$$ field.setResponder(text -> {
        //$$     if (pattern.matcher(text).matches()) {
        //$$         lastValid[0] = text;
        //$$     } else {
        //$$         field.setValue(lastValid[0]);
        //$$     }
        //$$ });
        //#else
        field.setTextPredicate(text -> pattern.matcher(text).matches());
        //#endif
        Widgets.setText(field, Integer.toString(value));
        return field;
    }

    private void search() {
        try {
            centerX = Integer.parseInt(Widgets.text(centerXField));
            centerZ = Integer.parseInt(Widgets.text(centerZField));
            radius = Math.max(1, Math.min(MAX_RADIUS, Integer.parseInt(Widgets.text(radiusField))));
            limit = Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(Widgets.text(limitField))));
        } catch (final NumberFormatException e) {
            statusKey = "confluxmap.screen.structure_candidates.invalid";
            return;
        }
        submitSearch();
    }

    private void submitSearch() {
        final int generation = ++queryGeneration;
        final int queryCenterX = centerX;
        final int queryCenterZ = centerZ;
        final int queryRadius = radius;
        final int queryLimit = limit;
        final ColumnStore store = map.biomeSearchStore();
        final OptionalInt predictedBiome = biome.getNamespace().equals("minecraft")
            ? CubiomesBiomeIds.idForName(biome.getPath())
            : OptionalInt.empty();
        final BaselineSampler sampler = predictedBiome.isPresent()
            ? map.biomeSearchSampler(dimension)
            : null;
        searching = true;
        statusKey = "confluxmap.screen.biome_candidates.searching";
        scrollOffset = 0;
        rebuild();
        try {
            ConfluxMapClient.get().executors().workers().execute(() -> {
                final List<BiomeCandidateSearch.Candidate> found = BiomeSearchService.search(
                    store,
                    biome.toString(),
                    predictedBiome,
                    sampler,
                    queryCenterX,
                    queryCenterZ,
                    queryRadius,
                    queryLimit
                );
                MinecraftClient.getInstance().execute(() -> acceptResults(
                    generation, found, sampler != null
                ));
            });
        } catch (final RejectedExecutionException e) {
            searching = false;
            statusKey = "confluxmap.screen.biome_candidates.failed";
            rebuild();
        }
    }

    private void acceptResults(
        final int generation,
        final List<BiomeCandidateSearch.Candidate> found,
        final boolean predictionAvailable
    ) {
        if (generation != queryGeneration
            || MinecraftAccess.screen(MinecraftClient.getInstance()) != this) {
            return;
        }
        results = found;
        searching = false;
        statusKey = found.isEmpty()
            ? predictionAvailable
                ? "confluxmap.screen.biome_candidates.not_found"
                : "confluxmap.screen.biome_candidates.not_found_no_prediction"
            : null;
        if (!found.isEmpty()) {
            focus(found.get(0));
        }
        rebuild();
    }

    private void focus(final BiomeCandidateSearch.Candidate candidate) {
        map.focusBiome(candidate);
    }

    static String searchButtonKey(final boolean searching) {
        return searching
            ? "confluxmap.screen.biome_candidates.searching_button"
            : "confluxmap.screen.structure_candidates.search";
    }

    static int statusY(final int screenHeight) {
        return screenHeight - 36;
    }

    private int visibleRows() {
        return candidateListUi().visibleRows();
    }

    private int rowWidth() {
        return Math.min(
            500,
            Math.max(
                1,
                splitLayout().panelContentWidth() - CandidateListUi.scrollBarReservedWidth()
            )
        );
    }

    private int rowX() {
        final SplitMapLayout layout = splitLayout();
        final int listWidth = Math.max(
            1, layout.panelContentWidth() - CandidateListUi.scrollBarReservedWidth()
        );
        return layout.panelContentLeft() + (listWidth - rowWidth()) / 2;
    }

    private CandidateListUi candidateListUi() {
        return new CandidateListUi(height, rowX(), rowWidth(), results.size(), scrollOffset);
    }

    private void updateRows() {
        final CandidateListUi listUi = candidateListUi();
        scrollOffset = listUi.scrollOffset();
        for (int index = 0; index < results.size(); index++) {
            listUi.layoutButtons(index, mapButtons.get(index), waypointButtons.get(index));
        }
    }

    private int requiredPanelContentWidth() {
        return Math.max(
            FIELD_WIDTH * 2 + GAP,
            Math.max(
                CandidateListUi.preferredContentWidth()
                    + CandidateListUi.scrollBarReservedWidth(),
                this.textRenderer.getWidth(getTitle()) + 16
            )
        );
    }

    private SplitMapLayout splitLayout() {
        return new SplitMapLayout(width, height, panelContentWidth);
    }

    @Override
    public void tick() {
        Widgets.tick(centerXField);
        Widgets.tick(centerZField);
        Widgets.tick(radiusField);
        Widgets.tick(limitField);
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
        final CandidateListUi listUi = candidateListUi();
        if (button == 0 && listUi.containsScrollBar(mouseX, mouseY)) {
            draggingScrollBar = true;
            scrollBarGrabOffset = listUi.scrollBarGrabOffset(mouseY);
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
        scrollOffset = candidateListUi().scrollOffsetForThumbTop(
            mouseY, scrollBarGrabOffset
        );
        updateRows();
    }

    @Override
    public boolean mouseScrolled(
        final double mouseX,
        final double mouseY,
        //#if MC>=12002
        //$$ final double horizontalAmount,
        //#endif
        final double amount
    ) {
        final SplitMapLayout layout = splitLayout();
        if (amount != 0 && layout.containsPanel(mouseX, mouseY)
            && results.size() > visibleRows()) {
            scrollOffset = candidateListUi().scrollBy(amount);
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
    public void onClose() {
        queryGeneration++;
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), picker);
    }

    @Override
    protected void renderContents(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final SplitMapLayout layout = splitLayout();
        mapPane.render(draw, mouseX, mouseY, tickDelta, layout);
        drawCentered(draw, getTitle().getString(), 8, 0xFFFFFFFF);
        drawCentered(draw, Texts.translatable(
            "confluxmap.screen.structure_candidates.center"
        ).getString(), 20, 0xFFBBBBBB);
        drawCentered(draw, Texts.translatable(
            "confluxmap.screen.structure_candidates.bounds"
        ).getString(), 52, 0xFFBBBBBB);
        final CandidateListUi listUi = candidateListUi();
        listUi.drawSurface(draw);
        final int end = Math.min(results.size(), scrollOffset + listUi.visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            final BiomeCandidateSearch.Candidate candidate = results.get(index);
            draw.drawTextWithShadow(
                this.textRenderer,
                this.textRenderer.trimToWidth(
                    CandidateListUi.coordinateText(candidate.blockX(), candidate.blockZ()),
                    listUi.textWidth()
                ),
                rowX(),
                listUi.rowY(index) + 6,
                0xFFFFFFFF
            );
            draw.drawTextWithShadow(
                this.textRenderer,
                this.textRenderer.trimToWidth(
                    Texts.translatable(
                        "confluxmap.value.blocks",
                        CandidateListUi.distanceInBlocks(
                            candidate.blockX(), candidate.blockZ(), centerX, centerZ
                        )
                    ).getString(),
                    listUi.textWidth()
                ),
                rowX(),
                listUi.waypointButtonY(index) + 6,
                0xFFBBBBBB
            );
        }
        if (statusKey != null) {
            drawCentered(
                draw,
                Texts.translatable(statusKey).getString(),
                statusY(height),
                0xFFAAAAAA
            );
        }
        listUi.drawScrollBar(draw);
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        candidateListUi().drawOverflowCues(draw);
    }

    private void drawCentered(final GuiDraw draw, final String text, final int y, final int color) {
        final SplitMapLayout layout = splitLayout();
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
}
