package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.StructureIconCatalog;
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

/** Bounded nearest-candidate finder with a clickable X/Z relationship preview. */
final class StructureCandidateScreen extends ConfluxScreen {
    private static final Pattern INTEGER = Pattern.compile("-?[0-9]*");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[0-9]*");
    private static final int MAX_CANDIDATES = 32;
    private static final int FIELD_WIDTH = 72;
    private static final int FIELD_HEIGHT = 20;
    private static final int CONTENT_TOP = 114;
    private static final int CONTENT_BOTTOM_MARGIN = 42;
    private static final int ROW_HEIGHT = 24;
    private static final int ICON_SIZE = 16;
    private static final int PANEL_GAP = 10;
    private static final int LIST_HEADER_HEIGHT = 18;

    private final StructureSearchScreen parent;
    private final FullscreenMapScreen map;
    private final StructureMarkerService structures;
    private final DimensionId dimension;
    private final StructureIndex.StructureType type;
    private final List<StructureIndex.Marker> candidates = new ArrayList<>();

    private TextFieldWidget centerXField;
    private TextFieldWidget centerZField;
    private TextFieldWidget countField;
    private ButtonWidget showOnMapButton;
    private ButtonWidget waypointButton;
    private int previewX;
    private int previewY;
    private int previewWidth;
    private int previewHeight;
    private int listX;
    private int listY;
    private int listWidth;
    private int listHeight;
    private int listRowsTop;
    private int listRowsHeight;
    private int scrollOffset;
    private int selectedCandidateIndex = -1;
    private String statusKey;
    private Object[] statusArgs = new Object[0];
    private int statusColor = 0xFFFF7777;

