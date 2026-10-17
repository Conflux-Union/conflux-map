package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.predict.SearchOrder;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;

/** Shared single-line candidate list layout, controls, separators, and scrolling. */
final class CandidateListUi {
    private static final int LIST_TOP = 112;
    private static final int LIST_BOTTOM_SPACE = 32;
    private static final int ROW_HEIGHT = 24;
    private static final int ACTION_WIDTH = 76;
    private static final int GAP = 4;
    private static final int SEPARATOR_COLOR = 0xAA777777;
    private static final int PREFERRED_CONTENT_WIDTH = 230;

    static SearchOrder toggledOrder(final SearchOrder order) {
        return order == SearchOrder.FARTHEST ? SearchOrder.NEAREST : SearchOrder.FARTHEST;
    }

    static String orderLabelKey(final SearchOrder order) {
        return order == SearchOrder.FARTHEST
            ? "confluxmap.screen.structure_candidates.order.farthest"
            : "confluxmap.screen.structure_candidates.order.nearest";
    }

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

    int waypointButtonY(final int index) {
        return rowY(index) + 2;
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

    void layoutButton(final int index, final ButtonWidget waypointButton) {
        final boolean visible = isVisible(index);
        waypointButton.visible = visible;
        if (visible) {
            Widgets.setY(waypointButton, waypointButtonY(index));
        }
    }

    int candidateAt(final double mouseX, final double mouseY) {
        if (mouseX < rowX || mouseX >= actionX()
            || mouseY < LIST_TOP || mouseY >= LIST_TOP + visibleRows * ROW_HEIGHT) {
            return -1;
        }
        final int index = scrollOffset + (int) ((mouseY - LIST_TOP) / ROW_HEIGHT);
        return index < totalRows ? index : -1;
    }

    /**
     * Draws the full row line as a tooltip, but only when it is wider than the width the
     * row was trimmed to, so the tooltip always means content the row is hiding.
     */
    void drawRowTooltip(
        final GuiDraw draw,
        final Screen screen,
        final TextRenderer renderer,
        final String line,
        final int fittedWidth,
        final int mouseX,
        final int mouseY
    ) {
        if (renderer.getWidth(line) > fittedWidth) {
            draw.drawTooltip(screen, renderer, Texts.literal(line), mouseX, mouseY);
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
