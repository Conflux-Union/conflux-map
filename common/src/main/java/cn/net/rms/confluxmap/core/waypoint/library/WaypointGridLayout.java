// Copyright (c) 2026 half_masa. MIT; see THIRD_PARTY_NOTICES.md.
package cn.net.rms.confluxmap.core.waypoint.library;


import java.util.ArrayList;
import java.util.List;

/** One geometry for rendering, hit testing and scrolling across both waypoint sources. */
public record WaypointGridLayout(int columns, int visibleRows, int maxScroll, int scroll,
                                 List<Cell> cells, int left, int top, int width, int height)
{
    public static final int MIN_PERCENT = 30;
    public static final int MAX_PERCENT = 300;
    private static final int COMPACT_COLUMN_WIDTH = 96;
    private static final int COMPACT_ROW_HEIGHT = 22;

    public static int clampPercent(int value) {
        return Math.max(MIN_PERCENT, Math.min(MAX_PERCENT, value));
    }

    public record Cell(int index, int x, int y, int width, int height)
    {
        public boolean contains(double px, double py)
        { return px >= x && px < x + width && py >= y && py < y + height; }
    }

    public static WaypointGridLayout create(int count, int left, int top, int width, int height,
                                            boolean compact, int fontHeight, int requestedScroll)
    {
        return create(count, left, top, width, height, compact, fontHeight, requestedScroll, 100, 100);
    }

    public static WaypointGridLayout create(int count, int left, int top, int width, int height,
                                            boolean compact, int fontHeight, int requestedScroll,
                                            int widthPercent, int heightPercent)
    {
        count = Math.max(0, count); width = Math.max(0, width); height = Math.max(0, height);
        int gap = compact ? 3 : 4;
        int minColumnWidth = scaledSize(COMPACT_COLUMN_WIDTH, widthPercent);
        int columns = compact ? (int) Math.max(1, ((long) width + gap) / (minColumnWidth + gap)) : 1;
        int baseRowHeight = Math.max(COMPACT_ROW_HEIGHT, fontHeight + 8);
        int rowHeight = compact ? scaledSize(baseRowHeight, heightPercent) : Math.max(30, 2 * fontHeight + 10);
        if (compact && height >= scaledSize(baseRowHeight, MIN_PERCENT)) rowHeight = Math.min(height, rowHeight);
        int stride = rowHeight + gap;
        int visible = width == 0 ? 0 : (height + gap) / stride;
        int totalRows = (int)(((long)count + columns - 1) / columns);
        int maxScroll = Math.max(0, totalRows - visible);
        int scroll = Math.max(0, Math.min(requestedScroll, maxScroll));
        List<Cell> cells = new ArrayList<>();
        long first = (long)scroll * columns;
        long end = Math.min(count, first + (long)visible * columns);
        for (long index = first; index < end; index++)
        {
            int column = (int)(index % columns);
            int x = left + column * (width + gap) / columns;
            int right = left + (column + 1) * (width + gap) / columns - gap;
            cells.add(new Cell((int)index, x, top + (int)((index - first) / columns) * stride,
                    Math.max(0, right - x), rowHeight));
        }
        return new WaypointGridLayout(columns, visible, maxScroll, scroll, List.copyOf(cells), left, top, width, height);
    }

    private static int scaledSize(int pixels, int percent) {
        return Math.round(pixels * (clampPercent(percent) / 100.0f));
    }

    public Cell at(double x, double y)
    { return cells.stream().filter(cell -> cell.contains(x, y)).findFirst().orElse(null); }

    public boolean contains(double x, double y)
    { return x >= left && x < left + width && y >= top && y < top + height; }
}