    StructureCandidateScreen(
        final StructureSearchScreen parent,
        final FullscreenMapScreen map,
        final StructureMarkerService structures,
        final DimensionId dimension,
        final StructureIndex.StructureType type
    ) {
        super(Texts.translatable("confluxmap.screen.structure_candidates.title", localizedName(type)));
        this.parent = parent;
        this.map = map;
        this.structures = structures;
        this.dimension = dimension;
        this.type = type;
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        final int fieldsLeft = width / 2 - FIELD_WIDTH - 3;
        centerXField = numericField(fieldsLeft, 45, Integer.toString(map.centerBlockX()), INTEGER);
        centerZField = numericField(width / 2 + 3, 45, Integer.toString(map.centerBlockZ()), INTEGER);
        countField = numericField(width / 2 - FIELD_WIDTH / 2, 82, "5", POSITIVE_INTEGER);
        addDrawableChild(centerXField);
        addDrawableChild(centerZField);
        addDrawableChild(countField);
        addDrawableChild(Widgets.button(
            width / 2 + FIELD_WIDTH / 2 + 7, 82, 62, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.structure_candidates.search"),
            ignored -> search()
        ));

        layoutContent();
        final int actionWidth = Math.min(116, Math.max(64, (width - 40) / 3));
        final int actionsLeft = width / 2 - (actionWidth * 3 + PANEL_GAP * 2) / 2;
        showOnMapButton = addDrawableChild(Widgets.button(
            actionsLeft, height - 28, actionWidth, 20,
            Texts.translatable("confluxmap.screen.structure_candidates.show_on_map"),
            ignored -> showOnMap()
        ));
        waypointButton = addDrawableChild(Widgets.button(
            actionsLeft + actionWidth + PANEL_GAP, height - 28, actionWidth, 20,
            Texts.translatable("confluxmap.screen.structure_candidates.add_waypoint"),
            ignored -> addWaypoint()
        ));
        addDrawableChild(Widgets.button(
            actionsLeft + (actionWidth + PANEL_GAP) * 2, height - 28, actionWidth, 20,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
        search();
    }

    private void layoutContent() {
        final int contentBottom = Math.max(CONTENT_TOP + 1, height - CONTENT_BOTTOM_MARGIN);
        final int contentHeight = contentBottom - CONTENT_TOP;
        final int contentWidth = Math.min(680, Math.max(180, width - 32));
        final int contentLeft = width / 2 - contentWidth / 2;
        if (width >= 560 && contentWidth >= 520) {
            previewX = contentLeft;
            previewY = CONTENT_TOP;
            previewWidth = Math.min(260, Math.max(190, contentWidth * 2 / 5));
            previewHeight = contentHeight;
            listX = previewX + previewWidth + PANEL_GAP;
            listY = CONTENT_TOP;
            listWidth = contentWidth - previewWidth - PANEL_GAP;
            listHeight = contentHeight;
        } else {
            previewX = contentLeft;
            previewY = CONTENT_TOP;
            previewWidth = contentWidth;
            previewHeight = Math.min(140, Math.max(80, contentHeight / 3));
            listX = contentLeft;
            listY = previewY + previewHeight + PANEL_GAP;
            listWidth = contentWidth;
            listHeight = Math.max(1, contentBottom - listY);
        }
        listRowsTop = listY + LIST_HEADER_HEIGHT;
        listRowsHeight = Math.max(1, listHeight - LIST_HEADER_HEIGHT);
    }

    private TextFieldWidget numericField(
        final int x,
        final int y,
        final String value,
        final Pattern predicate
    ) {
        final TextFieldWidget field = new TextFieldWidget(
            this.textRenderer, x, y, FIELD_WIDTH, FIELD_HEIGHT, Texts.literal("")
        );
        field.setMaxLength(11);
        //#if MC>=260100
        //$$ final String[] lastValid = {value};
        //$$ field.setResponder(text -> {
        //$$     if (predicate.matcher(text).matches()) {
        //$$         lastValid[0] = text;
        //$$     } else {
        //$$         field.setValue(lastValid[0]);
        //$$     }
        //$$ });
        //#else
        field.setTextPredicate(text -> predicate.matcher(text).matches());
        //#endif
        field.setText(value);
        return field;
    }

    private void search() {
        final Integer centerX = parse(centerXField);
        final Integer centerZ = parse(centerZField);
        final Integer requested = parse(countField);
        candidates.clear();
        selectedCandidateIndex = -1;
        if (centerX == null || centerZ == null || requested == null || requested <= 0) {
            scrollOffset = 0;
            statusKey = "confluxmap.screen.structure_candidates.invalid_input";
            statusArgs = new Object[0];
            statusColor = 0xFFFF7777;
            refreshSelectionState();
            return;
        }
        final int boundedCount = Math.min(requested, MAX_CANDIDATES);
        final boolean limited = requested > MAX_CANDIDATES;
        if (limited) {
            countField.setText(Integer.toString(MAX_CANDIDATES));
        }
        candidates.addAll(structures.findNearestCandidates(type, centerX, centerZ, boundedCount));
        if (candidates.isEmpty()) {
            statusKey = "confluxmap.screen.structure_candidates.none";
            statusArgs = new Object[0];
            statusColor = 0xFFFF7777;
        } else if (limited) {
            statusKey = "confluxmap.screen.structure_candidates.limit";
            statusArgs = new Object[] {MAX_CANDIDATES};
            statusColor = 0xFFFFE066;
        } else {
            statusKey = null;
            statusArgs = new Object[0];
        }
        selectedCandidateIndex = candidates.isEmpty() ? -1 : 0;
        scrollOffset = 0;
        refreshSelectionState();
    }

    private static Integer parse(final TextFieldWidget field) {
        try {
            return Integer.valueOf(field.getText());
        } catch (final NumberFormatException e) {
            return null;
        }
    }

    private void refreshSelectionState() {
        scrollOffset = Math.max(0, Math.min(scrollOffset, Math.max(0, candidates.size() - visibleRows())));
        final boolean selected = selectedCandidate() != null;
        if (showOnMapButton != null) {
            showOnMapButton.active = selected;
        }
        if (waypointButton != null) {
            waypointButton.active = selected;
        }
    }

    private void selectCandidate(final int index) {
        if (index < 0 || index >= candidates.size()) {
            return;
        }
        selectedCandidateIndex = index;
        refreshSelectionState();
    }

    private void showOnMap() {
        final StructureIndex.Marker marker = selectedCandidate();
        if (marker == null) {
            return;
        }
        map.focusStructure(marker);
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), map);
    }

    private void addWaypoint() {
        final StructureIndex.Marker marker = selectedCandidate();
        if (marker == null) {
            return;
        }
        final MinecraftClient client = MinecraftClient.getInstance();
        final double y = client.player == null ? 64.0 : client.player.getY();
        MinecraftAccess.setScreen(client, WaypointEditScreen.forCreate(
            this,
            dimension,
            localizedName(marker.type()),
            marker.blockX(),
            y,
            marker.blockZ()
        ));
    }

