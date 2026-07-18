package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
            if (lod < 4) {
                assertEquals(west.terrainY[neighborIdx], here.terrainY[hereIdx], "height mismatch at z=" + z);
            }
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
            if (lod < 4) {
                assertEquals(south.terrainY[neighborIdx], here.terrainY[hereIdx], "height mismatch at x=" + x);
            }
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
}
