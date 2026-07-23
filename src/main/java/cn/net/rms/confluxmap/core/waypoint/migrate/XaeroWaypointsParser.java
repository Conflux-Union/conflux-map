package cn.net.rms.confluxmap.core.waypoint.migrate;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses one Xaero's Minimap waypoint file (all sub-world sets of one
 * dimension). Implements {@code docs/reference-specs/waypoint-storage-formats.md}
 * Format X §5-§8; the containing dimension comes from the {@code dim%...}
 * folder the file lives in, resolved by {@link MigrationSourceScanner}.
 *
 * <p>Faithful to the reference loader's tolerance: unknown line types and
 * individually unparsable lines are skipped, never fatal.
 */
public final class XaeroWaypointsParser {
    /** Format X §7: 16-entry chat-color palette, index-normalized on read. */
    private static final int[] PALETTE = {
        0xFF000000, 0xFF0000AA, 0xFF00AA00, 0xFF00AAAA,
        0xFFAA0000, 0xFFAA00AA, 0xFFFFAA00, 0xFFAAAAAA,
        0xFF555555, 0xFF5555FF, 0xFF55FF55, 0xFF55FFFF,
        0xFFFF0000, 0xFFFF55FF, 0xFFFFFF55, 0xFFFFFFFF,
    };
    private static final String DEFAULT_SET_LITERAL = "gui.xaero_default";
    private static final String DEATH_NAME_LITERAL = "gui.xaero_deathpoint";
    private static final String OLD_DEATH_NAME_LITERAL = "gui.xaero_deathpoint_old";
    /** Format X §6: y token 4 may be this literal meaning "height unknown". */
    private static final String UNKNOWN_Y_TOKEN = "~";
    private static final double UNKNOWN_Y_FALLBACK = 64.0;
    /** Format X §6: tokens 0-9 are mandatory; the reference loader rejects shorter lines. */
    private static final int MIN_TOKENS = 10;

    private XaeroWaypointsParser() {
    }

    public static List<ImportedWaypoint> parse(final List<String> lines, final DimensionId dimension) {
        final List<ImportedWaypoint> result = new ArrayList<>();
        for (final String line : lines) {
            final ImportedWaypoint waypoint = parseLine(line, dimension);
            if (waypoint != null) {
                result.add(waypoint);
            }
        }
        return result;
    }

    private static ImportedWaypoint parseLine(final String line, final DimensionId dimension) {
        final String[] tokens = line.trim().split(":", -1);
        if (tokens.length < MIN_TOKENS || !tokens[0].equals("waypoint")) {
            return null;
        }
        final double x;
        final double y;
        final double z;
        final int colorIndex;
        final int type;
        try {
            x = Integer.parseInt(tokens[3]);
            y = UNKNOWN_Y_TOKEN.equals(tokens[4]) ? UNKNOWN_Y_FALLBACK : Integer.parseInt(tokens[4]);
            z = Integer.parseInt(tokens[5]);
            colorIndex = Integer.parseInt(tokens[6]);
            type = Integer.parseInt(tokens[8]);
        } catch (final NumberFormatException e) {
            return null;
        }
        final boolean disabled = Boolean.parseBoolean(tokens[7]);
        return new ImportedWaypoint(
            displayName(unescape(tokens[1])),
            dimension,
            x,
            y,
            z,
            PALETTE[colorIndex < 0 ? 0 : colorIndex % 16],
            setName(tokens[9]),
            !disabled,
            type == 0 ? Waypoint.Type.NORMAL : Waypoint.Type.DEATH
        );
    }

    /** Format X §8: {@code :} in name/initials is stored as the two-character token below. */
    private static String unescape(final String value) {
        return value.replace("§§", ":");
    }

    /** Death points are stored under translation-key names; map to their English display strings. */
    private static String displayName(final String name) {
        if (DEATH_NAME_LITERAL.equals(name)) {
            return "Latest Death";
        }
        if (OLD_DEATH_NAME_LITERAL.equals(name)) {
            return "Old Death";
        }
        return name;
    }

    /** Set tokens stay escaped on disk permanently; decode for display and fold the default set. */
    private static String setName(final String stored) {
        if (DEFAULT_SET_LITERAL.equals(stored)) {
            return "";
        }
        return unescape(stored);
    }
}
