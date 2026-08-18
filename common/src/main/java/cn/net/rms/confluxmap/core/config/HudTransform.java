package cn.net.rms.confluxmap.core.config;

/** An affine transform this mod applies to a complete vanilla HUD element around the GUI origin. */
public record HudTransform(float translateX, float translateY, float scale) {
    public static final HudTransform IDENTITY = new HudTransform(0f, 0f, 1f);

    /** A pure vertical move, the shape used to push an element clear of the minimap. */
    public static HudTransform ofVerticalShift(final float shift) {
        return shift == 0f ? IDENTITY : new HudTransform(0f, shift, 1f);
    }

    /** A pure horizontal move, the shape used to push an element clear of the minimap. */
    public static HudTransform ofHorizontalShift(final float shift) {
        return shift == 0f ? IDENTITY : new HudTransform(shift, 0f, 1f);
    }

    public boolean isIdentity() {
        return equals(IDENTITY);
    }

    /** Maps a screen-space corner back to where it sat before this transform moved it. */
    public float unapplyX(final float x) {
        return scale == 0f ? x : (x - translateX) / scale;
    }

    /** Maps a screen-space corner back to where it sat before this transform moved it. */
    public float unapplyY(final float y) {
        return scale == 0f ? y : (y - translateY) / scale;
    }

    /**
     * Returns the transform to push so the on-screen result still equals this one when
     * {@code ambient} is applied around the push rather than inside it.
     *
     * <p>A mod that scales the element from outside this mod's push would otherwise scale this
     * transform's translation too, landing the element short of the reserved gap.
     */
    public HudTransform rebased(final HudAmbient ambient) {
        if (ambient == null
            || ambient.isIdentity()
            || ambient.scaleX() == 0f
            || ambient.scaleY() == 0f) {
            return this;
        }
        return new HudTransform(
            (translateX + ambient.translateX() * (scale - 1f)) / ambient.scaleX(),
            (translateY + ambient.translateY() * (scale - 1f)) / ambient.scaleY(),
            scale
        );
    }
}
