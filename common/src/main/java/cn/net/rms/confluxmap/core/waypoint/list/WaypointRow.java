package cn.net.rms.confluxmap.core.waypoint.list;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.Objects;
import java.util.UUID;

/**
 * One list row in the unified waypoint list template: a flattened, immutable
 * projection of either a local {@link Waypoint}, a shared server waypoint, or
 * a read-only cross-world guest row. The screen renders and routes every
 * gesture through this shape plus {@link WaypointListModel} predicates and
 * never sees the underlying domain types.
 *
 * @param guest true for read-only rows the owning model does not control
 *              (cross-world siblings); the screen hides mutation gestures
 * @param hidden the row-level eye state, already resolved from whichever
 *               store owns it (local waypoint flag or shared preferences)
 * @param crossDimensionVisible whether the row renders when viewed from the
 *              portal-linked dimension (local flag or shared per-id config)
 * @param worldLabel guest rows only: label of the sibling world; empty otherwise
 */
public record WaypointRow(
    UUID id,
    String name,
    String secondaryText,
    DimensionId dimensionId,
    double x,
    double y,
    double z,
    int colorArgb,
    Waypoint.Type type,
    String iconItemId,
    String markerLabel,
    String description,
    String imageId,
    String groupKey,
    boolean hidden,
    boolean guest,
    boolean crossDimensionVisible,
    String worldLabel
) {
    public WaypointRow {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(secondaryText, "secondaryText");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(iconItemId, "iconItemId");
        Objects.requireNonNull(markerLabel, "markerLabel");
        description = description == null ? "" : description;
        imageId = imageId == null ? "" : imageId;
        Objects.requireNonNull(groupKey, "groupKey");
        Objects.requireNonNull(worldLabel, "worldLabel");
    }
}
