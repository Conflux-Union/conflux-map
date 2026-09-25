package cn.net.rms.confluxmap.core.waypoint;

import java.util.Objects;

/**
 * A waypoint owned by a seed-sibling world namespace, paired with the label of the world it
 * came from. Read-only by construction: the owning session mutates its own store, never this view.
 */
public record SiblingWaypoint(Waypoint waypoint, String worldLabel) {
    public SiblingWaypoint {
        Objects.requireNonNull(waypoint, "waypoint");
        worldLabel = worldLabel == null ? "" : worldLabel;
    }
}