    private StructureIndex.Marker selectedCandidate() {
        return selectedCandidateIndex >= 0 && selectedCandidateIndex < candidates.size()
            ? candidates.get(selectedCandidateIndex)
            : null;
    }

    private int visibleRows() {
        return Math.max(1, listRowsHeight / ROW_HEIGHT);
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
        if (button == 0) {
            final StructureCandidatePreview.Layout preview = previewLayout();
            final int previewCandidate = preview.candidateAt(candidates, mouseX, mouseY, 7);
            if (previewCandidate >= 0) {
                selectCandidate(previewCandidate);
                return true;
            }
            final int listCandidate = candidateAtListPosition(mouseX, mouseY);
            if (listCandidate >= 0) {
                selectCandidate(listCandidate);
                return true;
            }
        }
        //#if MC>=12109
        //$$ return super.mouseClicked(click, doubledClick);
        //#else
        return super.mouseClicked(mouseX, mouseY, button);
        //#endif
    }

    private int candidateAtListPosition(final double mouseX, final double mouseY) {
        if (mouseX < listX || mouseX >= listX + listWidth || mouseY < listRowsTop
            || mouseY >= listRowsTop + visibleRows() * ROW_HEIGHT) {
            return -1;
        }
        final int row = (int) ((mouseY - listRowsTop) / ROW_HEIGHT);
        final int index = scrollOffset + row;
        return index < candidates.size() ? index : -1;
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
        if (amount != 0 && mouseX >= listX && mouseX < listX + listWidth
            && mouseY >= listRowsTop && mouseY < listRowsTop + listRowsHeight
            && candidates.size() > visibleRows()) {
            scrollOffset -= (int) Math.signum(amount);
            refreshSelectionState();
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
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 14, 0xFFFFFFFF);
        drawCentered(draw, Texts.translatable("confluxmap.screen.structure_candidates.center").getString(), 31, 0xFFBBBBBB);
        drawCentered(draw, Texts.translatable("confluxmap.screen.structure_candidates.count").getString(), 70, 0xFFBBBBBB);
        drawPreview(draw);
        drawCandidateList(draw);
        if (statusKey != null) {
            drawCentered(draw, Texts.translatable(statusKey, statusArgs).getString(), height - 42, statusColor);
        }
    }

    private void drawPreview(final GuiDraw draw) {
        StructureSearchScrollBar.drawListSurface(draw, previewX, previewY, previewWidth, previewHeight, previewHeight);
        final String title = Texts.translatable("confluxmap.screen.structure_candidates.preview").getString();
        draw.drawTextWithShadow(
            this.textRenderer,
            title,
            previewX + previewWidth / 2f - this.textRenderer.getWidth(title) / 2f,
            previewY + 5,
            0xFFE0E0E0
        );
        if (candidates.isEmpty()) {
            final String empty = Texts.translatable("confluxmap.screen.structure_candidates.preview_empty").getString();
            draw.drawTextWithShadow(
                this.textRenderer,
                this.textRenderer.trimToWidth(empty, Math.max(1, previewWidth - 12)),
                previewX + 6,
                previewY + previewHeight / 2f - 4,
                0xFFAAAAAA
            );
            return;
        }
        final StructureCandidatePreview.Layout preview = previewLayout();
        draw.fill(preview.x(), preview.y(), preview.x() + preview.width(), preview.y() + preview.height(), 0x2D000000);
        final int centerX = preview.centerScreenX(searchCenterX());
        final int centerY = preview.centerScreenY(searchCenterZ());
        draw.fill(centerX - 1, preview.y(), centerX + 1, preview.y() + preview.height(), 0x5588BBDD);
        draw.fill(preview.x(), centerY - 1, preview.x() + preview.width(), centerY + 1, 0x5588BBDD);
        draw.fill(centerX - 3, centerY - 1, centerX + 4, centerY + 1, 0xFF9ED6FF);
        draw.fill(centerX - 1, centerY - 3, centerX + 1, centerY + 4, 0xFF9ED6FF);
        for (int index = 0; index < candidates.size(); index++) {
            final StructureIndex.Marker marker = candidates.get(index);
            final int markerX = preview.centerScreenX(marker.blockX());
            final int markerY = preview.centerScreenY(marker.blockZ());
            final int color = index == selectedCandidateIndex ? 0xFFFFE070
                : marker.state() == StructureIndex.State.VERIFIED ? 0xFF82E58A : 0xFFFFAA55;
            if (index == selectedCandidateIndex) {
                draw.fill(markerX - 5, markerY - 5, markerX + 6, markerY + 6, 0xFFFFFFFF);
            }
            draw.fill(markerX - 3, markerY - 3, markerX + 4, markerY + 4, color);
            final String number = Integer.toString(index + 1);
            draw.drawTextWithShadow(this.textRenderer, number, markerX + 5, markerY - 4, color);
        }
    }

