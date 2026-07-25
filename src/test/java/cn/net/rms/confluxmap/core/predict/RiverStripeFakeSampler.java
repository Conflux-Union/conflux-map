package cn.net.rms.confluxmap.core.predict;

import java.util.Arrays;

/**
 * {@link BaselineSampler} stub whose only water is a north-south river running exactly half an
 * output pixel east of every pixel origin, over otherwise uniform sub-sea-level plains.
 *
 * <p>A pixel starts on a multiple of {@code blocksPerPixel}, so one biome lookup per pixel always
 * lands on plains and never on the river. That is the aliasing this fixture exists to pin down:
 * without biome supersampling a river narrower than an output pixel is hit or missed by chance,
 * which is what broke meandering rivers into straight, gappy lines at LOD3-4.
 *
 * <p>Terrain is a flat Y=50 - below {@link BaselineDeriver#WATER_LEVEL}, with no fluid flags of its
 * own - so the water/land split is decided purely by each sample's biome.
 */
final class RiverStripeFakeSampler implements BaselineSampler {
    /** cubiomes ids, matching {@code BiomeTable}'s registration. */
    static final int PLAINS = 1;
    static final int RIVER = 7;
    /** Flat terrain height, deliberately below sea level so a river biome can classify as fluid. */
    static final int TERRAIN_Y = 50;

    private final int blocksPerPixel;

    RiverStripeFakeSampler(final int blocksPerPixel) {
        this.blocksPerPixel = blocksPerPixel;
    }

    @Override
    public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
        return false;
    }

    @Override
    public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        return false;
    }

    @Override
    public boolean overviewHeights(
        final int blockX, final int blockZ, final int w, final int h, final int stride,
        final int[] outTerrainY
    ) {
        Arrays.fill(outTerrainY, 0, w * h, TERRAIN_Y);
        return true;
    }

    @Override
    public boolean surfaceColumns(
        final int blockX, final int blockZ, final int w, final int h, final int stride,
        final int[] outSolidY, final int[] outFluidY, final int[] outSurfaceY, final int[] outFlags
    ) {
        Arrays.fill(outSolidY, 0, w * h, TERRAIN_Y);
        Arrays.fill(outFluidY, 0, w * h, BaselineGrid.NO_FLUID);
        Arrays.fill(outSurfaceY, 0, w * h, TERRAIN_Y);
        Arrays.fill(outFlags, 0, w * h, 0);
        return true;
    }

    @Override
    public boolean surfaceBiomes(
        final int blockX, final int blockZ, final int w, final int h, final int stride,
        final int[] terrainY, final int[] outBiomeIds
    ) {
        for (int zz = 0; zz < h; zz++) {
            for (int xx = 0; xx < w; xx++) {
                final int sampleX = blockX + xx * stride;
                outBiomeIds[zz * w + xx] =
                    Math.floorMod(sampleX, blocksPerPixel) == blocksPerPixel / 2 ? RIVER : PLAINS;
            }
        }
        return true;
    }

    @Override
    public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
        return false;
    }
}
