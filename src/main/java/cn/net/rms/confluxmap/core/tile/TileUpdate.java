package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.model.TileKey;

/**
 * One freshly-composed tile, ready for the render thread to upload. {@code argbPixels}
 * is 256x256 (LOD0), row-major, {@code z * 256 + x}. {@code changedX/Y/Width/Height}
 * describes the sub-rect that actually changed; M1 always recomposes and reports the
 * whole tile, but the field exists so partial uploads can be added later without
 * changing this shape.
 */
public record TileUpdate(
    TileKey key,
    int[] argbPixels,
    int changedX,
    int changedY,
    int changedWidth,
    int changedHeight,
    Relight relight
) {
    /**
     * How this tile's pixels can be re-lit in place when the day/night factor moves on
     * without a recompose - the fix for tiles whose backing regions were evicted from the
     * in-memory store (their data is only on disk, so {@code TileService#markSurfaceRelit}
     * can't reach them, yet their GPU texture keeps being drawn). {@code composedDaylight}
     * is the {@code DaylightModel} factor the pixels were darkened with at compose time
     * (1.0 when dynamic lighting was off - compose then leaves pixels untouched, which is
     * bit-identical to darkening with factor 1.0); {@code lightLevels} is the tile's
     * per-pixel 0-15 block-light plane, needed so torch-lit pixels keep their own
     * brightness through a re-light instead of scaling with the sky. Null on tiles whose
     * colors never respond to daylight (cave/nether/end layers, predicted tiles).
     */
    public record Relight(float composedDaylight, byte[] lightLevels) {
    }

    public static TileUpdate fullTile(final TileKey key, final int[] argbPixels) {
        return fullTile(key, argbPixels, null);
    }

    public static TileUpdate fullTile(final TileKey key, final int[] argbPixels, final Relight relight) {
        return new TileUpdate(key, argbPixels, 0, 0, 256, 256, relight);
    }
}
