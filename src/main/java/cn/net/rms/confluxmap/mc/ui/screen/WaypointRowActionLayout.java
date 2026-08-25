package cn.net.rms.confluxmap.mc.ui.screen;

/** Compact, right-aligned layout for a waypoint row's equally sized actions. */
final class WaypointRowActionLayout {
    private final int left;
    private final int width;
    private final int gap;
    private final int count;

    private WaypointRowActionLayout(
        final int left,
        final int width,
        final int gap,
        final int count
    ) {
        this.left = left;
        this.width = width;
        this.gap = gap;
        this.count = count;
    }

    static WaypointRowActionLayout create(
        final int contentLeft,
        final int contentRight,
        final int padding,
        final int gap,
        final int count,
        final int maxWidth
    ) {
        final int available = Math.max(count, contentRight - contentLeft - padding * 2 - gap * (count - 1));
        final int width = Math.max(1, Math.min(maxWidth, available / count));
        final int totalWidth = width * count + gap * (count - 1);
        return new WaypointRowActionLayout(contentRight - padding - totalWidth, width, gap, count);
    }

    int x(final int index) {
        if (index < 0 || index >= count) {
            throw new IndexOutOfBoundsException(index);
        }
        return left + index * (width + gap);
    }

    int width() {
        return width;
    }

    int right() {
        return left + count * width + (count - 1) * gap;
    }
}
