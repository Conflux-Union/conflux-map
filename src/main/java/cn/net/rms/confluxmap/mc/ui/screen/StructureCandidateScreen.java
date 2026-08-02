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
    private static final int LIST_TOP = 132;
    private static final int ROW_HEIGHT = 24;
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
            initialQueryComplete = true;
        }
        rebuild();
    }

    private void rebuild() {
        clearChildren();
        mapButtons.clear();
        waypointButtons.clear();
        final int left = width / 2 - FIELD_WIDTH - 6;
        final int right = width / 2 + 6;
        centerXField = integerField(left, 42, centerX, false);
        centerZField = integerField(right, 42, centerZ, false);
        radiusField = integerField(left, 78, radius, true);
        limitField = integerField(right, 78, limit, true);
        addDrawableChild(centerXField);
        addDrawableChild(centerZField);
        addDrawableChild(radiusField);
        addDrawableChild(limitField);
        searchButton = addDrawableChild(Widgets.button(
            width / 2 - 50, 106, 100, FIELD_HEIGHT,
            Texts.translatable("confluxmap.screen.structure_candidates.search"),
            ignored -> search()
        ));

        final int rowWidth = Math.min(500, Math.max(230, width - 24));
        final int rowX = width / 2 - rowWidth / 2;
        for (int index = 0; index < results.size(); index++) {
            final StructureIndex.Marker marker = results.get(index);
            final ButtonWidget mapButton = addDrawableChild(Widgets.button(
                rowX + rowWidth - WAYPOINT_WIDTH - MAP_WIDTH - GAP,
                LIST_TOP,
                MAP_WIDTH,
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.map"),
                ignored -> focus(marker)
            ));
            final ButtonWidget waypointButton = addDrawableChild(Widgets.button(
                rowX + rowWidth - WAYPOINT_WIDTH,
                LIST_TOP,
                WAYPOINT_WIDTH,
                20,
                Texts.translatable("confluxmap.screen.structure_candidates.waypoint"),
                ignored -> map.createWaypointForStructure(marker, this)
            ));
            mapButtons.add(mapButton);
            waypointButtons.add(waypointButton);
        }
        addDrawableChild(Widgets.button(
            width / 2 - 50,
            height - 28,
            100,
            20,
            Texts.translatable("confluxmap.screen.structure_search.back"),
            ignored -> onClose()
        ));
        updateRows();
        updateAccess();
    }

    private TextFieldWidget integerField(
        final int x,
        final int y,
        final int value,
        final boolean positive
    ) {
        final TextFieldWidget field = new TextFieldWidget(
            this.textRenderer, x, y, FIELD_WIDTH, FIELD_HEIGHT, Texts.literal("")
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
        rebuild();
    }

    private void focus(final StructureIndex.Marker marker) {
        map.focusStructure(marker);
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), map);
    }

    private int visibleRows() {
        return Math.max(1, (height - LIST_TOP - 38) / ROW_HEIGHT);
    }

    private int rowWidth() {
        return Math.min(500, Math.max(230, width - 24));
    }

    private int rowX() {
        return width / 2 - rowWidth() / 2;
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
        //$$ return super.mouseClicked(click, doubledClick);
        //#else
        return super.mouseClicked(mouseX, mouseY, button);
        //#endif
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
        if (amount != 0 && results.size() > visibleRows()) {
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
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        drawCentered(draw, getTitle().getString(), 14, 0xFFFFFFFF);
        drawCentered(draw, Texts.translatable("confluxmap.screen.structure_candidates.center").getString(), 30, 0xFFBBBBBB);
        drawCentered(draw, Texts.translatable("confluxmap.screen.structure_candidates.bounds").getString(), 66, 0xFFBBBBBB);
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
                fitToWidth(text, Math.max(20, rowWidth - MAP_WIDTH - WAYPOINT_WIDTH - GAP * 3)),
                rowX,
                LIST_TOP + (index - scrollOffset) * ROW_HEIGHT + 6,
                0xFFFFFFFF
            );
        }
        if (statusKey != null) {
            drawCentered(draw, Texts.translatable(statusKey).getString(), height - 42, 0xFFFF7777);
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
        draw.drawTextWithShadow(
            this.textRenderer, text, width / 2f - this.textRenderer.getWidth(text) / 2f, y, color
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
