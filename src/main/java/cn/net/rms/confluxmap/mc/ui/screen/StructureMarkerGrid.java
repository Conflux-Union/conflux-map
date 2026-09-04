package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.core.predict.StructureIndex.Marker;
import cn.net.rms.confluxmap.core.predict.StructureIndex.StructureType;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** World-anchored thinning; callers query whole cells so viewport edges cannot change winners. */
final class StructureMarkerGrid {
    private record Cell(StructureType type, int x, int z) {}

    private StructureMarkerGrid() {}

    static int cellSize(final double scale) {
        return Integer.highestOneBit(Math.max(1, (int) Math.ceil(24 * scale) - 1)) << 1;
    }

    static int minimum(final int block, final int size) {
        return (int) Math.max(Integer.MIN_VALUE, (long) Math.floorDiv(block, size) * size);
    }

    static int maximum(final int block, final int size) {
        return (int) Math.min(Integer.MAX_VALUE, (long) minimum(block, size) + size - 1);
    }

    static List<Marker> select(final List<Marker> markers, final int size) {
        final Map<Cell, Marker> cells = new HashMap<>();
        final Comparator<Marker> order = Comparator.comparingInt(Marker::blockX)
            .thenComparingInt(Marker::blockZ).thenComparing(Marker::type);
        for (final Marker marker : markers) {
            final Cell cell = new Cell(
                marker.type(), Math.floorDiv(marker.blockX(), size), Math.floorDiv(marker.blockZ(), size)
            );
            cells.merge(cell, marker, (a, b) -> order.compare(a, b) <= 0 ? a : b);
        }
        return cells.values().stream().sorted(order).toList();
    }
}
