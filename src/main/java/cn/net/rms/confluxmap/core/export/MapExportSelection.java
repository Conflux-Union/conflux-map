package cn.net.rms.confluxmap.core.export;

import java.util.Optional;

/** Two-corner map gesture state, independent of Minecraft mouse event versions. */
public final class MapExportSelection {
    public record Point(int x, int z) {
    }

    private Point first;
    private Point second;

    public Optional<MapExportBounds> select(final int x, final int z) {
        if (first == null) {
            first = new Point(x, z);
            return Optional.empty();
        }
        second = new Point(x, z);
        return bounds();
    }

    public Optional<Point> first() {
        return Optional.ofNullable(first);
    }

    public Optional<MapExportBounds> bounds() {
        return first == null || second == null
            ? Optional.empty()
            : Optional.of(MapExportBounds.between(first.x(), first.z(), second.x(), second.z()));
    }

    public void reset() {
        first = null;
        second = null;
    }
}
