package cn.net.rms.confluxmap.core.predict;

/**
 * One predicted tile's raw sampled data at one LOD: a cubiomes biome id and a terrain height
 * per pixel, plus a 1-pixel margin on every edge so continuous slope shading can read both
 * diagonal samples around an output pixel without needing another tile's data. Unlike real
 * captured tiles, a predicted tile can simply sample a slightly larger area directly from the
 * seed.
 *
 * <p>Indexed via {@link #index(int, int)}, local pixel coordinates {@code [-MARGIN,
 * PIXELS-1+MARGIN]} in both axes.
 */
public final class BaselineGrid {
    public static final int PIXELS = 256;
    public static final int MARGIN = 1;
    public static final int SIZE = PIXELS + 2 * MARGIN;

    /** Sentinel for {@link #terrainY}: no surface here (End void between islands). */
    public static final int NO_SURFACE = Integer.MIN_VALUE;

    /** cubiomes biome id at this pixel. */
    public final int[] biomeId = new int[SIZE * SIZE];
    /** Floored terrain height, or {@link #NO_SURFACE}. */
    public final int[] terrainY = new int[SIZE * SIZE];

    public static int index(final int localX, final int localZ) {
        return (localZ + MARGIN) * SIZE + (localX + MARGIN);
    }
}
