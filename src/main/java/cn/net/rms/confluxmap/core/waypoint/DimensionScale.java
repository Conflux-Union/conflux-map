package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * Portal-coordinate math retained for callers that explicitly need to compare
 * Overworld and Nether portal locations.
 *
 * <p>Vanilla declares a horizontal coordinate-scale multiplier per dimension
 * (1.0 for the Overworld and the End, 8.0 for the Nether) that its own portal
 * linking logic uses; the classic "Nether = Overworld / 8" rule falls
 * directly out of that. Y is never scaled, in any dimension.
 *
 * <p>Waypoint rendering deliberately does not use this conversion. A waypoint
 * is visible only in its exact stored dimension.
 */
public final class DimensionScale {
    private static final double NETHER_SCALE = 8.0;
    private static final double DEFAULT_SCALE = 1.0;

    private DimensionScale() {
    }

    /** Vanilla's declared horizontal coordinate-scale multiplier for {@code dimension}. */
    public static double scaleOf(final DimensionId dimension) {
        return dimension.equals(DimensionId.NETHER) ? NETHER_SCALE : DEFAULT_SCALE;
    }

    /**
     * Converts one horizontal coordinate stored as a raw local value in
     * {@code from} into the equivalent raw local value in {@code to}. Never
     * apply this to Y.
     */
    public static double convertHorizontal(final double value, final DimensionId from, final DimensionId to) {
        if (from.equals(to)) {
            return value;
        }
        return value * scaleOf(from) / scaleOf(to);
    }

    /**
     * Whether a waypoint belongs to the active render dimension.
     */
    public static boolean isVisibleFrom(final DimensionId waypointDimension, final DimensionId currentDimension) {
        return waypointDimension.equals(currentDimension);
    }
}
