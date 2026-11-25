package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.mc.ui.GuiDraw;
//#if MC>=260100
//$$ import net.minecraft.client.gui.components.EditBox;
//#else
import net.minecraft.client.gui.widget.TextFieldWidget;
//#endif

/**
 * Whole-row scrolling shared by {@link ConfigScreen} and {@link RadarEntityVisibilityScreen}:
 * the offset in row steps with its end clamps, the pointer-over-rows test, and the
 * two-{@code fill} scrollbar. The screen owns its closing/retiring guards, the focused
 * widget, the row geometry, and the rebuild.
 */
final class RowScrollPane {
    private final int rowHeight;
    /** Rows scroll in row-height steps; widgets outside the viewport are simply not built. */
    private int scrollOffset;

    RowScrollPane(final int rowHeight) {
        this.rowHeight = rowHeight;
    }

    int scrollOffset() {
        return scrollOffset;
    }

    void scrollOffset(final int value) {
        scrollOffset = value;
    }

    /**
     * Whether a scroll must be dropped because a text field holds the focus: rebuilding
     * the rows would destroy the field mid-edit, and the screen returns {@code false}
     * without consulting its super.
     */
    boolean blocksScroll(final Object focused) {
        //#if MC>=260100
        //$$ return focused instanceof EditBox;
        //#else
        return focused instanceof TextFieldWidget;
        //#endif
    }

    /**
     * Whether the rows consume this wheel event: the wheel turned and the pointer is over
     * the row band (its left edge to {@code rowWidth + 8} past it, rows top to viewport
     * bottom). Anything else falls through to the screen's super.
     */
    boolean consumesScroll(
        final double mouseX,
        final double mouseY,
        final double amount,
        final int rowLeft,
        final int rowWidth,
        final int rowsTop,
        final int viewportBottom
    ) {
        return amount != 0
            && mouseX >= rowLeft && mouseX <= rowLeft + rowWidth + 8
            && mouseY >= rowsTop && mouseY <= viewportBottom;
    }

    /**
     * Moves the offset one row step against the sign of the wheel amount, clamped to
     * {@code [0, max(0, contentHeight - viewportHeight)]}; returns whether the offset
     * moved, so the screen can rebuild its rows.
     */
    boolean scrollBy(final double amount, final int contentHeight, final int viewportHeight) {
        final int maxScroll = Math.max(0, contentHeight - viewportHeight);
        final int next = Math.max(
            0,
            Math.min(maxScroll, scrollOffset - (int) Math.signum(amount) * rowHeight)
        );
        final boolean moved = next != scrollOffset;
        scrollOffset = next;
        return moved;
    }

    /** Draws the scrollbar just right of the rows; nothing when every row fits. */
    void drawScrollbar(
        final GuiDraw draw,
        final int x,
        final int rowsTop,
        final int contentHeight,
        final int viewportHeight,
        final int trackArgb,
        final int thumbArgb
    ) {
        final int maxScroll = Math.max(0, contentHeight - viewportHeight);
        if (maxScroll <= 0) {
            return;
        }
        final int trackHeight = viewportHeight;
        final int thumbHeight = Math.max(12, trackHeight * viewportHeight / contentHeight);
        final int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        final int thumbY = rowsTop
            + (int) Math.round(thumbTravel * (scrollOffset / (double) maxScroll));
        draw.fill(x, rowsTop, x + 2, rowsTop + trackHeight, trackArgb);
        draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, thumbArgb);
    }
}
