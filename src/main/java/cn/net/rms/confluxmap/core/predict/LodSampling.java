package cn.net.rms.confluxmap.core.predict;

/**
 * Fills a {@link BaselineGrid} for one predicted tile. Biomes retain the per-LOD cubiomes sampling
 * layout, while each Overworld pixel asks the native generator for the solid terrain, fluid, and
 * final base surface at that pixel's exact block coordinate. Keeping fluid resolution inside the
 * versioned terrain generator avoids reconstructing modern aquifers from a height and biome in
 * Java. End heights retain their dimension-specific interpolation and pooling path.
 * <ul>
 *   <li>LOD0-1: final biomes are sampled at block scale, one lookup per output pixel.
 *   <li>LOD2: final biomes are sampled at native scale 4, one lookup per output pixel.
 *   <li>LOD3-4: scale-4 biomes use a 2-cell or 4-cell stride so every output pixel still maps to
 *       its own world position instead of falling back to a coarser pre-zoom layer.
 * </ul>
 *
 * <p>Every grid is sampled over the full margin range {@code
 * [-BaselineGrid.MARGIN, BaselineGrid.PIXELS-1+BaselineGrid.MARGIN]} on both axes so both slope
 * samples remain local to the tile.
 */
public final class LodSampling {
    private static final int PIXELS = BaselineGrid.PIXELS;
    private static final int P_MIN = -BaselineGrid.MARGIN;
    private static final int P_MAX = PIXELS - 1 + BaselineGrid.MARGIN;

