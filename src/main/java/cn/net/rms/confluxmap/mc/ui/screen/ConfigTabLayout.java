package cn.net.rms.confluxmap.mc.ui.screen;

/** Responsive grid geometry for settings categories. */
record ConfigTabLayout(int columns, int rows, int tabWidth, int totalWidth, int contentTop) {
    private static final int MIN_TAB_WIDTH = 84;
    private static final int MAX_TAB_WIDTH = 110;

    static ConfigTabLayout fit(
        final int screenWidth,
        final int itemCount,
        final int margin,
        final int gap,
        final int tabY,
        final int tabHeight
    ) {
        if (itemCount <= 0) {
            throw new IllegalArgumentException("itemCount must be positive");
        }
        final int available = Math.max(1, screenWidth - margin * 2);
        final int fittingColumns = Math.max(1, (available + gap) / (MIN_TAB_WIDTH + gap));
        final int columns = Math.min(itemCount, fittingColumns);
        final int rows = (itemCount + columns - 1) / columns;
        final int tabWidth = Math.min(
            MAX_TAB_WIDTH,
            Math.max(1, (available - gap * (columns - 1)) / columns)
        );
        final int totalWidth = tabWidth * columns + gap * (columns - 1);
        final int contentTop = tabY + rows * tabHeight + (rows + 1) * gap;
        return new ConfigTabLayout(columns, rows, tabWidth, totalWidth, contentTop);
    }
}
