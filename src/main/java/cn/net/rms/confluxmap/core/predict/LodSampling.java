package cn.net.rms.confluxmap.core.predict;

import cn.net.rms.confluxmap.core.color.ShadingPipeline;

/**
 * Fills a {@link BaselineGrid} for one predicted tile (dimension/LOD/tile origin), matching the
 * plan's per-LOD table: biome scale/expansion is shared between Overworld and End (the same
 * cubiomes {@code Range} scales apply regardless of dimension), while terrain height source and
 * expansion mode differ per LOD. The margin this fills in exists for the same reason {@code
 * cn.net.rms.confluxmap.core.tile.TileService#composeRegion} reads an adjacent region's edge
 * column/row: {@link cn.net.rms.confluxmap.core.color.ShadingPipeline#slopeShade}'s fixed
 * diagonal neighbor is (x-1, z+1).
 * <ul>
 *   <li>LOD0: biomes at native scale 1 (1:1 with pixels); heights at native 1:4 scale, expanded
 *       x4 by fixed-point integer bilinear (per-axis lerp, composed - {@link Math#floorDiv} only,
 *       no float/double anywhere in this class).
 *   <li>LOD1: biomes/heights both at native scale 4 (4 blocks/sample = exactly 2 pixels/sample
 *       at this LOD's 2 blocks/pixel), nearest-neighbor expanded x2.
 *   <li>LOD2: biomes/heights both at native scale 4, exactly 1:1 with this LOD's 4-block pixels -
 *       no expansion at all.
 *   <li>LOD3: biomes at native scale 16, nearest-neighbor expanded x2; heights still queried at
 *       native 1:4 scale (finer than this LOD's 8-block pixels by exactly 2x per axis), then
 *       2x2-integer-mean pooled down to pixel resolution.
 *   <li>LOD4: biomes at native scale 16, 1:1 with pixels; heights use one nearest 1:4 sample
 *       per pixel so the coarse preview can still distinguish ocean from elevated land.
 * </ul>
 *
 * <p>Every grid (both biomes and heights) is sampled over the full margin range {@code
 * [-BaselineGrid.MARGIN, BaselineGrid.PIXELS-1+BaselineGrid.MARGIN]} on both axes - see {@link
 * BaselineGrid}'s javadoc for why a uniform (if slightly wasteful) two-sided margin is fetched
 * rather than the tighter asymmetric west/south-only range the slope-shading neighbor actually
 * needs: one indexing scheme for every LOD, dimension and axis is worth a handful of native
 * samples neither is ever read back from.
 */
public final class LodSampling {
    private static final int PIXELS = BaselineGrid.PIXELS;
    private static final int P_MIN = -BaselineGrid.MARGIN;
    private static final int P_MAX = PIXELS - 1 + BaselineGrid.MARGIN;

    /** Native biome scale per LOD (cubiomes {@code Range} scale: 1, 4 or 16). */
    private static final int[] BIOME_SCALE = {1, 4, 4, 16, 16};
    /** Pixels sharing one nearest-neighbor biome sample per LOD (1 = no expansion). */
    private static final int[] BIOME_EXPAND = {1, 2, 1, 2, 1};

    private LodSampling() {
    }

