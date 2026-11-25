package cn.net.rms.confluxmap.core.waypoint.library;

import java.util.Arrays;
import java.util.Optional;

/** Stable preset IDs; labels are translated by the client, never used as storage keys. */
public enum WaypointTag {
    BASE("base", "基地"),
    RESOURCE("resource", "资源"),
    BUILDING("building", "建筑"),
    EXPLORATION("exploration", "探索"),
    TRANSPORT("transport", "交通"),
    OTHER("other", "其他");

    private final String id;
    private final String legacyLabel;

    WaypointTag(final String id, final String legacyLabel) {
        this.id = id;
        this.legacyLabel = legacyLabel;
    }

    public String id() {
        return id;
    }

    public String translationKey() {
        return "confluxmap.waypoint_details.tag." + id;
    }

    public boolean matches(final String query) {
        return id.contains(query) || legacyLabel.contains(query);
    }

    public static Optional<WaypointTag> fromStored(final String value) {
        return Arrays.stream(values()).filter(tag -> tag.id.equalsIgnoreCase(value)
            || tag.legacyLabel.equals(value)).findFirst();
    }
}
