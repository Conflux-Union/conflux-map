package cn.net.rms.confluxmap.core.tile;

import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.model.TileKey;
import java.util.ArrayList;
import java.util.List;

/**
 * One freshly-composed tile, ready for the render thread to upload. Full updates keep a
 * 256x256 row-major {@code argbPixels} plane. A single-rectangle patch may instead carry only
 * that rectangle's packed pixels; {@code payloadBounds} identifies their destination. This
 * avoids allocating two whole tile planes for every 18x18 live-chunk refresh. {@code changed}
 * lists the sub-rects this pass actually composed, and the texture cache keeps its previous
 * content everywhere else.
 */
public record TileUpdate(
    TileKey key,
    int[] argbPixels,
    List<Rect> changed,
    Relight relight,
    Rect payloadBounds
) {
    /** One composed sub-rect of the 256x256 tile, in tile-pixel coordinates. */
    public record Rect(int x, int y, int width, int height) {
    }

    private static final List<Rect> FULL_TILE = List.of(new Rect(0, 0, 256, 256));

    /** Backward-compatible full-plane constructor used by prediction and tests. */
    public TileUpdate(
        final TileKey key,
        final int[] argbPixels,
        final List<Rect> changed,
        final Relight relight
    ) {
        this(key, argbPixels, changed, relight, null);
    }

    /** A packed single-rectangle update. Both color and light planes use the rectangle's stride. */
    public static TileUpdate patch(
        final TileKey key,
        final int[] argbPixels,
        final Rect changed,
        final Relight relight
    ) {
        final int expected = changed.width * changed.height;
        if (argbPixels.length != expected
            || (relight != null && relight.lightLevels.length != expected)) {
            throw new IllegalArgumentException("packed tile patch has the wrong plane size");
        }
        return new TileUpdate(key, argbPixels, List.of(changed), relight, changed);
    }

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
        return new TileUpdate(key, argbPixels, FULL_TILE, relight, null);
    }

    /** Returns one destination pixel regardless of whether this update is full or packed. */
    public int pixelAt(final int x, final int y) {
        return argbPixels[sourceIndex(x, y)];
    }

    /** Returns one destination light level regardless of whether this update is full or packed. */
    public byte lightLevelAt(final int x, final int y) {
        if (relight == null) {
            throw new IllegalStateException("tile update has no relight plane");
        }
        return relight.lightLevels[sourceIndex(x, y)];
    }

    /** Source-plane index for the first pixel of a destination row segment. */
    public int sourceIndexAt(final int x, final int y) {
        return sourceIndex(x, y);
    }

    private int sourceIndex(final int x, final int y) {
        if (payloadBounds == null) {
            return y * 256 + x;
        }
        return (y - payloadBounds.y) * payloadBounds.width + x - payloadBounds.x;
    }

    /**
     * Combines two not-yet-uploaded updates for the same tile. Newer rectangles overwrite older
     * pixels while untouched older rectangles remain present.
     */
    public static TileUpdate merge(final TileUpdate older, final TileUpdate newer) {
        if (!older.key.equals(newer.key)) {
            throw new IllegalArgumentException("cannot merge updates for different tiles");
        }
        final int[] pixels = new int[256 * 256];
        copyRects(older, pixels);
        copyRects(newer, pixels);
        final List<Rect> changed = new ArrayList<>(older.changed.size() + newer.changed.size());
        changed.addAll(older.changed);
        changed.addAll(newer.changed);
        return new TileUpdate(
            older.key, pixels, List.copyOf(changed), mergeRelight(older, newer), null
        );
    }

    private static Relight mergeRelight(final TileUpdate older, final TileUpdate newer) {
        if (older.relight == null || newer.relight == null
            || older.relight.composedDaylight != newer.relight.composedDaylight
            || older.relight.composedGamma != newer.relight.composedGamma
            || older.relight.style != newer.relight.style) {
            return expandedRelight(newer);
        }
        final byte[] levels = new byte[256 * 256];
        copyLightRects(older, levels);
        copyLightRects(newer, levels);
        return new Relight(
            newer.relight.composedDaylight,
            newer.relight.composedGamma,
            levels,
            newer.relight.style
        );
    }

    private static Relight expandedRelight(final TileUpdate update) {
        if (update.relight == null || update.payloadBounds == null) {
            return update.relight;
        }
        final byte[] levels = new byte[256 * 256];
        copyLightRects(update, levels);
        return new Relight(
            update.relight.composedDaylight,
            update.relight.composedGamma,
            levels,
            update.relight.style
        );
    }

    private static void copyRects(final TileUpdate source, final int[] target) {
        for (final Rect rect : source.changed) {
            for (int row = 0; row < rect.height; row++) {
                final int targetOffset = (rect.y + row) * 256 + rect.x;
                if (source.payloadBounds == null) {
                    System.arraycopy(source.argbPixels, targetOffset, target, targetOffset, rect.width);
                } else {
                    final int sourceOffset = (rect.y + row - source.payloadBounds.y)
                        * source.payloadBounds.width + rect.x - source.payloadBounds.x;
                    System.arraycopy(source.argbPixels, sourceOffset, target, targetOffset, rect.width);
                }
            }
        }
    }

    private static void copyLightRects(final TileUpdate source, final byte[] target) {
        for (final Rect rect : source.changed) {
            for (int row = 0; row < rect.height; row++) {
                final int targetOffset = (rect.y + row) * 256 + rect.x;
                if (source.payloadBounds == null) {
                    System.arraycopy(
                        source.relight.lightLevels, targetOffset, target, targetOffset, rect.width
                    );
                } else {
                    final int sourceOffset = (rect.y + row - source.payloadBounds.y)
                        * source.payloadBounds.width + rect.x - source.payloadBounds.x;
                    System.arraycopy(
                        source.relight.lightLevels, sourceOffset, target, targetOffset, rect.width
                    );
                }
            }
        }
    }
}
