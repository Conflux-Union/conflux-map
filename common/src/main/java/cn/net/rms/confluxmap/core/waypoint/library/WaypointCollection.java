package cn.net.rms.confluxmap.core.waypoint.library;

import java.util.Objects;
import java.util.UUID;

/** A collection owns references, never the referenced waypoints. */
public record WaypointCollection(UUID id, String name, int colorArgb, String icon) {
    public static final int NAME_LIMIT = 64;

    public WaypointCollection {
        Objects.requireNonNull(id, "id");
        name = Objects.requireNonNull(name, "name").trim();
        icon = icon == null || icon.isBlank() ? "*" : icon.trim();
        if (name.isEmpty() || name.length() > NAME_LIMIT || name.chars().anyMatch(Character::isISOControl)
            || icon.length() > 16 || icon.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Invalid collection name or icon");
        }
    }
}
