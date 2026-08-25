package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** User-selected dimension scope for the waypoint management page. */
public record WaypointDimensionFilter(Mode mode, DimensionId dimension) {
    public enum Mode { CURRENT, ALL, ONLY }

    public WaypointDimensionFilter {
        Objects.requireNonNull(mode, "mode");
        if (mode == Mode.ONLY) {
            Objects.requireNonNull(dimension, "dimension");
        } else if (dimension != null) {
            throw new IllegalArgumentException("only a specific-dimension filter can carry a dimension");
        }
    }

    public static WaypointDimensionFilter current() {
        return new WaypointDimensionFilter(Mode.CURRENT, null);
    }

    public static WaypointDimensionFilter all() {
        return new WaypointDimensionFilter(Mode.ALL, null);
    }

    public static WaypointDimensionFilter only(final DimensionId dimension) {
        return new WaypointDimensionFilter(Mode.ONLY, dimension);
    }

    public boolean matches(final DimensionId candidate, final DimensionId currentDimension) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(currentDimension, "currentDimension");
        return switch (mode) {
            case CURRENT -> candidate.equals(currentDimension);
            case ALL -> true;
            case ONLY -> candidate.equals(dimension);
        };
    }

    public WaypointDimensionFilter normalized(
        final DimensionId currentDimension,
        final List<DimensionId> knownDimensions
    ) {
        if (mode != Mode.ONLY) {
            return this;
        }
        return dimension.equals(currentDimension) || !knownDimensions.contains(dimension)
            ? current()
            : this;
    }

    public static List<WaypointDimensionFilter> options(
        final DimensionId currentDimension,
        final List<DimensionId> knownDimensions
    ) {
        Objects.requireNonNull(currentDimension, "currentDimension");
        Objects.requireNonNull(knownDimensions, "knownDimensions");
        final List<WaypointDimensionFilter> result = new ArrayList<>();
        result.add(current());
        result.add(all());
        knownDimensions.stream()
            .filter(dimension -> !dimension.equals(currentDimension))
            .distinct()
            .map(WaypointDimensionFilter::only)
            .forEach(result::add);
        return List.copyOf(result);
    }
}
