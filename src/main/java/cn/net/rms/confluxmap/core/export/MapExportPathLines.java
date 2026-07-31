package cn.net.rms.confluxmap.core.export;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.ToIntFunction;

/** Wraps an output path without dropping any character, preferring directory boundaries. */
public final class MapExportPathLines {
    private MapExportPathLines() {
    }

    public static List<String> wrap(
        final String path,
        final int maxWidth,
        final ToIntFunction<String> measure
    ) {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(measure, "measure");
        if (maxWidth <= 0) {
            throw new IllegalArgumentException("Maximum line width must be positive");
        }
        if (path.isEmpty()) {
            return List.of("");
        }

        final List<String> lines = new ArrayList<>();
        int offset = 0;
        while (offset < path.length()) {
            int end = offset;
            int cursor = offset;
            while (cursor < path.length()) {
                final int next = path.offsetByCodePoints(cursor, 1);
                if (measure.applyAsInt(path.substring(offset, next)) > maxWidth) {
                    if (end == offset) {
                        end = next;
                    }
                    break;
                }
                end = next;
                cursor = next;
            }
            if (end < path.length()) {
                final int separator = lastSeparator(path, offset, end);
                if (separator > offset) {
                    end = separator + 1;
                }
            }
            lines.add(path.substring(offset, end));
            offset = end;
        }
        return List.copyOf(lines);
    }

    private static int lastSeparator(final String path, final int offset, final int end) {
        final int slash = path.lastIndexOf('/', end - 1);
        final int backslash = path.lastIndexOf('\\', end - 1);
        final int separator = Math.max(slash, backslash);
        return separator >= offset ? separator : -1;
    }
}