    /**
     * Samples one tile's biome ids and terrain heights, or returns null if any underlying native
     * query failed (caller should treat this as a transient no-op - see {@code
     * PredictionTileService}'s gating).
     */
    public static BaselineGrid sample(
        final BaselineSampler sampler,
        final boolean end,
        final int lod,
        final int tileOriginX,
        final int tileOriginZ
    ) {
        final BaselineGrid grid = new BaselineGrid();
        if (!sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        if (!sampleHeights(sampler, end, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        return grid;
    }

    private static boolean sampleBiomes(
        final BaselineSampler sampler, final int lod, final int tileOriginX, final int tileOriginZ, final BaselineGrid grid
    ) {
        final int scale = BIOME_SCALE[lod];
        final int expand = BIOME_EXPAND[lod];
        final int sMin = Math.floorDiv(P_MIN, expand);
        final int sMax = Math.floorDiv(P_MAX, expand);
        final int sw = sMax - sMin + 1;
        final int nativeX0 = Math.floorDiv(tileOriginX, scale) + sMin;
        final int nativeZ0 = Math.floorDiv(tileOriginZ, scale) + sMin;

        final int[] raw = new int[sw * sw];
        if (!sampler.biomes(scale, nativeX0, nativeZ0, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int sz = Math.floorDiv(pz, expand) - sMin;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int sx = Math.floorDiv(px, expand) - sMin;
                grid.biomeId[BaselineGrid.index(px, pz)] = raw[sz * sw + sx];
            }
        }
        return true;
    }

    private static boolean sampleHeights(
        final BaselineSampler sampler, final boolean end, final int lod, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid
    ) {
        switch (lod) {
            case 0:
                return sampleHeightsBilinear(sampler, end, tileOriginX, tileOriginZ, grid);
            case 1:
                return sampleHeightsNearest(sampler, end, tileOriginX, tileOriginZ, grid, 2);
            case 2:
                return sampleHeightsNearest(sampler, end, tileOriginX, tileOriginZ, grid, 1);
            case 3:
                return sampleHeightsMeanPool(sampler, end, tileOriginX, tileOriginZ, grid);
            case 4:
                // A flat reference height makes every ocean biome look like land because the
                // water rule is height-gated. One coarse 1:4 sample per 16-block pixel keeps the
                // high-LOD preview's water mask correct without paying for a full 4x4 pool.
                return sampleHeightsNearest(sampler, end, tileOriginX, tileOriginZ, grid, 1);
            default:
                return false;
        }
    }

    /** LOD0: 4-block native samples, bilinearly expanded x4 to per-pixel resolution. */
    private static boolean sampleHeightsBilinear(
        final BaselineSampler sampler, final boolean end, final int tileOriginX, final int tileOriginZ, final BaselineGrid grid
    ) {
        final int sMin = Math.floorDiv(P_MIN, 4);
        final int sMax = Math.floorDiv(P_MAX, 4) + 1; // +1: bilinear's upper corner sample
        final int sw = sMax - sMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin;

        final int[] raw = new int[sw * sw];
        if (!fetchHeights(sampler, end, x4Origin, z4Origin, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int baseZ = Math.floorDiv(pz, 4);
            final int fz = pz - baseZ * 4;
            final int sz0 = baseZ - sMin;
            final int sz1 = sz0 + 1;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int baseX = Math.floorDiv(px, 4);
                final int fx = px - baseX * 4;
                final int sx0 = baseX - sMin;
                final int sx1 = sx0 + 1;
                final int h00 = raw[sz0 * sw + sx0];
                final int h10 = raw[sz0 * sw + sx1];
                final int h01 = raw[sz1 * sw + sx0];
                final int h11 = raw[sz1 * sw + sx1];
                final int value;
                if (end && h00 == 0 && h10 == 0 && h01 == 0 && h11 == 0) {
                    value = BaselineGrid.NO_SURFACE;
                } else {
                    final int top = h00 + Math.floorDiv((h10 - h00) * fx, 4);
                    final int bottom = h01 + Math.floorDiv((h11 - h01) * fx, 4);
                    value = top + Math.floorDiv((bottom - top) * fz, 4);
                }
                grid.terrainY[BaselineGrid.index(px, pz)] = value;
            }
        }
        return true;
    }

    /** LOD1 (expand=2) / LOD2 (expand=1): 4-block native samples, nearest-neighbor expanded. */
    private static boolean sampleHeightsNearest(
        final BaselineSampler sampler, final boolean end, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int expand
    ) {
        final int sMin = Math.floorDiv(P_MIN, expand);
        final int sMax = Math.floorDiv(P_MAX, expand);
        final int sw = sMax - sMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin;

        final int[] raw = new int[sw * sw];
        if (!fetchHeights(sampler, end, x4Origin, z4Origin, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int sz = Math.floorDiv(pz, expand) - sMin;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int sx = Math.floorDiv(px, expand) - sMin;
                final int raw0 = raw[sz * sw + sx];
                grid.terrainY[BaselineGrid.index(px, pz)] = end && raw0 == 0 ? BaselineGrid.NO_SURFACE : raw0;
            }
        }
        return true;
    }

    /** LOD3: 4-block native samples at 2x this LOD's pixel resolution, 2x2-integer-mean pooled down. */
    private static boolean sampleHeightsMeanPool(
        final BaselineSampler sampler, final boolean end, final int tileOriginX, final int tileOriginZ, final BaselineGrid grid
    ) {
        final int subMin = 2 * P_MIN;
        final int subMax = 2 * P_MAX + 1;
        final int sw = subMax - subMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + subMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + subMin;

        final int[] raw = new int[sw * sw];
        if (!fetchHeights(sampler, end, x4Origin, z4Origin, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int j0 = 2 * pz - subMin;
            final int j1 = j0 + 1;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int i0 = 2 * px - subMin;
                final int i1 = i0 + 1;
                final int a = raw[j0 * sw + i0];
                final int b = raw[j0 * sw + i1];
                final int c = raw[j1 * sw + i0];
                final int d = raw[j1 * sw + i1];
                final int value;
                if (end && a == 0 && b == 0 && c == 0 && d == 0) {
                    value = BaselineGrid.NO_SURFACE;
                } else {
                    value = Math.floorDiv(a + b + c + d, 4);
                }
                grid.terrainY[BaselineGrid.index(px, pz)] = value;
            }
        }
        return true;
    }

    private static boolean fetchHeights(
        final BaselineSampler sampler, final boolean end, final int x4, final int z4, final int w, final int h, final int[] out
    ) {
        return end ? sampler.endHeights(x4, z4, w, h, out) : sampler.heights(x4, z4, w, h, out);
    }
}
