package cn.net.rms.confluxmap.core.predict;

import java.util.Arrays;

/**
 * One predicted tile's raw sampled data at one LOD: a cubiomes biome id and predicted base
 * surface column per pixel, plus a 1-pixel margin on every edge so directional relief can read
 * both three-sample shoulders around an output pixel without needing another tile's data. Unlike real
 * captured tiles, a predicted tile can simply sample a slightly larger area directly from the
 * seed. Every Overworld LOD retains a per-output-pixel overview and applies a sparse exact-height
 * residual at a screen-space-stable interval.
 *
 * <p>Indexed via {@link #index(int, int)}, local pixel coordinates {@code [-MARGIN,
 * PIXELS-1+MARGIN]} in both axes.
 */
public final class BaselineGrid {
    public static final int PIXELS = 256;
    public static final int MARGIN = 1;
    public static final int SIZE = PIXELS + 2 * MARGIN;
    /** {@link #subPerAxis} value meaning "one sample per pixel", i.e. no supersampling. */
    public static final int NO_SUPERSAMPLING = 1;

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

    /**
     * Sub-samples per axis inside one output pixel. {@link #NO_SUPERSAMPLING} at LODs whose pixel
     * is already at or below the native quart biome grid; 2 at the coarse LODs, where one biome
     * lookup per pixel drops most thin features (rivers, shorelines) between samples.
     */
    public final int subPerAxis;
    /**
     * Biome id per sub-sample, {@code subCount()} entries per pixel laid out row-major inside the
     * pixel (see {@link #subIndex}). Empty when {@link #subPerAxis} is {@link #NO_SUPERSAMPLING}.
     *
     * <p>Only the biome varies between a pixel's sub-samples: terrain height is deliberately shared
     * from the pixel centre, because the overview height field is smooth enough that a river
     * valley's sub-sea-level depression already spans the whole pixel. Measured on 1.21.5, adding
     * per-sub-sample heights recovers a further 3% of water pixels for 4x the sampling cost.
     */
    public final int[] subBiomeId;
    /** Per-sub-sample counterpart of {@link #baseSurfaceY}. */
    public final int[] subBaseSurfaceY;
    /** Per-sub-sample counterpart of {@link #surfaceFlags}. */
    public final int[] subSurfaceFlags;

    private final int blocksPerPixel;
    private final int tileOriginX;
    private final int tileOriginZ;

    public BaselineGrid() {
        this(0, 0, 0);
    }

    BaselineGrid(final int lod, final int tileOriginX, final int tileOriginZ) {
        this(lod, tileOriginX, tileOriginZ, NO_SUPERSAMPLING);
    }

    BaselineGrid(
        final int lod, final int tileOriginX, final int tileOriginZ, final int subPerAxis
    ) {
        if (subPerAxis < 1 || (1 << lod) % subPerAxis != 0) {
            throw new IllegalArgumentException(
                "subPerAxis " + subPerAxis + " does not divide LOD" + lod + "'s pixel"
            );
        }
        this.blocksPerPixel = 1 << lod;
        this.tileOriginX = tileOriginX;
        this.tileOriginZ = tileOriginZ;
        this.subPerAxis = subPerAxis;
        final int subCells = subPerAxis == NO_SUPERSAMPLING ? 0 : SIZE * SIZE * subPerAxis * subPerAxis;
        this.subBiomeId = new int[subCells];
        this.subBaseSurfaceY = new int[subCells];
        this.subSurfaceFlags = new int[subCells];
        Arrays.fill(fluidY, NO_FLUID);
        Arrays.fill(baseSurfaceY, NO_SURFACE);
        Arrays.fill(subBaseSurfaceY, NO_SURFACE);
    }

    public static int index(final int localX, final int localZ) {
        return (localZ + MARGIN) * SIZE + (localX + MARGIN);
    }

    /** Sub-samples per pixel; 1 when this grid is not supersampled. */
    public int subCount() {
        return subPerAxis * subPerAxis;
    }

    public boolean supersampled() {
        return subPerAxis != NO_SUPERSAMPLING;
    }

    /** Index into the {@code sub*} arrays for sub-sample {@code (subX, subZ)} of {@code pixelIndex}. */
    public int subIndex(final int pixelIndex, final int subX, final int subZ) {
        return (pixelIndex * subPerAxis + subZ) * subPerAxis + subX;
    }

    int blockX(final int localX) {
        return tileOriginX + localX * blocksPerPixel;
    }

    int blockZ(final int localZ) {
        return tileOriginZ + localZ * blocksPerPixel;
    }

    /** World X of one sub-sample; equals {@link #blockX} when not supersampled. */
    int subBlockX(final int localX, final int subX) {
        return blockX(localX) + subX * (blocksPerPixel / subPerAxis);
    }

    /** World Z of one sub-sample; equals {@link #blockZ} when not supersampled. */
    int subBlockZ(final int localZ, final int subZ) {
        return blockZ(localZ) + subZ * (blocksPerPixel / subPerAxis);
    }
}
