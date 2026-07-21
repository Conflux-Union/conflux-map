package cn.net.rms.confluxmap.mc.ui.screen;

import net.minecraft.util.math.MathHelper;

/** Pure scroll calculations shared by the waypoint-set dropdown and its tests. */
final class DropdownScroll {
    private DropdownScroll() {
    }

    static int maxOffset(final int optionCount, final int visibleRows) {
        return Math.max(0, optionCount - Math.max(0, visibleRows));
    }

    static int clamp(final int offset, final int optionCount, final int visibleRows) {
        return MathHelper.clamp(offset, 0, maxOffset(optionCount, visibleRows));
    }

    static int afterWheel(
        final int offset,
        final double amount,
        final int optionCount,
        final int visibleRows
    ) {
        return clamp(offset - (int) Math.signum(amount), optionCount, visibleRows);
    }

    static int ensureVisible(final int selectedIndex, final int optionCount, final int visibleRows) {
        if (selectedIndex < visibleRows) {
            return 0;
        }
        return clamp(selectedIndex - visibleRows + 1, optionCount, visibleRows);
    }

    static int keepVisible(
        final int offset,
        final int selectedIndex,
        final int optionCount,
        final int visibleRows
    ) {
        if (selectedIndex < offset) {
            return clamp(selectedIndex, optionCount, visibleRows);
        }
        if (selectedIndex >= offset + visibleRows) {
            return clamp(selectedIndex - visibleRows + 1, optionCount, visibleRows);
        }
        return clamp(offset, optionCount, visibleRows);
    }

    static int fromThumbPosition(
        final double mouseY,
        final int trackTop,
        final int trackHeight,
        final int thumbHeight,
        final int optionCount,
        final int visibleRows
    ) {
        final int maxOffset = maxOffset(optionCount, visibleRows);
        final int travel = Math.max(0, trackHeight - thumbHeight);
        if (maxOffset == 0 || travel == 0) {
            return 0;
        }
        final double thumbTop = mouseY - trackTop - thumbHeight / 2.0;
        final double progress = MathHelper.clamp(thumbTop / travel, 0.0, 1.0);
        return (int) Math.round(progress * maxOffset);
    }
}
