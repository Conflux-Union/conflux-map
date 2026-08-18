package cn.net.rms.confluxmap.core.config;

/**
 * A rectangle in scaled GUI coordinates, with exclusive right and bottom edges.
 *
 * <p>Shared by every HUD avoidance policy so a measured vanilla element and the minimap's own
 * footprint compare directly, without a conversion step that could disagree about edge semantics.
 */
public record HudRect(int left, int top, int right, int bottom) {
    /** Absorbs the float error of dividing a measured corner back out of an applied transform. */
    private static final float SNAP = 1e-3f;

    public HudRect {
        if (right < left || bottom < top) {
            throw new IllegalArgumentException("bounds must not have negative dimensions");
        }
    }

    /** Rounds a measured rectangle outward so a scaled element is never under-reported. */
    public static HudRect enclosing(
        final float left,
        final float top,
        final float right,
        final float bottom
    ) {
        final int roundedLeft = (int) Math.floor(left + SNAP);
        final int roundedTop = (int) Math.floor(top + SNAP);
        return new HudRect(
            roundedLeft,
            roundedTop,
            Math.max(roundedLeft, (int) Math.ceil(right - SNAP)),
            Math.max(roundedTop, (int) Math.ceil(bottom - SNAP))
        );
    }

    public int width() {
        return right - left;
    }

    public int height() {
        return bottom - top;
    }

    public boolean overlaps(final HudRect other) {
        return other != null
            && left < other.right()
            && right > other.left()
            && top < other.bottom()
            && bottom > other.top();
    }

    /** Returns the smallest rectangle containing both, treating a null operand as absent. */
    public HudRect union(final HudRect other) {
        if (other == null) {
            return this;
        }
        return new HudRect(
            Math.min(left, other.left()),
            Math.min(top, other.top()),
            Math.max(right, other.right()),
            Math.max(bottom, other.bottom())
        );
    }
}
