package cn.net.rms.confluxmap.core.config;

/**
 * The axis-aligned transform a GUI matrix already carried before this mod pushed its own.
 *
 * <p>Vanilla leaves it at identity. Mods that resize or move a HUD element contribute one, and its
 * scale may be non-uniform, so measuring an element means asking the matrix what the coordinates
 * vanilla painted with actually map to.
 */
public record HudAmbient(float translateX, float translateY, float scaleX, float scaleY) {
    public static final HudAmbient IDENTITY = new HudAmbient(0f, 0f, 1f, 1f);

    public float applyX(final float x) {
        return x * scaleX + translateX;
    }

    public float applyY(final float y) {
        return y * scaleY + translateY;
    }

    /** Maps a rectangle from the coordinates vanilla painted in to actual screen pixels. */
    public HudRect apply(final HudRect rect) {
        return rect == null
            ? null
            : HudRect.enclosing(
                applyX(rect.left()), applyY(rect.top()),
                applyX(rect.right()), applyY(rect.bottom())
            );
    }

    /** Returns the transform that applies this one first and {@code outer} afterwards. */
    public HudAmbient then(final HudAmbient outer) {
        if (outer == null || outer.isIdentity()) {
            return this;
        }
        return new HudAmbient(
            outer.scaleX() * translateX + outer.translateX(),
            outer.scaleY() * translateY + outer.translateY(),
            scaleX * outer.scaleX(),
            scaleY * outer.scaleY()
        );
    }

    public boolean isIdentity() {
        return equals(IDENTITY);
    }
}
