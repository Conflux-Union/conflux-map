package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Verifies the margin {@link LodSampling} fetches lines up with a neighboring tile's own edge:
 * a west-margin sample (local x = -1) must equal the west-neighbor tile's east edge (local x =
 * 255), and a south-margin sample (local z = 256) must equal the south-neighbor tile's north
 * edge (local z = 0) - see {@code TileService#composeRegion}'s (x-1, z+1) neighbor convention,
 * which {@link BaselineGrid}'s own javadoc explains this margin exists to serve without needing
 * another tile's data at all.
 */
class LodSamplingTest {
    private static final PositionBasedFakeSampler SAMPLER = new PositionBasedFakeSampler();

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void westMarginMatchesWestNeighborsEastEdge(final int lod) {
        final int blocksPerTile = 256 << lod;
        final BaselineGrid here = LodSampling.sample(SAMPLER, false, lod, 0, 0);
        final BaselineGrid west = LodSampling.sample(SAMPLER, false, lod, -blocksPerTile, 0);
        assertNotNull(here);
        assertNotNull(west);

        for (final int z : new int[] {0, 1, 50, 128, 200, 255, 256}) {
            final int hereIdx = BaselineGrid.index(-1, z);
            final int neighborIdx = BaselineGrid.index(255, z);
            assertEquals(west.biomeId[neighborIdx], here.biomeId[hereIdx], "biome mismatch at z=" + z);
            assertEquals(west.terrainY[neighborIdx], here.terrainY[hereIdx], "height mismatch at z=" + z);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void southMarginMatchesSouthNeighborsNorthEdge(final int lod) {
        final int blocksPerTile = 256 << lod;
        final BaselineGrid here = LodSampling.sample(SAMPLER, false, lod, 0, 0);
        final BaselineGrid south = LodSampling.sample(SAMPLER, false, lod, 0, blocksPerTile);
        assertNotNull(here);
        assertNotNull(south);

        for (final int x : new int[] {-1, 0, 1, 50, 128, 200, 255}) {
            final int hereIdx = BaselineGrid.index(x, 256);
            final int neighborIdx = BaselineGrid.index(x, 0);
            assertEquals(south.biomeId[neighborIdx], here.biomeId[hereIdx], "biome mismatch at x=" + x);
            assertEquals(south.terrainY[neighborIdx], here.terrainY[hereIdx], "height mismatch at x=" + x);
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3})
    void endVoidHeightsBecomeTheSentinel(final int lod) {
        final int blocksPerTile = 256 << lod;
        final PositionBasedFakeSampler voidSampler = new PositionBasedFakeSampler(blocksPerTile * 10);
        final BaselineGrid grid = LodSampling.sample(voidSampler, true, lod, 0, blocksPerTile * 20);
        assertNotNull(grid);
        assertEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(0, 0)]);
    }

    @org.junit.jupiter.api.Test
    void lod4UsesCoarseHeightsForWaterClassification() {
        final BaselineGrid grid = LodSampling.sample(SAMPLER, false, 4, 12345, -6789);
        assertNotNull(grid);
        boolean hasSampledHeight = false;
        for (final int y : grid.terrainY) {
            hasSampledHeight |= y != cn.net.rms.confluxmap.core.color.ShadingPipeline.REFERENCE_HEIGHT;
        }
        org.junit.jupiter.api.Assertions.assertTrue(hasSampledHeight);
    }

    @org.junit.jupiter.api.Test
    void lod4KeepsOceanBiomesAsWaterInsteadOfTurningThemIntoLand() {
        final BaselineSampler oceanSampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                java.util.Arrays.fill(out, 0);
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                java.util.Arrays.fill(outY, 40);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };
        final BaselineGrid grid = LodSampling.sample(oceanSampler, false, 4, 0, 0);
        assertNotNull(grid);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        assertEquals(SurfaceKind.WATER.ordinal(), derived.kind[BaselineGrid.index(0, 0)]);
    }

    @org.junit.jupiter.api.Test
    void lod4HeightSamplesAdvanceBySixteenBlocksPerPixel() {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                java.util.Arrays.fill(out, 1);
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        outY[zz * w + xx] = x4 + xx;
                    }
                }
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };
        final BaselineGrid grid = LodSampling.sample(sampler, false, 4, -4096, -4096);
        assertNotNull(grid);
        final int first = grid.terrainY[BaselineGrid.index(0, 0)];
        final int second = grid.terrainY[BaselineGrid.index(1, 0)];
        assertEquals(4, second - first, "LOD4 pixels are 16 blocks apart, i.e. four native 1:4 height cells");
    }

    @org.junit.jupiter.api.Test
    void lod1HeightsAreBilinearlyInterpolatedNotTwoPixelStepped() {
        // LOD1 covers 2 blocks/pixel but heights come from 4-block native samples. Bilinear
        // interpolation must give adjacent pixels different values inside the same native cell,
        // rather than the old nearest x2 expand where two pixels snapped to one sample - this is
        // what closes the resolution gap to the real LOD1 tile (a downsample of full-res LOD0).
        final BaselineGrid grid = LodSampling.sample(SAMPLER, false, 1, 0, 0);
        assertNotNull(grid);
        final int h0 = grid.terrainY[BaselineGrid.index(0, 0)];
        final int h1 = grid.terrainY[BaselineGrid.index(1, 0)];
        assertNotEquals(h0, h1, "adjacent LOD1 pixels should bilinearly differ within one native cell");
    }

    @org.junit.jupiter.api.Test
    void highLodBiomesUseOneFinalLayerSamplePerOutputPixel() {
        final int[] lastScale = {-1};
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                lastScale[0] = scale;
                final int[] ids = {1, 4, 35};
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        out[zz * w + xx] = ids[Math.floorMod(x + xx, ids.length)];
                    }
                }
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                java.util.Arrays.fill(outY, 70);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        final BaselineGrid lod1 = LodSampling.sample(sampler, false, 1, 0, 0);
        assertNotNull(lod1);
        assertEquals(1, lastScale[0], "LOD1 must sample the final 1:1 biome layer instead of expanding 1:4 cells");
        assertNotEquals(lod1.biomeId[BaselineGrid.index(0, 0)], lod1.biomeId[BaselineGrid.index(1, 0)]);

        final BaselineGrid lod3 = LodSampling.sample(sampler, false, 3, 0, 0);
        assertNotNull(lod3);
        assertEquals(4, lastScale[0]);
        assertNotEquals(lod3.biomeId[BaselineGrid.index(0, 0)], lod3.biomeId[BaselineGrid.index(1, 0)]);

        final BaselineGrid lod4 = LodSampling.sample(sampler, false, 4, 0, 0);
        assertNotNull(lod4);
        assertEquals(4, lastScale[0], "LOD4 must keep the final 1:4 biome layer instead of cubiomes' coarse scale-16 layer");
        assertNotEquals(lod4.biomeId[BaselineGrid.index(0, 0)], lod4.biomeId[BaselineGrid.index(1, 0)]);
    }
}
