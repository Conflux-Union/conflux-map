package cn.net.rms.confluxmap.core.waypoint.list;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import java.util.Objects;

/**
 * Form payload for creating or editing a waypoint through the unified list
 * template. The owning model maps it onto its backend: the local model
 * writes a {@link Waypoint} into the store, the shared model sends a
 * protocol mutation and persists the client-owned cross-dimension overrides.
 *
 * <p>{@link #groupKey} uses {@link GroupRef#UNGROUPED_KEY} for the ungrouped
 * bucket. {@link #crossDimensionVisible}/{@link #crossDimensionY} are the
 * client's personal rendering overrides, never server-shared data.
 */
public record EditDraft(
    String name,
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
    boolean crossDimensionVisible,
    Double crossDimensionY,
    String groupKey
) {
    public EditDraft {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(dimensionId, "dimensionId");
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(iconItemId, "iconItemId");
        Objects.requireNonNull(markerLabel, "markerLabel");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(groupKey, "groupKey");
    }
}
