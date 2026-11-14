package cn.net.rms.confluxmap.core.waypoint;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * Validation and normalization of the waypoint notes payload (multi-line
 * description plus preview-image reference) shared by local forms, the
 * companion protocol validator, and the API boundary. Mirrors
 * {@link WaypointMarkerStyle}: violations throw
 * {@link IllegalArgumentException}, and disk/protocol loaders translate that
 * into warn-and-clear instead of dropping the waypoint.
 */
public final class WaypointDescription {
    /** Hard cap on the stored description, in code points (8x the name cap). */
    public static final int MAX_CODE_POINTS = 512;
    /** Wire-facing cap; 512 four-byte code points already saturate it. */
    public static final int MAX_UTF8_BYTES = 2048;
    /** Maximum lines after blank-line collapsing; blank lines count. */
    public static final int MAX_LINES = 16;

    private WaypointDescription() {
    }

    /**
     * Normalizes a multi-line description: strips carriage returns and
     * per-line trailing spaces, collapses runs of blank lines to one, trims
     * the outer edges, and rejects control characters other than {@code \n}
     * (plus section signs, format, and surrogate code points). Null, blank,
     * and fully stripped input normalize to {@code ""}.
     */
    public static String normalize(final String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        final String[] lines = value.replace("\r", "").split("\n", -1);
        final StringBuilder out = new StringBuilder(value.length());
        int pendingBlankLines = 0;
        for (final String line : lines) {
            final String stripped = stripTrailingSpaces(line);
            if (stripped.isEmpty()) {
                pendingBlankLines++;
                continue;
            }
            if (!out.isEmpty()) {
                out.append('\n');
                if (pendingBlankLines > 0) {
                    out.append('\n');
                }
            }
            pendingBlankLines = 0;
            out.append(stripped);
        }
        final String normalized = out.toString().trim();
        if (normalized.isEmpty()) {
            return "";
        }
        for (int offset = 0; offset < normalized.length();) {
            final int codePoint = normalized.codePointAt(offset);
            if (codePoint != '\n'
                && (Character.isISOControl(codePoint)
                    || codePoint == '§'
                    || Character.getType(codePoint) == Character.FORMAT
                    || Character.getType(codePoint) == Character.SURROGATE)) {
                throw new IllegalArgumentException("waypoint description contains unsafe formatting");
            }
            offset += Character.charCount(codePoint);
        }
        if (normalized.codePointCount(0, normalized.length()) > MAX_CODE_POINTS) {
            throw new IllegalArgumentException("waypoint description exceeds " + MAX_CODE_POINTS + " code points");
        }
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_UTF8_BYTES) {
            throw new IllegalArgumentException("waypoint description exceeds " + MAX_UTF8_BYTES + " UTF-8 bytes");
        }
        if (normalized.split("\n", -1).length > MAX_LINES) {
            throw new IllegalArgumentException("waypoint description exceeds " + MAX_LINES + " lines");
        }
        return normalized;
    }

    /** Empty string means "no image"; anything else must be a UUID, canonicalized on return. */
    public static String normalizeImageId(final String value) {
        final String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            return "";
        }
        try {
            return UUID.fromString(normalized).toString();
        } catch (final IllegalArgumentException e) {
            throw new IllegalArgumentException("waypoint image id must be a canonical uuid or empty", e);
        }
    }

    private static String stripTrailingSpaces(final String line) {
        int end = line.length();
        while (end > 0 && line.charAt(end - 1) == ' ') {
            end--;
        }
        return line.substring(0, end);
    }
}
