package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import net.minecraft.client.MinecraftClient;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;

/** Configurable candidate query and result actions for one structure type. */
final class StructureCandidateScreen extends ConfluxScreen {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]*");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[0-9]*");
    private static final int DEFAULT_RADIUS = 100_000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_RADIUS = 100_000;
    private static final int MAX_LIMIT = 100;
    private static final int FIELD_WIDTH = 92;
    private static final int FIELD_HEIGHT = 20;
    private static final int LIST_TOP = 112;
    private static final int ROW_HEIGHT = 22;
    private static final int LIST_BOTTOM_SPACE = 32;
    private static final int MAP_WIDTH = 50;
    private static final int WAYPOINT_WIDTH = 76;
    private static final int GAP = 4;
    private static final int SCROLLBAR_WIDTH = 6;
    private static final int SCROLLBAR_GAP = 4;

    private final StructureSearchScreen picker;
    private final FullscreenMapScreen map;
    private final StructureMarkerService structures;
    private final DimensionId dimension;
    private final StructureIndex.StructureType type;
    private final SplitMapPane mapPane;
    private final List<ButtonWidget> mapButtons = new ArrayList<>();
    private final List<ButtonWidget> waypointButtons = new ArrayList<>();

    private int centerX;
    private int centerZ;
    private int radius = DEFAULT_RADIUS;
    private int limit = DEFAULT_LIMIT;
    private List<StructureIndex.Marker> results = List.of();
    private int scrollOffset;
    private boolean initialQueryComplete;
    private boolean draggingScrollBar;
    private double scrollBarGrabOffset;
    private TextFieldWidget centerXField;
    private TextFieldWidget centerZField;
    private TextFieldWidget radiusField;
    private TextFieldWidget limitField;
    private ButtonWidget searchButton;
    private int fieldWidth;
    private int locateWidth;
    private int waypointWidth;
    private String statusKey;

    StructureCandidateScreen(
        final StructureSearchScreen picker,
        final FullscreenMapScreen map,
        final StructureMarkerService structures,
        final DimensionId dimension,
        final StructureIndex.StructureType type
    ) {
        super(Texts.translatable("confluxmap.screen.structure_candidates.title", localizedName(type)));
        this.picker = picker;
        this.map = map;
        this.structures = structures;
        this.dimension = dimension;
        this.type = type;
        this.mapPane = new SplitMapPane(map);
        this.centerX = map.centerBlockX();
        this.centerZ = map.centerBlockZ();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        if (!initialQueryComplete) {
            results = structures.findCandidates(type, centerX, centerZ, radius, limit);
            statusKey = results.isEmpty()
                ? "confluxmap.screen.structure_candidates.not_found"
                : null;
            if (!results.isEmpty()) {
                map.focusStructure(results.get(0));
            }
            initialQueryComplete = true;
        }
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        mapButtons.clear();
        waypointButtons.clear();
        final SplitMapLayout layout = splitLayout();
        fieldWidth = Math.min(
            FIELD_WIDTH,
            Math.max(1, (layout.panelContentWidth() - GAP) / 2)
        );
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
            Texts.translatable("confluxmap.screen.structure_candidates.search"),
            ignored -> search()
        ));

        final int rowWidth = rowWidth();
        final int rowX = rowX();
        updateActionWidths(rowWidth);
        for (int index = 0; index < results.size(); index++) {
            final StructureIndex.Marker marker = results.get(index);
            final ButtonWidget mapButton = addDrawableChild(Widgets.button(
                rowX + rowWidth - waypointWidth - locateWidth - GAP,
                LIST_TOP,
                locateWidth,
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.map"),
                ignored -> focus(marker)
            ));
            final ButtonWidget waypointButton = addDrawableChild(Widgets.button(
                rowX + rowWidth - waypointWidth,
                LIST_TOP,
                waypointWidth,
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.waypoint"),
                ignored -> map.createWaypointForStructure(marker, this)
            ));
            mapButtons.add(mapButton);
            waypointButtons.add(waypointButton);
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
        updateRows();
        updateAccess();
    }

    private void updateActionWidths(final int rowWidth) {
        final CandidateRowLayout layout = candidateRowLayout(rowWidth);
        waypointWidth = layout.waypointWidth();
        locateWidth = layout.locateWidth();
    }

    static CandidateRowLayout candidateRowLayout(final int rowWidth) {
        final int coordinateWidth = Math.min(80, Math.max(24, rowWidth * 2 / 5));
        final int available = Math.max(2, rowWidth - GAP * 3 - coordinateWidth);
        final int waypointWidth = Math.min(
            WAYPOINT_WIDTH, Math.max(1, available * 3 / 5)
        );
        final int locateWidth = Math.min(
            MAP_WIDTH, Math.max(1, available - waypointWidth)
        );
        return new CandidateRowLayout(
            Math.max(1, rowWidth - locateWidth - waypointWidth - GAP * 3),
            locateWidth,
            waypointWidth
        );
    }

    record CandidateRowLayout(int coordinateWidth, int locateWidth, int waypointWidth) {
    }

    private SplitMapLayout splitLayout() {
        return new SplitMapLayout(width, height);
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
        results = structures.findCandidates(type, centerX, centerZ, radius, limit);
        scrollOffset = 0;
        statusKey = results.isEmpty() ? "confluxmap.screen.structure_candidates.not_found" : null;
        if (!results.isEmpty()) {
            map.focusStructure(results.get(0));
        }
        rebuild();
    }

    private void focus(final StructureIndex.Marker marker) {
        map.focusStructure(marker);
    }

    private int visibleRows() {
        return visibleRowsForHeight(height);
    }

    static int visibleRowsForHeight(final int screenHeight) {
        return Math.max(1, (screenHeight - LIST_TOP - LIST_BOTTOM_SPACE) / ROW_HEIGHT);
    }

    private int rowWidth() {
        return Math.min(
            500,
            Math.max(
                1,
                splitLayout().panelContentWidth() - SCROLLBAR_GAP - SCROLLBAR_WIDTH
            )
        );
    }

    private int rowX() {
        final SplitMapLayout layout = splitLayout();
        final int listWidth = Math.max(
            1, layout.panelContentWidth() - SCROLLBAR_GAP - SCROLLBAR_WIDTH
        );
        return layout.panelContentLeft() + (listWidth - rowWidth()) / 2;
    }

    private ScrollBarModel scrollBar() {
        return ScrollBarModel.of(
            LIST_TOP,
            visibleRows() * ROW_HEIGHT - 4,
            results.size(),
            visibleRows(),
            scrollOffset
        );
    }

    private int scrollBarX() {
        return rowX() + rowWidth() + SCROLLBAR_GAP;
    }

    private void updateRows() {
        scrollOffset = Math.max(
            0,
            Math.min(scrollOffset, Math.max(0, results.size() - visibleRows()))
        );
        for (int index = 0; index < results.size(); index++) {
            final boolean visible = index >= scrollOffset && index < scrollOffset + visibleRows();
            final int y = LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
            final ButtonWidget mapButton = mapButtons.get(index);
            final ButtonWidget waypointButton = waypointButtons.get(index);
            mapButton.visible = visible;
            waypointButton.visible = visible;
            if (visible) {
                Widgets.setY(mapButton, y);
                Widgets.setY(waypointButton, y);
            }
        }
    }

    private void updateAccess() {
        final boolean allowed = structures.availableTypes(dimension).contains(type);
        searchButton.active = allowed;
        for (final ButtonWidget button : mapButtons) {
            button.active = allowed;
        }
        for (final ButtonWidget button : waypointButtons) {
            button.active = allowed;
        }
    }

    @Override
    public void tick() {
        Widgets.tick(centerXField);
        Widgets.tick(centerZField);
        Widgets.tick(radiusField);
        Widgets.tick(limitField);
        updateAccess();
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
            && results.size() > visibleRows()) {
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
    public void onClose() {
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
        drawCentered(draw, Texts.translatable("confluxmap.screen.structure_candidates.center").getString(), 20, 0xFFBBBBBB);
        drawCentered(draw, Texts.translatable("confluxmap.screen.structure_candidates.bounds").getString(), 52, 0xFFBBBBBB);
        final int rowWidth = rowWidth();
        final int rowX = rowX();
        final int end = Math.min(results.size(), scrollOffset + visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            final StructureIndex.Marker marker = results.get(index);
            final long dx = marker.blockX() - (long) centerX;
            final long dz = marker.blockZ() - (long) centerZ;
            final String text = String.format(
                Locale.ROOT,
                "%d, %d · %.0f",
                marker.blockX(), marker.blockZ(), Math.sqrt(dx * dx + dz * dz)
            );
            draw.drawTextWithShadow(
                this.textRenderer,
                fitToWidth(text, candidateRowLayout(rowWidth).coordinateWidth()),
                rowX,
                LIST_TOP + (index - scrollOffset) * ROW_HEIGHT + 6,
                0xFFFFFFFF
            );
        }
        if (statusKey != null) {
            drawCentered(draw, Texts.translatable(statusKey).getString(), height - 36, 0xFFFF7777);
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

    private static String localizedName(final StructureIndex.StructureType type) {
        return Texts.translatable(type.translationKey()).getString();
    }
}