    /** Final biome layer used per LOD (cubiomes {@code Range} scale: 1 or 4). */
    private static final int[] BIOME_SCALE = {1, 1, 4, 4, 4};
    /** Native coordinates advanced per output pixel at each LOD. */
    private static final int[] BIOME_STRIDE = {1, 2, 1, 2, 4};

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
        final BaselineGrid grid = new BaselineGrid(lod, tileOriginX, tileOriginZ);
        if (!sampleBiomes(sampler, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        if (!sampleHeights(sampler, end, lod, tileOriginX, tileOriginZ, grid)) {
            return null;
        }
        if (end) {
            for (int i = 0; i < grid.terrainY.length; i++) {
                grid.baseSurfaceY[i] = grid.terrainY[i];
            }
        }
        return grid;
    }

    private static boolean sampleBiomes(
        final BaselineSampler sampler, final int lod, final int tileOriginX, final int tileOriginZ, final BaselineGrid grid
    ) {
        final int scale = BIOME_SCALE[lod];
        final int stride = BIOME_STRIDE[lod];
        final int nativeX0 = Math.floorDiv(tileOriginX, scale) + P_MIN * stride;
        final int nativeZ0 = Math.floorDiv(tileOriginZ, scale) + P_MIN * stride;
        final int[] raw = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
        if (!sampler.biomesStrided(scale, nativeX0, nativeZ0, BaselineGrid.SIZE, BaselineGrid.SIZE, stride, raw)) {
            return false;
        }
        System.arraycopy(raw, 0, grid.biomeId, 0, raw.length);
        return true;
    }

    private static boolean sampleHeights(
        final BaselineSampler sampler, final boolean end, final int lod, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid
    ) {
        if (!end) {
            final int blocksPerPixel = 1 << lod;
            return sampler.surfaceColumns(
                tileOriginX + P_MIN * blocksPerPixel,
                tileOriginZ + P_MIN * blocksPerPixel,
                BaselineGrid.SIZE,
                BaselineGrid.SIZE,
                blocksPerPixel,
                grid.terrainY,
                grid.fluidY,
                grid.baseSurfaceY,
                grid.surfaceFlags
            );
        }
        switch (lod) {
            case 0:
                return sampleHeightsBilinear(sampler, end, tileOriginX, tileOriginZ, grid, 1);
            case 1:
                // End heights are sampled at the native 1:4 grid and interpolated to this LOD's
                // 2-block pixels.
                return sampleHeightsBilinear(sampler, end, tileOriginX, tileOriginZ, grid, 2);
            case 2:
                return sampleHeightsNearest(sampler, end, tileOriginX, tileOriginZ, grid, 1);
            case 3:
                return sampleHeightsMeanPool(sampler, end, tileOriginX, tileOriginZ, grid);
            case 4:
                // The End path uses a 64-block grid and interpolates to 16-block pixels to avoid
                // calculating full-resolution heights for a heavily downsampled tile.
                return sampleHeightsBilinearCoarse(sampler, end, tileOriginX, tileOriginZ, grid, 4);
            default:
                return false;
        }
    }

    /**
     * 4-block native samples, bilinearly interpolated to per-pixel resolution. {@code blocksPerPixel}
     * is this LOD's pixel stride (1 at LOD0, 2 at LOD1): the bilinear fraction comes from where each
     * pixel's block offset lands inside its 4-block native cell, so LOD1 stays as sharp as the 1:4
     * native data allows instead of two pixels snapping to one nearest sample.
     */
    private static boolean sampleHeightsBilinear(
        final BaselineSampler sampler, final boolean end, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int blocksPerPixel
    ) {
        final int sMin = Math.floorDiv(P_MIN * blocksPerPixel, 4);
        final int sMax = Math.floorDiv(P_MAX * blocksPerPixel, 4) + 1; // +1: bilinear's upper corner sample
        final int sw = sMax - sMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin;

        final int[] raw = new int[sw * sw];
        if (!fetchHeights(sampler, end, x4Origin, z4Origin, sw, sw, raw)) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int blockZ = pz * blocksPerPixel;
            final int baseZ = Math.floorDiv(blockZ, 4);
            final int fz = blockZ - baseZ * 4;
            final int sz0 = baseZ - sMin;
            final int sz1 = sz0 + 1;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int blockX = px * blocksPerPixel;
                final int baseX = Math.floorDiv(blockX, 4);
                final int fx = blockX - baseX * 4;
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
                    final int interpolated = top + Math.floorDiv((bottom - top) * fz, 4);
                    value = interpolated;
                }
                grid.terrainY[BaselineGrid.index(px, pz)] = value;
            }
        }
        return true;
    }

    /**
     * Samples one height every {@code pixelsPerSample} output pixels, then fixed-point bilinearly
     * interpolates between those anchors. Used by LOD4, where one output pixel is 16 blocks and
     * a four-pixel anchor interval is therefore a 64-block terrain grid.
     */
    private static boolean sampleHeightsBilinearCoarse(
        final BaselineSampler sampler, final boolean end, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int pixelsPerSample
    ) {
        final int nativeStride = pixelsPerSample * 4; // 16 blocks/pixel divided by native 4-block cells
        final int sMin = Math.floorDiv(P_MIN, pixelsPerSample);
        final int sMax = Math.floorDiv(P_MAX, pixelsPerSample) + 1;
        final int sw = sMax - sMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin * nativeStride;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin * nativeStride;
        final int[] raw = new int[sw * sw];
        final boolean sampled = end
            ? sampler.endHeightsStrided(x4Origin, z4Origin, sw, sw, nativeStride, raw)
            : sampler.heightsStrided(x4Origin, z4Origin, sw, sw, nativeStride, raw);
        if (!sampled) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int baseZ = Math.floorDiv(pz, pixelsPerSample);
            final int fz = pz - baseZ * pixelsPerSample;
            final int sz0 = baseZ - sMin;
            final int sz1 = sz0 + 1;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int baseX = Math.floorDiv(px, pixelsPerSample);
                final int fx = px - baseX * pixelsPerSample;
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
                    final int top = h00 + Math.floorDiv((h10 - h00) * fx, pixelsPerSample);
                    final int bottom = h01 + Math.floorDiv((h11 - h01) * fx, pixelsPerSample);
                    final int interpolated = top + Math.floorDiv((bottom - top) * fz, pixelsPerSample);
                    value = interpolated;
                }
                grid.terrainY[BaselineGrid.index(px, pz)] = value;
            }
        }
        return true;
    }

    /** 4-block native samples selected at {@code nativeStride} cells per output pixel. */
    private static boolean sampleHeightsNearest(
        final BaselineSampler sampler, final boolean end, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int nativeStride
    ) {
        final int[] raw = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
        final int x4 = Math.floorDiv(tileOriginX, 4) + P_MIN * nativeStride;
        final int z4 = Math.floorDiv(tileOriginZ, 4) + P_MIN * nativeStride;
        final boolean sampled = end
            ? sampler.endHeightsStrided(x4, z4, BaselineGrid.SIZE, BaselineGrid.SIZE, nativeStride, raw)
            : sampler.heightsStrided(x4, z4, BaselineGrid.SIZE, BaselineGrid.SIZE, nativeStride, raw);
        if (!sampled) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int index = BaselineGrid.index(px, pz);
                final int raw0 = raw[index];
                grid.terrainY[index] = end && raw0 == 0 ? BaselineGrid.NO_SURFACE : raw0;
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
                    final int mean = Math.floorDiv(a + b + c + d, 4);
                    value = mean;
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
