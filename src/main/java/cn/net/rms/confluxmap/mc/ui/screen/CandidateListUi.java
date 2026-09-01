package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Shared two-line candidate list layout, controls, separators, and scrolling. */
final class CandidateListUi {
    private static final int LIST_TOP = 112;
    private static final int LIST_BOTTOM_SPACE = 32;
    private static final int ROW_HEIGHT = 44;
    private static final int ACTION_ROW_OFFSET = 22;
    private static final int ACTION_WIDTH = 76;
    private static final int GAP = 4;
    private static final int SEPARATOR_COLOR = 0xAA777777;
    private static final int PREFERRED_CONTENT_WIDTH = 230;

    private final int rowX;
    private final int rowWidth;
    private final int totalRows;
    private final int visibleRows;
    private final int scrollOffset;
    private final int actionWidth;

    CandidateListUi(
        final int screenHeight,
        final int rowX,
        final int rowWidth,
        final int totalRows,
        final int scrollOffset
    ) {
        this.rowX = rowX;
        this.rowWidth = rowWidth;
        this.totalRows = totalRows;
        visibleRows = Math.max(
            1, (screenHeight - LIST_TOP - LIST_BOTTOM_SPACE) / ROW_HEIGHT
        );
        this.scrollOffset = Math.max(
            0, Math.min(scrollOffset, Math.max(0, totalRows - visibleRows))
        );
        actionWidth = Math.min(ACTION_WIDTH, Math.max(1, rowWidth - GAP - 24));
    }

    int visibleRows() {
        return visibleRows;
    }

    int scrollOffset() {
        return scrollOffset;
    }

    int renderedRows() {
        return Math.min(visibleRows, Math.max(0, totalRows - scrollOffset));
    }

    int rowHeight() {
        return ROW_HEIGHT;
    }

    int rowY(final int index) {
        return LIST_TOP + (index - scrollOffset) * ROW_HEIGHT;
    }

    int textWidth() {
        return Math.max(8, rowWidth - actionWidth - GAP);
    }

    int actionX() {
        return rowX + rowWidth - actionWidth;
    }

    int actionWidth() {
        return actionWidth;
    }

    int mapButtonY(final int index) {
        return rowY(index);
    }

    int waypointButtonY(final int index) {
        return rowY(index) + ACTION_ROW_OFFSET;
    }

    int dividerY(final int index) {
        return rowY(index) + ROW_HEIGHT - 1;
    }

    boolean isVisible(final int index) {
        return index >= scrollOffset && index < scrollOffset + visibleRows;
    }

    int scrollBy(final double amount) {
        if (amount == 0) {
            return scrollOffset;
        }
        return Math.max(
            0,
            Math.min(
                totalRows - visibleRows,
                scrollOffset - (int) Math.signum(amount)
            )
        );
    }

    void layoutButtons(
        final int index,
        final ButtonWidget mapButton,
        final ButtonWidget waypointButton
    ) {
        final boolean visible = isVisible(index);
        mapButton.visible = visible;
        waypointButton.visible = visible;
        if (visible) {
            Widgets.setY(mapButton, mapButtonY(index));
            Widgets.setY(waypointButton, waypointButtonY(index));
        }
    }

    ScrollBarModel scrollBar() {
        return ScrollBarModel.of(
            LIST_TOP,
            visibleRows * ROW_HEIGHT - 4,
            totalRows,
            visibleRows,
            scrollOffset
        );
    }

    boolean containsScrollBar(final double mouseX, final double mouseY) {
        final ScrollBarModel bar = scrollBar();
        return bar.visible()
            && mouseX >= scrollBarX()
            && mouseX < scrollBarX() + scrollBarWidth()
            && mouseY >= bar.trackTop()
            && mouseY < bar.trackTop() + bar.trackHeight();
    }

    double scrollBarGrabOffset(final double mouseY) {
        final ScrollBarModel bar = scrollBar();
        return mouseY >= bar.thumbTop() && mouseY < bar.thumbTop() + bar.thumbHeight()
            ? mouseY - bar.thumbTop()
            : bar.thumbHeight() / 2.0;
    }

    int scrollOffsetForThumbTop(final double mouseY, final double grabOffset) {
        return scrollBar().offsetForThumbTop(mouseY - grabOffset);
    }

    int scrollBarX() {
        return rowX + rowWidth + GAP;
    }

    static int scrollBarWidth() {
        return StructureSearchScrollBar.trackWidth();
    }

    static int scrollBarReservedWidth() {
        return GAP + scrollBarWidth();
    }

    static int preferredContentWidth() {
        return PREFERRED_CONTENT_WIDTH;
    }

    void drawSurface(final GuiDraw draw) {
        final int surfaceX = rowX - GAP;
        final int surfaceWidth = rowWidth + StructureSearchScrollBar.trackWidth() + GAP + 6;
        final int surfaceHeight = visibleRows * ROW_HEIGHT + 2;
        StructureSearchScrollBar.drawListSurface(
            draw, surfaceX, LIST_TOP - 2, surfaceWidth, surfaceHeight, ROW_HEIGHT
        );
        for (int row = 0; row + 1 < renderedRows(); row++) {
            final int y = dividerY(scrollOffset + row);
            draw.fill(surfaceX + 1, y, surfaceX + surfaceWidth - 1, y + 1, SEPARATOR_COLOR);
        }
    }

    void drawScrollBar(final GuiDraw draw) {
        StructureSearchScrollBar.draw(
            draw,
            scrollBarX(),
            LIST_TOP,
            visibleRows * ROW_HEIGHT - 4,
            totalRows,
            visibleRows,
            scrollOffset
        );
    }

    void drawOverflowCues(final GuiDraw draw) {
        StructureSearchScrollBar.drawOverflowCues(
            draw,
            rowX - GAP,
            LIST_TOP - 2,
            rowWidth + StructureSearchScrollBar.trackWidth() + GAP + 6,
            visibleRows * ROW_HEIGHT + 2,
            totalRows,
            visibleRows,
            scrollOffset
        );
    }

    static String coordinateText(final int blockX, final int blockZ) {
        return blockX + ", " + blockZ;
    }

    static long distanceInBlocks(
        final int blockX,
        final int blockZ,
        final int centerX,
        final int centerZ
    ) {
        final long dx = blockX - (long) centerX;
        final long dz = blockZ - (long) centerZ;
        return Math.round(Math.hypot(dx, dz));
    }
}
