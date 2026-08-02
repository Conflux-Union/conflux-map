package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;

/** Small non-interactive position cue for mouse-wheel-scrolled structure lists. */
final class StructureSearchScrollBar {
    private static final int MIN_THUMB_HEIGHT = 8;
    private static final int TRACK_WIDTH = 7;
    private static final int FADE_HEIGHT = 10;

    private StructureSearchScrollBar() {
    }

    static void draw(
        final GuiDraw draw,
        final int x,
        final int top,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int scrollOffset
    ) {
        if (height <= 0 || totalRows <= visibleRows) {
            return;
        }
        final int thumbHeight = thumbHeight(height, totalRows, visibleRows);
        draw.fill(x, top, x + TRACK_WIDTH, top + height, 0xDD17171C);
        draw.fill(x, top, x + TRACK_WIDTH, top + 1, 0xFF77777F);
        draw.fill(x, top + height - 1, x + TRACK_WIDTH, top + height, 0xFF25252B);
        draw.fill(x, top, x + 1, top + height, 0xFF77777F);
        draw.fill(x + TRACK_WIDTH - 1, top, x + TRACK_WIDTH, top + height, 0xFF25252B);
        final int thumbTop = thumbTop(top, height, totalRows, visibleRows, scrollOffset);
        draw.fill(
            x + 1,
            thumbTop,
            x + TRACK_WIDTH - 1,
            thumbTop + thumbHeight,
            0xFFE0E0E8
        );
        draw.fill(x + 1, thumbTop, x + TRACK_WIDTH - 1, thumbTop + 1, 0xFFFFFFFF);
        draw.fill(x + 1, thumbTop + thumbHeight - 1, x + TRACK_WIDTH - 1, thumbTop + thumbHeight, 0xFF77777F);
    }

    /** Draws a subtle vanilla-style surface so a list reads as one scrollable region. */
    static void drawListSurface(
        final GuiDraw draw,
        final int x,
        final int top,
        final int width,
        final int height,
        final int rowHeight
    ) {
        if (width <= 2 || height <= 2) {
            return;
        }
        draw.fill(x, top, x + width, top + height, 0x64000000);
        draw.fill(x, top, x + width, top + 1, 0xFF777777);
        draw.fill(x, top + height - 1, x + width, top + height, 0xFF333333);
        draw.fill(x, top, x + 1, top + height, 0xFF777777);
        draw.fill(x + width - 1, top, x + width, top + height, 0xFF333333);
        for (int y = top + rowHeight; y < top + height; y += rowHeight) {
            draw.fill(x + 1, y, x + width - 1, y + 1, 0x44303038);
        }
    }

    /**
     * Uses an opaque-to-transparent edge cue rather than an artificial blur. It shows that the
     * current list continues above or below the visible rows without obscuring the controls.
     */
    static void drawOverflowCues(
        final GuiDraw draw,
        final int x,
        final int top,
        final int width,
        final int height,
        final int totalRows,
        final int visibleRows,
        final int scrollOffset
    ) {
        if (height <= 0 || width <= 0 || totalRows <= visibleRows) {
            return;
        }
        final int maximum = Math.max(0, totalRows - visibleRows);
        if (scrollOffset > 0) {
            drawFade(draw, x + 1, top + 1, width - 2, true);
        }
        if (scrollOffset < maximum) {
            drawFade(draw, x + 1, top + height - FADE_HEIGHT - 1, width - 2, false);
        }
    }

    static int trackWidth() {
        return TRACK_WIDTH;
    }

    private static void drawFade(
        final GuiDraw draw,
        final int x,
        final int y,
        final int width,
        final boolean fromTop
    ) {
        for (int row = 0; row < FADE_HEIGHT; row += 2) {
            final int alpha = fromTop ? 92 - row * 8 : 20 + row * 8;
            draw.fill(x, y + row, x + width, y + row + 2, alpha << 24);
        }
    }

    static int thumbHeight(final int trackHeight, final int totalRows, final int visibleRows) {
        if (trackHeight <= 0 || totalRows <= 0 || visibleRows <= 0) {
            return 0;
        }
        return Math.min(
            trackHeight,
            Math.max(MIN_THUMB_HEIGHT, (int) ((long) trackHeight * visibleRows / totalRows))
        );
    }

    static int thumbTop(
        final int trackTop,
        final int trackHeight,
        final int totalRows,
        final int visibleRows,
        final int scrollOffset
    ) {
        final int thumbHeight = thumbHeight(trackHeight, totalRows, visibleRows);
        final int scrollRange = Math.max(0, totalRows - visibleRows);
        final int pixelRange = Math.max(0, trackHeight - thumbHeight);
        final int boundedOffset = Math.max(0, Math.min(scrollOffset, scrollRange));
        return trackTop + (scrollRange == 0 ? 0 : pixelRange * boundedOffset / scrollRange);
    }
}
