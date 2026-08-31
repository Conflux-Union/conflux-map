package cn.net.rms.confluxmap.core.waypoint;

import java.util.regex.Pattern;

/** Validation and normalization shared by local forms and companion protocol boundaries. */
public final class WaypointMarkerStyle {
    public static final int MAX_LABEL_CODE_POINTS = 3;

    private static final Pattern VANILLA_ITEM_ID = Pattern.compile("minecraft:[a-z0-9/._-]+");

    private WaypointMarkerStyle() {
    }

    public static String markerLabel(final String value) {
        final String normalized = value == null ? "" : value.trim();
        if (normalized.codePointCount(0, normalized.length()) > MAX_LABEL_CODE_POINTS) {
            throw new IllegalArgumentException("marker label exceeds three characters");
        }
        for (int offset = 0; offset < normalized.length();) {
            final int codePoint = normalized.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                || codePoint == '\u00a7'
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.SURROGATE) {
                throw new IllegalArgumentException("marker label contains unsafe formatting");
            }
            offset += Character.charCount(codePoint);
        }
        return normalized;
    }

    public static String iconItemId(final String value) {
        final String normalized = value == null ? "" : value.trim();
        if (!normalized.isEmpty() && !VANILLA_ITEM_ID.matcher(normalized).matches()) {
            throw new IllegalArgumentException("icon item id must use the minecraft namespace");
        }
        return normalized;
    }
}
