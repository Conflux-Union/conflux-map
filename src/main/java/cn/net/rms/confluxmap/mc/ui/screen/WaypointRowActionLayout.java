package cn.net.rms.confluxmap.mc.ui.screen;

import java.util.Arrays;

/** Compact, right-aligned layout for a waypoint row's actions. */
final class WaypointRowActionLayout {
    private final int left;
    private final int[] widths;
    private final int gap;

    private WaypointRowActionLayout(
        final int left,
        final int[] widths,
        final int gap
    ) {
        this.left = left;
        this.widths = widths;
        this.gap = gap;
    }

    static WaypointRowActionLayout create(
        final int contentLeft,
        final int contentRight,
        final int padding,
        final int gap,
        final int count,
        final int maxWidth
    ) {
        final int[] preferredWidths = new int[count];
        Arrays.fill(preferredWidths, maxWidth);
        return create(contentLeft, contentRight, padding, gap, preferredWidths);
    }

    static WaypointRowActionLayout create(
        final int contentLeft,
        final int contentRight,
        final int padding,
        final int gap,
        final int[] preferredWidths
    ) {
        if (preferredWidths.length == 0) {
            throw new IllegalArgumentException("At least one action is required");
        }
        final int[] widths = Arrays.copyOf(preferredWidths, preferredWidths.length);
        final int available = Math.max(
            widths.length,
            contentRight - contentLeft - padding * 2 - gap * (widths.length - 1)
        );
        final int preferredTotal = Arrays.stream(widths).sum();
        if (preferredTotal > available) {
            shrinkToFit(widths, preferredWidths, available);
        }
        final int totalWidth = Arrays.stream(widths).sum() + gap * (widths.length - 1);
        return new WaypointRowActionLayout(contentRight - padding - totalWidth, widths, gap);
    }

    private static void shrinkToFit(
        final int[] widths,
        final int[] preferredWidths,
        final int available
    ) {
        final int minimum = Arrays.stream(preferredWidths).min().orElse(1);
        if (minimum * widths.length > available) {
            Arrays.fill(widths, Math.max(1, available / widths.length));
            return;
        }
        Arrays.fill(widths, minimum);
        int remaining = available - minimum * widths.length;
        while (remaining > 0) {
            final int adjustable = (int) java.util.stream.IntStream.range(0, widths.length)
                .filter(index -> widths[index] < preferredWidths[index])
                .count();
            if (adjustable == 0) {
                return;
            }
            final int share = Math.max(1, remaining / adjustable);
            for (int index = 0; index < widths.length && remaining > 0; index++) {
                final int growth = Math.min(
                    remaining,
                    Math.min(share, preferredWidths[index] - widths[index])
                );
                widths[index] += growth;
                remaining -= growth;
            }
        }
    }

    int x(final int index) {
        if (index < 0 || index >= widths.length) {
            throw new IndexOutOfBoundsException(index);
        }
        int x = left;
        for (int previous = 0; previous < index; previous++) {
            x += widths[previous] + gap;
        }
        return x;
    }

    int width() {
        return width(0);
    }

    int width(final int index) {
        if (index < 0 || index >= widths.length) {
            throw new IndexOutOfBoundsException(index);
        }
        return widths[index];
    }

    int right() {
        return left + Arrays.stream(widths).sum() + (widths.length - 1) * gap;
    }
}
