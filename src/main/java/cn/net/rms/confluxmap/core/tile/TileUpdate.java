package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.List;

/**
 * One freshly-composed tile, ready for the render thread to upload. {@code argbPixels}
 * is 256x256 (LOD0), row-major, {@code z * 256 + x}. {@code changed} lists the sub-rects
 * this pass actually composed: a tile backed by in-memory data reports the full tile,
 * while a LOD-N compose whose covered regions are only partially resident reports one
 * rect per resident region's quadrant. Pixels outside every rect are unspecified (left
 * at zero) and the texture cache must keep its previous content there - that is what
 * stops a recompose from erasing quadrants whose regions were evicted to disk.
 */
public record TileUpdate(
    TileKey key,
    int[] argbPixels,
    List<Rect> changed,
    Relight relight
) {
    /** One composed sub-rect of the 256x256 tile, in tile-pixel coordinates. */
    public record Rect(int x, int y, int width, int height) {
    }

    private static final List<Rect> FULL_TILE = List.of(new Rect(0, 0, 256, 256));

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
        return new TileUpdate(key, argbPixels, FULL_TILE, relight);
    }
}
