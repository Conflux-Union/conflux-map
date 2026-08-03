package cn.net.rms.confluxmap.mc.ui.screen;

/** Geometry shared by screens that place an interactive map beside a control panel. */
record SplitMapLayout(int screenWidth, int screenHeight) {
    private static final int PANEL_PADDING = 8;

    SplitMapLayout {
        if (screenWidth < 2 || screenHeight < 1) {
            throw new IllegalArgumentException("split map screens require positive dimensions");
        }
    }

    int mapWidth() {
        return screenWidth / 2;
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

    int panelLeft() {
        return mapWidth();
    }

    int panelWidth() {
        return screenWidth - panelLeft();
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

    private int panelPadding() {
        return Math.min(PANEL_PADDING, Math.max(0, (panelWidth() - 1) / 2));
    }
}
