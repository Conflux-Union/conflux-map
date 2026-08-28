package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.ArrayList;
import java.util.Arrays;
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
     * How this tile's pixels can be re-lit in place when the day/night factor or gamma moves on
     * without a recompose - the fix for tiles whose backing regions were evicted from the
     * in-memory store (their data is only on disk, so {@code TileService#markSurfaceRelit}
     * can't reach them, yet their GPU texture keeps being drawn). {@code composedDaylight}
     * and {@code composedGamma} are the {@code DaylightModel} inputs used at compose time
     * (1.0 when dynamic lighting was off - compose then leaves pixels untouched, which is
     * bit-identical to darkening with factor 1.0); {@code lightLevels} is the tile's
     * per-pixel 0-15 block-light plane, needed so torch-lit pixels keep their own
     * brightness through a re-light instead of scaling with the sky. Null on tiles whose
     * colors never respond to daylight (cave/nether/end layers and biome-color tiles).
     */
    public record Relight(
        float composedDaylight,
        float composedGamma,
        byte[] lightLevels,
        MapColorStyle style
    ) {
        public Relight(final float composedDaylight, final byte[] lightLevels) {
            this(composedDaylight, 0f, lightLevels, MapColorStyle.CONFLUX);
        }

        public Relight(
            final float composedDaylight,
            final byte[] lightLevels,
            final MapColorStyle style
        ) {
            this(composedDaylight, 0f, lightLevels, style);
        }
    }

    public static TileUpdate fullTile(final TileKey key, final int[] argbPixels) {
        return fullTile(key, argbPixels, null);
    }

    public static TileUpdate fullTile(final TileKey key, final int[] argbPixels, final Relight relight) {
        return new TileUpdate(key, argbPixels, FULL_TILE, relight);
    }

    /**
     * Combines two not-yet-uploaded updates for the same tile. Newer rectangles overwrite older
     * pixels while untouched older rectangles remain present.
     */
    public static TileUpdate merge(final TileUpdate older, final TileUpdate newer) {
        if (!older.key.equals(newer.key)) {
            throw new IllegalArgumentException("cannot merge updates for different tiles");
        }
        final int[] pixels = Arrays.copyOf(older.argbPixels, older.argbPixels.length);
        copyRects(newer.argbPixels, pixels, newer.changed);
        final List<Rect> changed = new ArrayList<>(older.changed.size() + newer.changed.size());
        changed.addAll(older.changed);
        changed.addAll(newer.changed);
        return new TileUpdate(older.key, pixels, List.copyOf(changed), mergeRelight(older, newer));
    }

    private static Relight mergeRelight(final TileUpdate older, final TileUpdate newer) {
        if (older.relight == null || newer.relight == null
            || older.relight.composedDaylight != newer.relight.composedDaylight
            || older.relight.composedGamma != newer.relight.composedGamma
            || older.relight.style != newer.relight.style) {
            return newer.relight;
        }
        final byte[] levels = Arrays.copyOf(
            older.relight.lightLevels, older.relight.lightLevels.length
        );
        copyRects(newer.relight.lightLevels, levels, newer.changed);
        return new Relight(
            newer.relight.composedDaylight,
            newer.relight.composedGamma,
            levels,
            newer.relight.style
        );
    }

    private static void copyRects(final int[] source, final int[] target, final List<Rect> rects) {
        for (final Rect rect : rects) {
            for (int row = 0; row < rect.height; row++) {
                final int offset = (rect.y + row) * 256 + rect.x;
                System.arraycopy(source, offset, target, offset, rect.width);
            }
        }
    }

    private static void copyRects(final byte[] source, final byte[] target, final List<Rect> rects) {
        for (final Rect rect : rects) {
            for (int row = 0; row < rect.height; row++) {
                final int offset = (rect.y + row) * 256 + rect.x;
                System.arraycopy(source, offset, target, offset, rect.width);
            }
        }
    }
}
