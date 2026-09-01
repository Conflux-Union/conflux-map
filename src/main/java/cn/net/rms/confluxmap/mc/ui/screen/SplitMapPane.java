package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;

/** Interactive left-hand map pane shared by the structure picker and candidate browser. */
final class SplitMapPane {
    private static final int PANEL_BACKGROUND = 0xB0101018;
    private static final int DIVIDER_COLOR = 0xB0454554;

    private final FullscreenMapScreen map;
    private boolean dragging;

    SplitMapPane(final FullscreenMapScreen map) {
        this.map = map;
    }

    void render(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta,
        final SplitMapLayout layout
    ) {
        map.renderEmbedded(draw, mouseX, mouseY, tickDelta, layout);
        draw.fill(
            layout.panelLeft(), 0,
            layout.screenWidth(), layout.screenHeight(),
            PANEL_BACKGROUND
        );
        draw.fill(
            layout.panelLeft(), 0,
            layout.panelLeft() + 1, layout.screenHeight(),
            DIVIDER_COLOR
        );
    }

    boolean mouseClicked(
        final double mouseX,
        final double mouseY,
        final int button,
        final SplitMapLayout layout
    ) {
        if (button != 0 || !layout.containsMap(mouseX, mouseY)) {
            return false;
        }
        dragging = true;
        return true;
    }

    boolean mouseDragged(
        final int button,
        final double deltaX,
        final double deltaY
    ) {
        if (button != 0 || !dragging) {
            return false;
        }
        map.panEmbedded(deltaX, deltaY);
        return true;
    }

    boolean mouseReleased(final int button) {
        if (button != 0 || !dragging) {
            return false;
        }
        dragging = false;
        return true;
    }

    boolean mouseScrolled(
        final double mouseX,
        final double mouseY,
        final double amount,
        final SplitMapLayout layout
    ) {
        return amount != 0.0
            && layout.containsMap(mouseX, mouseY)
            && map.zoomEmbedded(mouseX, mouseY, amount, layout);
    }
}
