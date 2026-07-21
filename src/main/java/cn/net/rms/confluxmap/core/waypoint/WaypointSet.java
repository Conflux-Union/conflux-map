package cn.net.rms.confluxmap.core.waypoint;

import java.util.Objects;

/**
 * A player-owned collection of local waypoints. The empty name is the
 * implicit default collection used by legacy waypoints whose {@link Waypoint#group}
 * field was empty; every non-empty name is a custom collection.
 *
 * <p>Collection names remain the persisted identity for compatibility with
 * the existing free-text {@code group} field. {@link WaypointStore} performs
 * rename/delete operations across both the collection metadata and member
 * waypoints so those two representations cannot drift.
 */
public record WaypointSet(String name) {
    public static final int MAX_NAME_LENGTH = 32;
    public static final String DEFAULT_NAME = "";
    public static final WaypointSet DEFAULT = new WaypointSet(DEFAULT_NAME);

    public WaypointSet {
        Objects.requireNonNull(name, "name");
    }

    public boolean isDefault() {
        return name.isEmpty();
    }

    /** Returns a canonical custom name, or {@code null} when user input is invalid. */
    static String normalizeCustomName(final String candidate) {
        if (candidate == null) {
            return null;
        }
        final String normalized = candidate.trim();
        if (normalized.isEmpty() || normalized.length() > MAX_NAME_LENGTH) {
            return null;
        }
        for (int i = 0; i < normalized.length(); i++) {
            if (Character.isISOControl(normalized.charAt(i))) {
                return null;
            }
        }
        return normalized;
    }
}
