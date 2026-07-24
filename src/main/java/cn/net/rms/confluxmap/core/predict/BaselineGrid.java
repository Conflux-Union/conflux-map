package cn.net.rms.confluxmap.core.predict;

import java.util.Arrays;

/**
 * One predicted tile's raw sampled data at one LOD: a cubiomes biome id and native-resolved base
 * surface column per pixel, plus a 1-pixel margin on every edge so directional relief can read
 * both three-sample shoulders around an output pixel without needing another tile's data. Unlike real
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
    /** Sentinel for {@link #fluidY}: this column has no base fluid surface. */
    public static final int NO_FLUID = Integer.MIN_VALUE;
    /** {@link #surfaceFlags} bit: the base visible surface is fluid rather than solid terrain. */
    public static final int SURFACE_FLUID = 1;

    /** cubiomes biome id at this pixel. */
    public final int[] biomeId = new int[SIZE * SIZE];
    /** Floored terrain height, or {@link #NO_SURFACE}. */
    public final int[] terrainY = new int[SIZE * SIZE];
    /** Top base-fluid block, or {@link #NO_FLUID}. */
    public final int[] fluidY = new int[SIZE * SIZE];
    /** Base visible surface after solid/fluid resolution, or {@link #NO_SURFACE}. */
    public final int[] baseSurfaceY = new int[SIZE * SIZE];
    /** Per-column base-surface flags, including {@link #SURFACE_FLUID}. */
    public final int[] surfaceFlags = new int[SIZE * SIZE];

    private final int blocksPerPixel;
    private final int tileOriginX;
    private final int tileOriginZ;

    public BaselineGrid() {
        this(0, 0, 0);
    }

    BaselineGrid(final int lod, final int tileOriginX, final int tileOriginZ) {
        this.blocksPerPixel = 1 << lod;
        this.tileOriginX = tileOriginX;
        this.tileOriginZ = tileOriginZ;
        Arrays.fill(fluidY, NO_FLUID);
        Arrays.fill(baseSurfaceY, NO_SURFACE);
    }

    public static int index(final int localX, final int localZ) {
        return (localZ + MARGIN) * SIZE + (localX + MARGIN);
    }

    int blockX(final int localX) {
        return tileOriginX + localX * blocksPerPixel;
    }

    int blockZ(final int localZ) {
        return tileOriginZ + localZ * blocksPerPixel;
    }
}
