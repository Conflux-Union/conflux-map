package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.predict.SearchOrder;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * The query panel the candidate screens share: search fields and control buttons over
 * a scrollable candidate list, beside the map pane. A screen supplies its query
 * execution and row presentation; the shared mouse handler priority order lives here.
 */
abstract class CandidateQueryScreen<R> extends SplitMapScreen {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]*");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[0-9]*");
    private static final int DEFAULT_RADIUS = 100_000;
    private static final int DEFAULT_LIMIT = 20;
    private static final int MAX_RADIUS = 100_000;
    private static final int MAX_LIMIT = 100;
    private static final int FIELD_WIDTH = 92;
    protected static final int FIELD_HEIGHT = 20;
    protected static final int GAP = 4;

    private final StructureSearchScreen picker;
    protected final List<ButtonWidget> waypointButtons = new ArrayList<>();

    protected int centerX;
    protected int centerZ;
    protected int radius = DEFAULT_RADIUS;
    protected int limit = DEFAULT_LIMIT;
    protected SearchOrder order = SearchOrder.NEAREST;
    protected int scrollOffset;
    protected volatile int queryGeneration;
    protected boolean searching;
    protected String statusKey;
    private boolean initialQueryStarted;
    private TextFieldWidget centerXField;
    private TextFieldWidget centerZField;
    private TextFieldWidget radiusField;
    private TextFieldWidget limitField;
    protected ButtonWidget searchButton;
    protected ButtonWidget orderButton;
    private int fieldWidth;
    private int panelContentWidth = 1;

    CandidateQueryScreen(
        final FullscreenMapScreen map,
        final StructureSearchScreen picker,
        final Text title
    ) {
        super(title, map);
        this.picker = picker;
        final SearchCenter center = map.searchCenter();
        centerX = center.blockX();
        centerZ = center.blockZ();
    }

    /** The rows the last query produced, in display order. */
    protected abstract List<R> results();

    /** Starts the query the current field values and settings describe. */
    protected abstract void submitSearch();

    /** The one line drawn for a row: coordinates, name, distance. */
    protected abstract String rowText(final R row);

    /** Focuses the map pane on a row's location. */
    protected abstract void focusRow(final R row);

    /** Draws one row's content onto the list surface. */
    protected abstract void drawRow(final GuiDraw draw, final CandidateListUi listUi, final int index);

    /** Runs a row's waypoint button action. */
    protected abstract void createWaypoint(final R row);

    /** The search button's label, rebuilt with the panel. */
    protected abstract Text searchButtonLabel();

    /** Refreshes the control buttons' availability after a rebuild and on each tick. */
    protected abstract void afterTick();

    /** Buttons in the control row; the shared width budget follows the count. */
    protected int controlButtonCount() {
        return 2;
    }

    /** The buttons between the search and the order buttons, left to right. */
    protected void addControlButtons(
        final SplitMapLayout layout, final int left, final int controlWidth, final int y
    ) {
    }

    /** Extra gate a screen puts on focusing a row by clicking it. */
    protected boolean canFocusRow() {
        return true;
    }

    /** Color of the status line under the list. */
    protected int statusColor() {
        return 0xFFAAAAAA;
    }

    /** Status lines a screen can show; the panel reserves width for the widest. */
    protected String[] statusTextKeys() {
        return new String[0];
    }

    /** Width a row's text is trimmed to; screens with row icons keep the icon's share. */
    protected int rowTextWidth(final CandidateListUi listUi) {
        return listUi.textWidth();
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

    protected void rebuild() {
        clearChildren();
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
        final int controlCount = controlButtonCount();
        final int controlsWidth = Math.min(
            controlCount * 100 + (controlCount - 1) * GAP,
            layout.panelContentWidth()
        );
        final int controlWidth = Math.max(
            1, (controlsWidth - (controlCount - 1) * GAP) / controlCount
        );
        final int controlsLeft = layout.panelCenterX() - controlsWidth / 2;
        searchButton = addDrawableChild(Widgets.button(
            controlsLeft,
            88,
            controlWidth,
            FIELD_HEIGHT,
            searchButtonLabel(),
            ignored -> search()
        ));
        addControlButtons(layout, controlsLeft + controlWidth + GAP, controlWidth, 88);
        orderButton = addDrawableChild(Widgets.button(
            controlsLeft + (controlCount - 1) * (controlWidth + GAP),
            88,
            controlWidth,
            FIELD_HEIGHT,
            Texts.translatable(CandidateListUi.orderLabelKey(order)),
            ignored -> toggleOrder()
        ));

        final CandidateListUi listUi = candidateListUi();
        scrollOffset = listUi.scrollOffset();
        for (int index = 0; index < results().size(); index++) {
            final R row = results().get(index);
            waypointButtons.add(addDrawableChild(Widgets.button(
                listUi.actionX(),
                listUi.waypointButtonY(index),
                listUi.actionWidth(),
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.waypoint"),
                ignored -> createWaypoint(row)
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
        updateRows();
        afterTick();
        mapPane.addMenuButtons(this);
    }

    private int requiredPanelContentWidth() {
        final int fieldsWidth = FIELD_WIDTH * 2 + GAP;
        final int rowWidth = CandidateListUi.preferredContentWidth()
            + CandidateListUi.scrollBarReservedWidth();
        int textWidth = this.textRenderer.getWidth(getTitle()) + 16;
        for (final String key : statusTextKeys()) {
            textWidth = Math.max(
                textWidth,
                this.textRenderer.getWidth(Texts.translatable(key)) + 16
            );
        }
        return Math.max(fieldsWidth, Math.max(rowWidth, textWidth));
    }

    protected SplitMapLayout splitLayout() {
        return new SplitMapLayout(width, height, panelContentWidth);
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

    private void toggleOrder() {
        order = CandidateListUi.toggledOrder(order);
        submitSearch();
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

    protected int rowX() {
        final SplitMapLayout layout = splitLayout();
        final int listWidth = Math.max(
            1, layout.panelContentWidth() - CandidateListUi.scrollBarReservedWidth()
        );
        return layout.panelContentLeft() + (listWidth - rowWidth()) / 2;
    }

    private CandidateListUi candidateListUi() {
        return new CandidateListUi(height, rowX(), rowWidth(), results().size(), scrollOffset);
    }

    private void updateRows() {
        final CandidateListUi listUi = candidateListUi();
        scrollOffset = listUi.scrollOffset();
        for (int index = 0; index < results().size(); index++) {
            listUi.layoutButton(index, waypointButtons.get(index));
        }
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
        if (mapPane.menuOpen() && !mapPane.menuContains(mouseX, mouseY)) {
            mapPane.menuClickedOutside(this, mouseX, mouseY, button, splitLayout());
            return true;
        }
        final CandidateListUi listUi = candidateListUi();
        if (button == MouseButtons.LEFT && listUi.containsScrollBar(mouseX, mouseY)) {
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
            mapPane.consumePendingMenuAction(this);
            return true;
        }
        if (mapPane.menuOpen()) {
            return true;
        }
        final int candidateIndex = button == MouseButtons.LEFT ? listUi.candidateAt(mouseX, mouseY) : -1;
        if (candidateIndex >= 0 && canFocusRow()) {
            focusRow(results().get(candidateIndex));
            return true;
        }
        return mapPane.mouseClicked(this, mouseX, mouseY, button, splitLayout());
    }

    @Override
    void updateScrollFromMouse(final double mouseY) {
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
            && results().size() > visibleRows()) {
            scrollOffset = candidateListUi().scrollBy(amount);
            updateRows();
            return true;
        }
        if (mapPane.mouseScrolled(this, mouseX, mouseY, amount, layout)) {
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
        requestClose(picker);
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
        final int end = Math.min(results().size(), scrollOffset + listUi.visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            drawRow(draw, listUi, index);
        }
        if (statusKey != null) {
            drawCentered(draw, Texts.translatable(statusKey).getString(), height - 36, statusColor());
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
        super.renderAfterWidgets(draw, mouseX, mouseY, tickDelta);
        final CandidateListUi listUi = candidateListUi();
        listUi.drawOverflowCues(draw);
        final int hovered = listUi.candidateAt(mouseX, mouseY);
        if (hovered >= 0) {
            listUi.drawRowTooltip(
                draw,
                this,
                this.textRenderer,
                rowText(results().get(hovered)),
                rowTextWidth(listUi),
                mouseX,
                mouseY
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

    protected String fitToWidth(final String text, final int maxWidth) {
        //#if MC>=260100
        //$$ return this.font.plainSubstrByWidth(text, maxWidth);
        //#else
        return this.textRenderer.trimToWidth(text, maxWidth);
        //#endif
    }
}
