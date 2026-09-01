package cn.net.rms.confluxmap.mc.ui.screen;

/** Geometry shared by screens that place an interactive map beside a control panel. */
record SplitMapLayout(int screenWidth, int screenHeight, int desiredContentWidth) {
    private static final int PANEL_PADDING = 8;

    SplitMapLayout {
        if (screenWidth < 2 || screenHeight < 1 || desiredContentWidth < 1) {
            throw new IllegalArgumentException("split map screens require positive dimensions");
        }
    }

    int mapWidth() {
        return screenWidth - panelWidth();
    }

    int mapRenderWidth() {
        return screenWidth;
    }

    int mapHeight() {
        return screenHeight;
    }

    double mapCenterX() {
        return mapWidth() / 2.0;
    }

    double mapCenterY() {
        return mapHeight() / 2.0;
    }

    double renderCenterX(final double centerX, final double scale) {
        return centerX + (mapRenderWidth() / 2.0 - mapCenterX()) * scale;
    }

    int panelLeft() {
        return mapWidth();
    }

    int panelWidth() {
        return Math.min(screenWidth / 2, desiredContentWidth + PANEL_PADDING * 2);
    }

    int panelCenterX() {
        return panelLeft() + panelWidth() / 2;
    }

    int panelContentLeft() {
        return panelLeft() + panelPadding();
    }

    int panelContentWidth() {
        return Math.max(1, panelWidth() - panelPadding() * 2);
    }

    boolean containsMap(final double mouseX, final double mouseY) {
        return mouseX >= 0.0 && mouseX < mapWidth()
            && mouseY >= 0.0 && mouseY < mapHeight();
    }

    boolean containsPanel(final double mouseX, final double mouseY) {
        return mouseX >= panelLeft() && mouseX < screenWidth
            && mouseY >= 0.0 && mouseY < screenHeight;
    }

    int mapRightAlignedX(final int contentWidth, final int margin) {
        return panelLeft() - margin - contentWidth;
    }

    private int panelPadding() {
        return Math.min(PANEL_PADDING, Math.max(0, (panelWidth() - 1) / 2));
    }
}
