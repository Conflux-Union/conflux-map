package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.waypoint.library.WaypointGridLayout.Cell;

/** Places the compact waypoint action panel against the visible cell that owns it. */
record CompactWaypointPanelLayout(int x, int y, int width, int height) {
    private static final int MARGIN = 4;
    private static final int FOOTER = 28;

    static CompactWaypointPanelLayout place(Cell anchor, int screenWidth, int screenHeight, int height) {
        return place(anchor, screenWidth, screenHeight, height, rect -> true);
    }

    static CompactWaypointPanelLayout place(Cell anchor, int screenWidth, int screenHeight, int height,
                                            java.util.function.Predicate<CompactWaypointPanelLayout> fits) {
        int right = screenWidth - MARGIN;
        int bottom = screenHeight - FOOTER;
        int width = Math.min(168, right - MARGIN);
        int cellRight = anchor.x() + anchor.width();
        int cellBottom = anchor.y() + anchor.height();
        if (width <= 0 || bottom - MARGIN < height) return null;

        int alignedY = Math.max(MARGIN, Math.min(anchor.y(), bottom - height));
        var toRight = new CompactWaypointPanelLayout(cellRight, alignedY, width, height);
        if (cellRight + width <= right && fits.test(toRight)) return toRight;
        var toLeft = new CompactWaypointPanelLayout(anchor.x() - width, alignedY, width, height);
        if (toLeft.x() >= MARGIN && fits.test(toLeft)) return toLeft;

        int alignedX = Math.max(MARGIN, Math.min(anchor.x(), right - width));
        var below = new CompactWaypointPanelLayout(alignedX, cellBottom, width, height);
        if (cellBottom + height <= bottom && fits.test(below)) return below;
        var above = new CompactWaypointPanelLayout(alignedX, anchor.y() - height, width, height);
        if (above.y() >= MARGIN && fits.test(above)) return above;
        return null;
    }

    // Test the triangle from the last pointer position in the cell to the shared panel edge.
    boolean onApproach(Cell anchor, double fromX, double fromY, double mx, double my) {
        if (x == anchor.x() + anchor.width() || x + width == anchor.x()) {
            int edge = x > anchor.x() ? x : x + width;
            return betweenEdges(fromX, fromY, edge, y, y + height, mx, my);
        }
        int edge = y > anchor.y() ? y : y + height;
        return betweenEdges(fromY, fromX, edge, x, x + width, my, mx);
    }

    private static boolean betweenEdges(double start, double crossStart, int edge, int min, int max, double at, double cross) {
        double progress = (at - start) / (edge - start);
        return progress > 0 && progress < 1
            && cross >= crossStart + (min - crossStart) * progress
            && cross <= crossStart + (max - crossStart) * progress;
    }

    boolean contains(double mx, double my) {
        return mx >= x && mx < x + width && my >= y && my < y + height;
    }
}
