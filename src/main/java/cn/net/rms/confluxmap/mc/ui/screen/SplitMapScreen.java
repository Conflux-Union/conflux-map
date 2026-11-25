package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import net.minecraft.text.Text;
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//#endif

/**
 * Host seam for screens that pair an interactive map pane with a control panel: owns
 * the pane and the drag handling every host shares; hosts move their own list on drags.
 */
abstract class SplitMapScreen extends ConfluxScreen {
    final SplitMapPane mapPane;
    boolean draggingScrollBar;
    double scrollBarGrabOffset;

    SplitMapScreen(final Text title, final FullscreenMapScreen map) {
        super(title);
        mapPane = new SplitMapPane(map);
    }

    /** Moves the host's list to the scroll position the dragged thumb stands for. */
    abstract void updateScrollFromMouse(final double mouseY);

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
        if (button == MouseButtons.LEFT && draggingScrollBar) {
            updateScrollFromMouse(mouseY);
            return true;
        }
        if (mapPane.mouseDragged(button, deltaX, deltaY)) {
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
        if (button == MouseButtons.LEFT && draggingScrollBar) {
            draggingScrollBar = false;
            return true;
        }
        if (mapPane.mouseReleased(button)) {
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseReleased(click);
        //#else
        return super.mouseReleased(mouseX, mouseY, button);
        //#endif
    }

    @Override
    protected boolean embeddedMenuHotkeyPressed(final int keyCode) {
        return mapPane.menuHotkeyPressed(this, keyCode);
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        mapPane.drawMenuHotkeys(draw, this.textRenderer);
    }
}
