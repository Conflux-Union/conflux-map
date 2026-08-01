package cn.net.rms.confluxmap.core.waypoint.migrate;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Parses one VoxelMap {@code .points} file. Implements
 * {@code docs/reference-specs/waypoint-storage-formats.md} Format V §2-§6.
 *
 * <p>Two format facts shape this parser: waypoint lines are key-driven and
 * order-insensitive (header lines fall out naturally because they have no
 * {@code name} key), and x/z are stored in overworld-equivalent scale - a
 * waypoint listed for several dimensions yields one import candidate per
 * dimension, each divided back into that dimension's local coordinates.
 */
public final class VoxelMapWaypointsParser {
    private static final Pattern DEATH_NAME = Pattern.compile("Latest Death|Previous Death( \\d+)?");
    /** Format V §3: y defaults to -1, which conventionally means "height unknown". */
    private static final double UNKNOWN_Y_FALLBACK = 64.0;

    private VoxelMapWaypointsParser() {
    }

    public static List<ImportedWaypoint> parse(final List<String> lines) {
        final List<ImportedWaypoint> result = new ArrayList<>();
        for (final String line : lines) {
            parseLine(line, result);
        }
        return result;
    }

    private static void parseLine(final String line, final List<ImportedWaypoint> out) {
        final Map<String, String> pairs = new HashMap<>();
        for (final String pair : line.split(",")) {
            final int separator = pair.indexOf(':');
            if (separator > 0) {
                pairs.put(
                    pair.substring(0, separator).trim().toLowerCase(Locale.ROOT),
                    pair.substring(separator + 1).trim()
                );
            }
        }
        final String name = unescape(pairs.getOrDefault("name", ""));
        if (name.isEmpty()) {
            return;
        }
        final double storedX;
        final double storedZ;
        final double storedY;
        final int colorArgb;
        try {
            storedX = Integer.parseInt(pairs.getOrDefault("x", "0"));
            storedZ = Integer.parseInt(pairs.getOrDefault("z", "0"));
            storedY = Integer.parseInt(pairs.getOrDefault("y", "-1"));
            colorArgb = toArgb(
                Float.parseFloat(pairs.getOrDefault("red", "0.5")),
                Float.parseFloat(pairs.getOrDefault("green", "0.0")),
                Float.parseFloat(pairs.getOrDefault("blue", "0.0"))
            );
        } catch (final NumberFormatException e) {
            return;
        }
        final boolean enabled = Boolean.parseBoolean(pairs.getOrDefault("enabled", "false"));
        final Waypoint.Type type = DEATH_NAME.matcher(name).matches()
            ? Waypoint.Type.DEATH
            : Waypoint.Type.NORMAL;
        final double y = storedY == -1.0 ? UNKNOWN_Y_FALLBACK : storedY;
        for (final DimensionId dimension : dimensions(pairs.getOrDefault("dimensions", ""))) {
            final double scale = DimensionScale.scaleOf(dimension);
            out.add(new ImportedWaypoint(
                name,
                dimension,
                Math.floor(storedX / scale),
                y,
                Math.floor(storedZ / scale),
                colorArgb,
                "",
                enabled,
                type
            ));
        }
    }

    /** Format V §5: {@code #}-joined storage names with a trailing separator. */
    private static List<DimensionId> dimensions(final String value) {
        final List<DimensionId> result = new ArrayList<>();
        for (final String element : value.split("#")) {
            final DimensionId dimension = parseDimension(element.trim());
            if (dimension != null && !result.contains(dimension)) {
                result.add(dimension);
            }
        }
        if (result.isEmpty()) {
            result.add(DimensionId.OVERWORLD);
        }
        return result;
    }

    private static DimensionId parseDimension(final String element) {
        if (element.isEmpty() || element.equals("UNKNOWN")) {
            return null;
        }
        // Very old files stored numeric dimension ids.
        switch (element) {
            case "0": return DimensionId.OVERWORLD;
            case "-1": return DimensionId.NETHER;
            case "1": return DimensionId.END;
            default: break;
        }
        if (element.chars().allMatch(c -> Character.isDigit(c) || c == '-')) {
            return null;
        }
        return DimensionId.parse(element);
    }

    private static int toArgb(final float red, final float green, final float blue) {
        return 0xFF000000 | channel(red) << 16 | channel(green) << 8 | channel(blue);
    }

    private static int channel(final float value) {
        return Math.round(Math.min(1.0f, Math.max(0.0f, value)) * 255.0f);
    }

    /**
     * Format V §4: value decoding applies the union of the value and
     * file-name escape sets plus the fullwidth substitutes.
     */
    static String unescape(final String value) {
        return value
            .replace("~comma~", ",")
            .replace("~colon~", ":")
            .replace("~less~", "<")
            .replace("~greater~", ">")
            .replace("~quote~", "\"")
            .replace("~slash~", "/")
            .replace("~backslash~", "\\")
            .replace("~pipe~", "|")
            .replace("~question~", "?")
            .replace("~star~", "*")
            .replace("﹐", ",")
            .replace("⟦", "[")
            .replace("⟧", "]");
    }
}
