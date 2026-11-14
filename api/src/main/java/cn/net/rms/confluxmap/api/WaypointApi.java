package cn.net.rms.confluxmap.api;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Read and write access to the current world's waypoints. Reads return immutable
 * snapshots from any thread; writes marshal to the client main thread and report their
 * outcome through the returned future.
 */
@ApiStatus.Experimental
public interface WaypointApi {
    /** All waypoints of the current world, in insertion order. Empty between sessions. */
    List<ApiWaypoint> list();

    Optional<ApiWaypoint> get(UUID id);

    /** Set (group) names; the default set is named {@code ""} (empty string). */
    List<String> sets();

    /** Creates a waypoint. Missing position/dimension fields default to the player's current viewpoint. */
    CompletableFuture<WaypointMutation> add(WaypointEdit waypoint);

    /** Applies every non-null field of {@code changes}; null fields keep the stored value. */
    CompletableFuture<WaypointMutation> update(UUID id, WaypointEdit changes);

    CompletableFuture<WaypointMutation> remove(UUID id);

    /** One saved location. Coordinates are raw local coordinates in {@code dimensionId}. */
    record ApiWaypoint(
        UUID id,
        String name,
        String dimensionId,
        double x,
        double y,
        double z,
        int colorArgb,
        String setName,
        boolean visible,
        boolean crossDimensionVisible,
        WaypointType type,
        String iconItemId,
        String markerLabel,
        String description,
        String imageId,
        long createdAtEpochMs
    ) {
    }

    /** Marker style of a waypoint; death waypoints get the mod's death-point visuals. */
    enum WaypointType { NORMAL, DEATH }

    /**
     * Outcome of one mutation. {@code waypoint} is present when the result is
     * {@link Result#APPLIED} (the created/updated entry) or {@link Result#NO_CHANGE}.
     */
    record WaypointMutation(Result result, ApiWaypoint waypoint) {
        public enum Result {
            APPLIED,
            NO_CHANGE,
            /** Missing name for {@code add}, or an unknown waypoint id for the others. */
            INVALID,
            NOT_FOUND,
            /** No world session is active. */
            NO_SESSION,
            /** The store refuses writes (shared/managed view). */
            READ_ONLY
        }
    }

    /**
     * Mutable-field carrier for {@code add}/{@code update}. On {@code add}, {@code name}
     * is required and missing position/dimension fields fall back to the player's current
     * viewpoint and dimension. On {@code update}, {@code null} fields keep the stored
     * value. Build one with {@link #builder()}.
     */
    record WaypointEdit(
        String name,
        String dimensionId,
        Double x,
        Double y,
        Double z,
        Integer colorArgb,
        String group,
        Boolean visible,
        Boolean crossDimensionVisible,
        WaypointType type,
        String iconItemId,
        String markerLabel,
        String description,
        String imageId
    ) {
        public static Builder builder() {
            return new Builder();
        }

        /** Fluent builder; every unset field stays {@code null}. */
        public static final class Builder {
            private String name;
            private String dimensionId;
            private Double x;
            private Double y;
            private Double z;
            private Integer colorArgb;
            private String group;
            private Boolean visible;
            private Boolean crossDimensionVisible;
            private WaypointType type;
            private String iconItemId;
            private String markerLabel;
            private String description;
            private String imageId;

            public Builder name(final String name) {
                this.name = name;
                return this;
            }

            public Builder dimensionId(final String dimensionId) {
                this.dimensionId = dimensionId;
                return this;
            }

            public Builder position(final double x, final double y, final double z) {
                this.x = x;
                this.y = y;
                this.z = z;
                return this;
            }

            public Builder colorArgb(final int colorArgb) {
                this.colorArgb = colorArgb;
                return this;
            }

            public Builder group(final String group) {
                this.group = group;
                return this;
            }

            public Builder visible(final boolean visible) {
                this.visible = visible;
                return this;
            }

            public Builder crossDimensionVisible(final boolean crossDimensionVisible) {
                this.crossDimensionVisible = crossDimensionVisible;
                return this;
            }

            public Builder type(final WaypointType type) {
                this.type = type;
                return this;
            }

            public Builder iconItemId(final String iconItemId) {
                this.iconItemId = iconItemId;
                return this;
            }

            public Builder markerLabel(final String markerLabel) {
                this.markerLabel = markerLabel;
                return this;
            }

            public Builder description(final String description) {
                this.description = description;
                return this;
            }

            public Builder imageId(final String imageId) {
                this.imageId = imageId;
                return this;
            }

            public WaypointEdit build() {
                return new WaypointEdit(
                    name, dimensionId, x, y, z, colorArgb, group, visible,
                    crossDimensionVisible, type, iconItemId, markerLabel,
                    description, imageId
                );
            }
        }
    }
}