    private void drawCandidateList(final GuiDraw draw) {
        final String heading = Texts.translatable("confluxmap.screen.structure_candidates.list").getString();
        draw.drawTextWithShadow(
            this.textRenderer,
            heading,
            listX + listWidth / 2f - this.textRenderer.getWidth(heading) / 2f,
            listY + 5,
            0xFFE0E0E0
        );
        StructureSearchScrollBar.drawListSurface(
            draw,
            listX,
            listRowsTop - 2,
            listWidth,
            visibleRows() * ROW_HEIGHT + 2,
            ROW_HEIGHT
        );
        final int end = Math.min(candidates.size(), scrollOffset + visibleRows());
        for (int index = scrollOffset; index < end; index++) {
            final StructureIndex.Marker marker = candidates.get(index);
            final int rowY = listRowsTop + (index - scrollOffset) * ROW_HEIGHT;
            if (index == selectedCandidateIndex) {
                draw.fill(listX + 1, rowY, listX + listWidth - 1, rowY + ROW_HEIGHT, 0x664F4323);
            }
            StructureIconCatalog.draw(draw, marker.type(), listX + 4, rowY + 2, ICON_SIZE, 0xFFFFFFFF);
            final String label = this.textRenderer.trimToWidth(
                (index + 1) + ". " + localizedName(marker.type()) + " (" + marker.blockX() + ", "
                    + marker.blockZ() + ") - " + distanceLabel(marker),
                Math.max(8, listWidth - ICON_SIZE - StructureSearchScrollBar.trackWidth() - 16)
            );
            draw.drawTextWithShadow(
                this.textRenderer,
                label,
                listX + ICON_SIZE + 8,
                rowY + 6,
                index == selectedCandidateIndex ? 0xFFFFFFFF
                    : marker.state() == StructureIndex.State.VERIFIED ? 0xFFE0E0E0 : 0xFFBBBBBB
            );
        }
        if (candidates.isEmpty()) {
            final String empty = Texts.translatable("confluxmap.screen.structure_search.empty").getString();
            draw.drawTextWithShadow(
                this.textRenderer,
                empty,
                listX + listWidth / 2f - this.textRenderer.getWidth(empty) / 2f,
                listRowsTop + 6,
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
            listX,
            listRowsTop - 2,
            listWidth,
            visibleRows() * ROW_HEIGHT + 2,
            candidates.size(),
            visibleRows(),
            scrollOffset
        );
        StructureSearchScrollBar.draw(
            draw,
            listX + listWidth - StructureSearchScrollBar.trackWidth() - 2,
            listRowsTop,
            visibleRows() * ROW_HEIGHT - 4,
            candidates.size(),
            visibleRows(),
            scrollOffset
        );
    }

    private StructureCandidatePreview.Layout previewLayout() {
        return StructureCandidatePreview.layout(
            previewX,
            previewY + LIST_HEADER_HEIGHT,
            previewWidth,
            Math.max(1, previewHeight - LIST_HEADER_HEIGHT - 2),
            searchCenterX(),
            searchCenterZ(),
            candidates
        );
    }

    private int searchCenterX() {
        final Integer value = parse(centerXField);
        return value == null ? 0 : value;
    }

    private int searchCenterZ() {
        final Integer value = parse(centerZField);
        return value == null ? 0 : value;
    }

    private String distanceLabel(final StructureIndex.Marker marker) {
        final long dx = marker.blockX() - (long) searchCenterX();
        final long dz = marker.blockZ() - (long) searchCenterZ();
        return String.format(Locale.ROOT, "%d m", Math.round(Math.hypot(dx, dz)));
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

    private static String localizedName(final StructureIndex.StructureType type) {
        return Texts.translatable(type.translationKey()).getString();
    }
}
