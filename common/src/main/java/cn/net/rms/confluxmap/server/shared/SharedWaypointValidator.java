package cn.net.rms.confluxmap.server.shared;

import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointMarkerStyle;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Semantic validation for untrusted shared-waypoint create requests. */
public final class SharedWaypointValidator {
    public static final int MAX_NAME_CODE_POINTS = 64;
    public static final double MAX_HORIZONTAL_COORDINATE = 29_999_984d;

    public record HeightRange(int minInclusive, int maxExclusive) {
        public HeightRange {
            if (minInclusive >= maxExclusive) {
                throw new IllegalArgumentException("height range must not be empty");
            }
        }
    }

    public record ValidatedCreate(
        String name,
        DimensionId dimensionId,
        double x,
        double y,
        double z,
        int colorArgb,
        Waypoint.Type type,
        String iconItemId,
        String markerLabel
    ) {
    }

    private final Map<DimensionId, HeightRange> dimensions;

    public SharedWaypointValidator(final Map<DimensionId, HeightRange> dimensions) {
        Objects.requireNonNull(dimensions, "dimensions");
        if (dimensions.isEmpty()) {
            throw new IllegalArgumentException("at least one dimension is required");
        }
        this.dimensions = Map.copyOf(new LinkedHashMap<>(dimensions));
    }

    /** Returns empty for any semantic violation; callers expose one non-sensitive invalid error. */
    public Optional<ValidatedCreate> validate(
        final String name,
        final DimensionId dimensionId,
        final double x,
        final double y,
        final double z,
        final int colorArgb,
        final Waypoint.Type type,
        final String iconItemId,
        final String markerLabel
    ) {
        if (name == null || dimensionId == null || type == null) {
            return Optional.empty();
        }
        final String normalizedName = name.strip();
        if (!validName(normalizedName)) {
            return Optional.empty();
        }
        final HeightRange heightRange = dimensions.get(dimensionId);
        if (heightRange == null
            || !Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)
            || Math.abs(x) > MAX_HORIZONTAL_COORDINATE
            || Math.abs(z) > MAX_HORIZONTAL_COORDINATE
            || y < heightRange.minInclusive() || y >= heightRange.maxExclusive()
            || (colorArgb >>> 24) != 0xFF) {
            return Optional.empty();
        }
        final String normalizedIconItemId;
        final String normalizedMarkerLabel;
        try {
            normalizedIconItemId = WaypointMarkerStyle.iconItemId(iconItemId);
            normalizedMarkerLabel = WaypointMarkerStyle.markerLabel(markerLabel);
        } catch (final IllegalArgumentException e) {
            return Optional.empty();
        }
        return Optional.of(new ValidatedCreate(
            normalizedName, dimensionId, x, y, z, colorArgb, type,
            normalizedIconItemId, normalizedMarkerLabel
        ));
    }

    static boolean validName(final String value) {
        return validDisplayText(value, MAX_NAME_CODE_POINTS);
    }

    static boolean validDisplayText(final String value, final int maxCodePoints) {
        if (value == null || value.isBlank() || value.codePointCount(0, value.length()) > maxCodePoints) {
            return false;
        }
        for (int offset = 0; offset < value.length();) {
            final int codePoint = value.codePointAt(offset);
            if (Character.isISOControl(codePoint)
                || codePoint == '\u00a7'
                || Character.getType(codePoint) == Character.FORMAT
                || Character.getType(codePoint) == Character.SURROGATE) {
                return false;
            }
            offset += Character.charCount(codePoint);
        }
        return true;
    }
}
