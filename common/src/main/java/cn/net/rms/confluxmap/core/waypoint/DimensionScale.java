package cn.net.rms.confluxmap.core.waypoint;

import cn.net.rms.confluxmap.core.model.DimensionId;

/**
 * Coordinate math and cross-dimension display filtering for waypoints. See
 * {@code docs/reference-specs/waypoint-ux.md} S3 for the behavior this
 * implements.
 *
 * <p>Vanilla declares a horizontal coordinate-scale multiplier per dimension
 * (1.0 for the Overworld and the End, 8.0 for the Nether) that its own portal
 * linking logic uses; the classic "Nether = Overworld / 8" rule falls
 * directly out of that. Y is never scaled, in any dimension.
 *
 * <p>Display filtering is a separate concern from the scale math: the
 * Overworld and the Nether are portal-linked (that's the entire reason the
 * 8:1 ratio exists), so a waypoint from either one can be shown while
 * standing in the other, coordinates converted. The End has no portal
 * correlation to the other two - a raw coordinate there means nothing in
 * Overworld/Nether space - so End waypoints are confined to the End and vice
 * versa. Whether cross-dimension display is active at all is decided by the
 * caller ({@link WaypointRenderCatalog} reads
 * {@code ConfluxConfig.waypointCrossDimensionEnabled}, off by default).
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
     * Whether a waypoint stored in {@code waypointDimension} should be
     * displayed (minimap, fullscreen map, in-world) while the player is
     * currently standing in {@code currentDimension}.
     */
    public static boolean isVisibleFrom(final DimensionId waypointDimension, final DimensionId currentDimension) {
        if (waypointDimension.equals(currentDimension)) {
            return true;
        }
        return isPortalLinked(waypointDimension) && isPortalLinked(currentDimension);
    }

    private static boolean isPortalLinked(final DimensionId dimension) {
        return dimension.equals(DimensionId.OVERWORLD) || dimension.equals(DimensionId.NETHER);
    }
}
