package cn.net.rms.confluxmap.core.predict;

/**
 * End-dimension height sampling: the per-LOD interpolation/pooling ladder behind {@code
 * LodSampling.sample}'s End branch. Void samples stay categorical - no path here may
 * interpolate land out of a void corner.
 */
final class EndHeightsSampling {
    private EndHeightsSampling() {
    }

    private static final int P_MIN = -BaselineGrid.MARGIN;
    private static final int P_MAX = BaselineGrid.PIXELS - 1 + BaselineGrid.MARGIN;

    static boolean sampleEndHeights(
        final BaselineSampler sampler, final int lod, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid
    ) {
        switch (lod) {
            case 0:
                return sampleEndHeightsBilinear(sampler, tileOriginX, tileOriginZ, grid, 1);
            case 1:
                // End heights are sampled at the native 1:4 grid and interpolated to this LOD's
                // 2-block pixels.
                return sampleEndHeightsBilinear(sampler, tileOriginX, tileOriginZ, grid, 2);
            case 2:
                return sampleEndHeightsNearest(sampler, tileOriginX, tileOriginZ, grid, 1);
            case 3:
                return sampleEndHeightsMeanPool(sampler, tileOriginX, tileOriginZ, grid);
            case 4:
                // Double the old 64-block grid's outline resolution without paying the cost of a
                // full 16-block surface-noise map. Nearest expansion keeps void categorical: one
                // land anchor owns one 32-block cell and cannot bleed into adjacent cells.
                return sampleEndHeightsNearestCoarse(
                    sampler, tileOriginX, tileOriginZ, grid, 2
                );
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
    private static boolean sampleEndHeightsBilinear(
        final BaselineSampler sampler, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int blocksPerPixel
    ) {
        final int sMin = Math.floorDiv(P_MIN * blocksPerPixel, 4);
        final int sMax = Math.floorDiv(P_MAX * blocksPerPixel, 4) + 1; // +1: bilinear's upper corner sample
        final int sw = sMax - sMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin;

        final int[] raw = new int[sw * sw];
        if (!sampler.endHeights(x4Origin, z4Origin, sw, sw, raw)) {
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
                if (h00 == 0 && h10 == 0 && h01 == 0 && h11 == 0) {
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

    /** 4-block native samples selected at {@code nativeStride} cells per output pixel. */
    private static boolean sampleEndHeightsNearest(
        final BaselineSampler sampler, final int tileOriginX, final int tileOriginZ,
        final BaselineGrid grid, final int nativeStride
    ) {
        final int[] raw = new int[BaselineGrid.SIZE * BaselineGrid.SIZE];
        final int x4 = Math.floorDiv(tileOriginX, 4) + P_MIN * nativeStride;
        final int z4 = Math.floorDiv(tileOriginZ, 4) + P_MIN * nativeStride;
        final boolean sampled = sampler.endHeightsStrided(
            x4, z4, BaselineGrid.SIZE, BaselineGrid.SIZE, nativeStride, raw
        );
        if (!sampled) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int index = BaselineGrid.index(px, pz);
                final int raw0 = raw[index];
                grid.terrainY[index] = raw0 == 0 ? BaselineGrid.NO_SURFACE : raw0;
            }
        }
        return true;
    }

    /**
     * Samples one anchor for each square of {@code pixelsPerSample} output pixels and expands it
     * without interpolation. This is the bounded high-LOD path: terrain edges may step at the
     * sampling-cell boundary, but an End void value can never turn into synthetic land.
     */
    private static boolean sampleEndHeightsNearestCoarse(
        final BaselineSampler sampler,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid,
        final int pixelsPerSample
    ) {
        final int sMin = Math.floorDiv(P_MIN, pixelsPerSample);
        final int sMax = Math.floorDiv(P_MAX, pixelsPerSample);
        final int sw = sMax - sMin + 1;
        final int nativeStride = pixelsPerSample * 4;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + sMin * nativeStride;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + sMin * nativeStride;
        final int[] raw = new int[sw * sw];
        if (!sampler.endHeightsStrided(
            x4Origin, z4Origin, sw, sw, nativeStride, raw
        )) {
            return false;
        }
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            final int sourceZ = Math.floorDiv(pz, pixelsPerSample) - sMin;
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int sourceX = Math.floorDiv(px, pixelsPerSample) - sMin;
                final int rawY = raw[sourceZ * sw + sourceX];
                grid.terrainY[BaselineGrid.index(px, pz)] = rawY == 0
                    ? BaselineGrid.NO_SURFACE
                    : rawY;
            }
        }
        return true;
    }

    /**
     * Uses the per-output-pixel End biome layer to sharpen the 32-block height cells. A non-small-
     * islands biome may extend land by one pixel only when an exact coarse cell already proves
     * terrain immediately beside it; the copied source plane prevents the refinement itself from
     * growing repeatedly through a large biome region.
     */
    static void refineEndLod4Coverage(final BaselineGrid grid) {
        final int[] coarse = grid.terrainY.clone();
        for (int pz = P_MIN; pz <= P_MAX; pz++) {
            for (int px = P_MIN; px <= P_MAX; px++) {
                final int index = BaselineGrid.index(px, pz);
                if (coarse[index] != BaselineGrid.NO_SURFACE
                    || grid.biomeId[index] == CubiomesBiomeIds.SMALL_END_ISLANDS) {
                    continue;
                }
                int nearestHeight = BaselineGrid.NO_SURFACE;
                int nearestDistance = Integer.MAX_VALUE;
                for (int dz = -1; dz <= 1; dz++) {
                    final int nearbyZ = pz + dz;
                    if (nearbyZ < P_MIN || nearbyZ > P_MAX) {
                        continue;
                    }
                    for (int dx = -1; dx <= 1; dx++) {
                        final int nearbyX = px + dx;
                        if (nearbyX < P_MIN || nearbyX > P_MAX) {
                            continue;
                        }
                        final int height = coarse[BaselineGrid.index(nearbyX, nearbyZ)];
                        final int distance = dx * dx + dz * dz;
                        if (height != BaselineGrid.NO_SURFACE && distance < nearestDistance) {
                            nearestHeight = height;
                            nearestDistance = distance;
                        }
                    }
                }
                if (nearestHeight != BaselineGrid.NO_SURFACE) {
                    grid.terrainY[index] = nearestHeight;
                }
            }
        }
    }

    /** Two native 4-block samples per LOD3 output-pixel axis, integer-mean pooled down. */
    private static boolean sampleEndHeightsMeanPool(
        final BaselineSampler sampler,
        final int tileOriginX,
        final int tileOriginZ,
        final BaselineGrid grid
    ) {
        final int subMin = 2 * P_MIN;
        final int subMax = 2 * P_MAX + 1;
        final int sw = subMax - subMin + 1;
        final int x4Origin = Math.floorDiv(tileOriginX, 4) + subMin;
        final int z4Origin = Math.floorDiv(tileOriginZ, 4) + subMin;

        final int[] raw = new int[sw * sw];
        if (!sampler.endHeights(x4Origin, z4Origin, sw, sw, raw)) {
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
                if (a == 0 && b == 0 && c == 0 && d == 0) {
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

}
