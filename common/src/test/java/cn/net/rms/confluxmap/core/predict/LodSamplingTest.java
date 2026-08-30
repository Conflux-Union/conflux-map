package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.util.TileViewport;
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
    void netherRoofSamplesBiomesOnAFixedCeilingWithoutTerrainQueries(final int lod) {
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h,
                final int[] out
            ) {
                java.util.Arrays.fill(out, 171);
                return true;
            }

            @Override
            public boolean heights(
                final int x4, final int z4, final int w, final int h, final int[] outY
            ) {
                throw new AssertionError("Nether roof prediction must not query Overworld heights");
            }

            @Override
            public boolean endHeights(
                final int x4, final int z4, final int w, final int h, final int[] outY
            ) {
                throw new AssertionError("Nether roof prediction must not query End heights");
            }
        };

        final BaselineGrid grid = LodSampling.sampleNetherRoof(sampler, lod, -8192, 4096);

        assertNotNull(grid);
        final int center = BaselineGrid.index(128, 128);
        assertEquals(171, grid.biomeId[center]);
        assertEquals(PredictionDimensions.NETHER_ROOF_Y, grid.terrainY[center]);
        assertEquals(PredictionDimensions.NETHER_ROOF_Y, grid.baseSurfaceY[center]);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        assertEquals(SurfaceKind.BEDROCK_CEILING.ordinal(), derived.kind[center]);
        assertEquals(PredictionDimensions.NETHER_ROOF_Y, derived.surfaceY[center]);
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void netherBiomePlaneSamplesTheRequestedHeight(final int lod) {
        final int[] sampledY = {Integer.MIN_VALUE};
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h,
                final int[] out
            ) {
                throw new AssertionError("a height-aware Nether plane must not use the roof sampler");
            }

            @Override
            public boolean biomesAtYStrided(
                final int blockY,
                final int scale,
                final int x,
                final int z,
                final int w,
                final int h,
                final int stride,
                final int[] out
            ) {
                sampledY[0] = blockY;
                java.util.Arrays.fill(out, 173);
                return true;
            }

            @Override
            public boolean heights(
                final int x4, final int z4, final int w, final int h, final int[] outY
            ) {
                throw new AssertionError("Nether biome prediction must not query Overworld heights");
            }

            @Override
            public boolean endHeights(
                final int x4, final int z4, final int w, final int h, final int[] outY
            ) {
                throw new AssertionError("Nether biome prediction must not query End heights");
            }
        };

        final BaselineGrid grid = LodSampling.sampleNetherBiomesAtY(
            sampler, lod, -8192, 4096, 37
        );

        assertNotNull(grid);
        assertEquals(37, sampledY[0]);
        final int center = BaselineGrid.index(128, 128);
        assertEquals(173, grid.biomeId[center]);
        assertEquals(37, grid.terrainY[center]);
        assertEquals(37, grid.baseSurfaceY[center]);
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void netherRoofWindowMatchesTheFullBaseline(final int lod) {
        final int minX = 29;
        final int minZ = 83;
        final int maxX = 41;
        final int maxZ = 97;
        final int originX = -32_768;
        final int originZ = 16_384;
        final BaselineGrid full = LodSampling.sampleNetherRoof(
            SAMPLER, lod, originX, originZ
        );
        final BaselineGrid window = LodSampling.sampleNetherRoofWindow(
            SAMPLER, lod, originX, originZ, minX, minZ, maxX, maxZ
        );

        assertNotNull(full);
        assertNotNull(window);
        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                final int index = BaselineGrid.index(x, z);
                assertEquals(full.biomeId[index], window.biomeId[index]);
                assertEquals(full.terrainY[index], window.terrainY[index]);
                assertEquals(full.baseSurfaceY[index], window.baseSurfaceY[index]);
            }
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 3, 4})
    void coarseWindowMatchesTheFullTileAtEveryRequestedPixel(final int lod) {
        final int minX = 113;
        final int minZ = 47;
        final int maxX = 119;
        final int maxZ = 53;
        final int originX = -16_384;
        final int originZ = 8_192;
        final long seed = 91L;
        final BaselineGrid full = LodSampling.sample(
            SAMPLER, false, lod, originX, originZ
        );
        final BaselineGrid window = LodSampling.sampleOverworldWindow(
            SAMPLER, lod, originX, originZ, minX, minZ, maxX, maxZ
        );
        assertNotNull(full);
        assertNotNull(window);
        final DerivedGrid fullDerived = BaselineDeriver.derive(full);
        CanopyStylizer.apply(fullDerived, full, seed, lod, originX, originZ);
        final DerivedGrid windowDerived = BaselineDeriver.deriveWindow(
            window, minX, minZ, maxX, maxZ
        );
        CanopyStylizer.applyWindow(
            windowDerived, window, seed, lod, originX, originZ,
            minX, minZ, maxX, maxZ
        );

        for (int z = minZ; z <= maxZ; z++) {
            for (int x = minX; x <= maxX; x++) {
                final int pixel = BaselineGrid.index(x, z);
                assertEquals(full.terrainY[pixel], window.terrainY[pixel]);
                assertEquals(full.biomeId[pixel], window.biomeId[pixel]);
                assertEquals(full.baseSurfaceY[pixel], window.baseSurfaceY[pixel]);
                assertEquals(full.surfaceFlags[pixel], window.surfaceFlags[pixel]);
                assertEquals(fullDerived.surfaceY[pixel], windowDerived.surfaceY[pixel]);
                assertEquals(fullDerived.kind[pixel], windowDerived.kind[pixel]);
                assertEquals(fullDerived.fluidDepth[pixel], windowDerived.fluidDepth[pixel]);
                if (full.supersampled()) {
                    for (int subZ = 0; subZ < full.subPerAxis; subZ++) {
                        for (int subX = 0; subX < full.subPerAxis; subX++) {
                            final int sub = full.subIndex(pixel, subX, subZ);
                            assertEquals(full.subBiomeId[sub], window.subBiomeId[sub]);
                            assertEquals(fullDerived.subSurfaceY[sub], windowDerived.subSurfaceY[sub]);
                            assertEquals(fullDerived.subKind[sub], windowDerived.subKind[sub]);
                        }
                    }
                }
            }
        }
    }

    @org.junit.jupiter.api.Test
    void lodFourOnePixelWindowDoesNotSampleTheRestOfTheTile() {
        final int[] overviewCells = {0};
        final int[] surfaceBiomeCells = {0};
        final BaselineSampler countingSampler = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale, final int x, final int z, final int w, final int h,
                final int[] out
            ) {
                java.util.Arrays.fill(out, RiverStripeFakeSampler.PLAINS);
                return true;
            }

            @Override
            public boolean heights(
                final int x4, final int z4, final int w, final int h, final int[] outY
            ) {
                return false;
            }

            @Override
            public boolean overviewHeights(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] outTerrainY
            ) {
                overviewCells[0] += w * h;
                java.util.Arrays.fill(outTerrainY, 70);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] terrainY, final int[] outBiomeIds
            ) {
                surfaceBiomeCells[0] += w * h;
                java.util.Arrays.fill(outBiomeIds, RiverStripeFakeSampler.PLAINS);
                return true;
            }

            @Override
            public boolean endHeights(
                final int x4, final int z4, final int w, final int h, final int[] outY
            ) {
                return false;
            }
        };

        final BaselineGrid grid = LodSampling.sampleOverworldWindow(
            countingSampler, 4, 0, 0, 37, 61, 37, 61
        );

        assertNotNull(grid);
        assertEquals(1, overviewCells[0], "only the requested output pixel may run terrain prediction");
        assertEquals(
            5, surfaceBiomeCells[0],
            "LOD4 samples one center biome plus the requested pixel's four sub-samples"
        );
    }

    @org.junit.jupiter.api.Test
    void reportedFullscreenZoomsPutTheMostExactWorkIntoTheClosestView() {
        final int screenWidth = 1920;
        final int screenHeight = 1080;
        long closestWork = 0L;
        long scaleTwoWork = 0L;
        long mostWork = 0L;
        double mostExpensiveScale = Double.NaN;
        for (final double scale : new double[] {16.0, 6.0, 3.0, 2.0, 1.0}) {
            final int lod = TileMath.lodForScale(scale);
            final TileViewport viewport = TileViewport.covering(
                0.0, 0.0, screenWidth, screenHeight, scale, lod
            );
            final long work = (long) viewport.tileCount() * sampledSurfaceCells(lod);
            if (work > mostWork) {
                mostWork = work;
                mostExpensiveScale = scale;
            }
            if (scale == 2.0) {
                scaleTwoWork = work;
            } else if (scale == 1.0) {
                closestWork = work;
            }
        }

        assertEquals(1.0, mostExpensiveScale, "the closest view should receive the highest precision budget");
        assertTrue(scaleTwoWork < closestWork, "scale 2 must not recreate the reported work cliff");
        assertTrue(mostWork < 100_000L, "sparse exact work must stay bounded across a full HD viewport");
    }

    @org.junit.jupiter.api.Test
    void exactCorrectionRunsOnlyWhereItStillBuysTerrainDetail() {
        assertEquals(34 * 34, sampledSurfaceCells(0), "LOD0 keeps the densest sparse residual grid");
        assertEquals(18 * 18, sampledSurfaceCells(1), "LOD1 uses the bounded overview correction budget");
        // From LOD2 up the anchors would sit 64+ blocks apart, where measurement put their
        // contribution to terrain gradient correlation at +0.001 for roughly a third of a second
        // per tile. The overview terrain model covers that range on its own now.
        for (final int lod : new int[] {2, 3, 4}) {
            assertEquals(
                0, sampledSurfaceCells(lod),
                "LOD" + lod + " must not pay for an exact residual grid it cannot use"
            );
        }
    }

    @org.junit.jupiter.api.Test
    void onlyLod0ClassifiesFluidFromTheExactAnchorLattice() {
        assertEquals(8, sampledSurfaceStride(0), "LOD0 keeps the generator's own aquifer answer");
        // A binary fluid flag sampled every 16 output pixels, interpolated and thresholded, put
        // scattered inland water on the lattice instead of where it is (measured IoU 0.060 at
        // LOD4 over swamp). Coarser LODs decide per pixel from the terrain height instead.
        for (final int lod : new int[] {2, 3, 4}) {
            assertEquals(
                0, sampledSurfaceCells(lod),
                "LOD" + lod + " must not depend on anchor fluid flags"
            );
        }
    }

    /**
     * Terrain a single block under sea level is land unless its biome says otherwise, so the
     * threshold cannot simply be "below {@link BaselineDeriver#WATER_LEVEL}" - that over-detects,
     * flooding dry ground wherever the overview height estimate runs low.
     */
    @ParameterizedTest
    @ValueSource(ints = {1, 2, 3, 4})
    void coarseLodWaterNeedsTerrainStrictlyUnderTheFluidCeiling(final int lod) {
        final BaselineGrid justUnderSeaLevel = LodSampling.sample(
            flatTerrainSampler(BaselineDeriver.WATER_LEVEL - 1), false, lod, 0, 0
        );
        assertNotNull(justUnderSeaLevel);
        assertEquals(
            0, justUnderSeaLevel.surfaceFlags[BaselineGrid.index(0, 0)],
            "one block under sea level must stay land on a non-water biome"
        );

        final BaselineGrid clearlyUnder = LodSampling.sample(
            flatTerrainSampler(BaselineDeriver.WATER_LEVEL - 2), false, lod, 0, 0
        );
        assertNotNull(clearlyUnder);
        assertEquals(
            BaselineGrid.SURFACE_FLUID, clearlyUnder.surfaceFlags[BaselineGrid.index(0, 0)],
            "terrain below the fluid ceiling must resolve to water without any anchor telling it so"
        );
    }

    /** Flat terrain at one height, a non-water biome, and no fluid flags of its own. */
    private static BaselineSampler flatTerrainSampler(final int terrainY) {
        return new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                java.util.Arrays.fill(out, RiverStripeFakeSampler.PLAINS);
                return true;
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
                java.util.Arrays.fill(outTerrainY, 0, w * h, terrainY);
                return true;
            }

            @Override
            public boolean surfaceColumns(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] outSolidY, final int[] outFluidY, final int[] outSurfaceY, final int[] outFlags
            ) {
                java.util.Arrays.fill(outSolidY, 0, w * h, terrainY);
                java.util.Arrays.fill(outFluidY, 0, w * h, BaselineGrid.NO_FLUID);
                java.util.Arrays.fill(outSurfaceY, 0, w * h, terrainY);
                java.util.Arrays.fill(outFlags, 0, w * h, 0);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX, final int blockZ, final int w, final int h, final int stride,
                final int[] terrain, final int[] outBiomeIds
            ) {
                java.util.Arrays.fill(outBiomeIds, 0, w * h, RiverStripeFakeSampler.PLAINS);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void diagonalSwampShoreDoesNotBecomeNearestAnchorRectangles(final int lod) {
        final int blocksPerPixel = 1 << lod;
        final int waterBoundary = (BaselineGrid.PIXELS - 1) * blocksPerPixel;
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale,
                final int x,
                final int z,
                final int w,
                final int h,
                final int[] out
            ) {
                java.util.Arrays.fill(out, 6);
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean overviewHeights(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outTerrainY
            ) {
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        outTerrainY[zz * w + xx] = terrainAt(
                            blockX + xx * stride, blockZ + zz * stride
                        );
                    }
                }
                return true;
            }

            @Override
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        final int index = zz * w + xx;
                        final int solid = terrainAt(blockX + xx * stride, blockZ + zz * stride);
                        final boolean water = solid < BaselineDeriver.WATER_LEVEL;
                        outSolidY[index] = solid;
                        outFluidY[index] = water ? BaselineDeriver.WATER_LEVEL : BaselineGrid.NO_FLUID;
                        outSurfaceY[index] = water ? BaselineDeriver.WATER_LEVEL : solid;
                        outFlags[index] = water ? BaselineGrid.SURFACE_FLUID : 0;
                    }
                }
                return true;
            }

            /** Sub-sea-level basin on one side of a diagonal, dry ground on the other. */
            private int terrainAt(final int bx, final int bz) {
                return bx + bz <= waterBoundary ? 55 : 70;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] terrainY,
                final int[] outBiomeIds
            ) {
                java.util.Arrays.fill(outBiomeIds, 6);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, 0, 0);
        assertNotNull(grid);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        int movingRows = 0;
        int previousBoundary = Integer.MIN_VALUE;
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            int lastWaterX = -1;
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final boolean actualWater = derived.kind[BaselineGrid.index(x, z)] == SurfaceKind.WATER.ordinal();
                if (actualWater) {
                    lastWaterX = x;
                }
            }
            movingRows += z > 0 && lastWaterX != previousBoundary ? 1 : 0;
            previousBoundary = lastWaterX;
        }
        assertTrue(
            movingRows >= 190,
            "LOD" + lod + " diagonal water boundary moved on only " + movingRows
                + " rows; nearest-anchor rectangles move at most once per correction cell"
        );
    }

    private static int sampledSurfaceCells(final int lod) {
        return sampledSurface(lod).cells();
    }

    private static int sampledSurfaceStride(final int lod) {
        return sampledSurface(lod).stride();
    }

    private static SampledSurface sampledSurface(final int lod) {
        final int[] cells = {0};
        final int[] sampledStride = {0};
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                java.util.Arrays.fill(out, 1);
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                cells[0] += w * h;
                sampledStride[0] = stride;
                java.util.Arrays.fill(outSolidY, 70);
                java.util.Arrays.fill(outFluidY, BaselineGrid.NO_FLUID);
                java.util.Arrays.fill(outSurfaceY, 70);
                java.util.Arrays.fill(outFlags, 0);
                return true;
            }

            @Override
            public boolean overviewHeights(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outTerrainY
            ) {
                java.util.Arrays.fill(outTerrainY, 70);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };
        assertNotNull(LodSampling.sample(sampler, false, lod, 0, 0));
        return new SampledSurface(cells[0], sampledStride[0]);
    }

    private record SampledSurface(int cells, int stride) {
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void coarseLodsKeepOverviewDetailAtEveryOutputPixel(final int lod) {
        final int outputStride = 1 << lod;
        final boolean[] sampledFullOverview = {false};
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                java.util.Arrays.fill(out, 1);
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean overviewHeights(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outTerrainY
            ) {
                sampledFullOverview[0] |= w == BaselineGrid.SIZE
                    && h == BaselineGrid.SIZE
                    && stride == outputStride;
                fillAlternatingHeights(blockX, blockZ, w, h, stride, outputStride, outTerrainY);
                return true;
            }

            @Override
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                fillAlternatingHeights(blockX, blockZ, w, h, stride, outputStride, outSolidY);
                System.arraycopy(outSolidY, 0, outSurfaceY, 0, w * h);
                java.util.Arrays.fill(outFluidY, BaselineGrid.NO_FLUID);
                java.util.Arrays.fill(outFlags, 0);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] terrainY,
                final int[] outBiomeIds
            ) {
                java.util.Arrays.fill(outBiomeIds, 1);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, 0, 0);
        assertNotNull(grid);
        assertTrue(sampledFullOverview[0], "the overview must be sampled at every output position");
        int transitions = 0;
        for (int x = 1; x < BaselineGrid.PIXELS; x++) {
            transitions += grid.terrainY[BaselineGrid.index(x, 100)]
                != grid.terrainY[BaselineGrid.index(x - 1, 100)] ? 1 : 0;
        }
        assertEquals(255, transitions, "LOD" + lod + " must not smear overview detail across multiple texels");
    }

    private static void fillAlternatingHeights(
        final int blockX,
        final int blockZ,
        final int w,
        final int h,
        final int stride,
        final int outputStride,
        final int[] out
    ) {
        for (int z = 0; z < h; z++) {
            for (int x = 0; x < w; x++) {
                final int sampleX = blockX + x * stride;
                final int sampleZ = blockZ + z * stride;
                out[z * w + x] = 80
                    + (Math.floorDiv(sampleX, outputStride) & 1)
                    + (Math.floorDiv(sampleZ, outputStride) & 1);
            }
        }
    }

    @org.junit.jupiter.api.Test
    void reusableSurfaceBiomesSkipTheDuplicateBiomeGenerationPath() {
        final boolean[] fallbackBiomesCalled = {false};
        final boolean[] surfaceBiomesCalled = {false};
        final BaselineSampler sampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                fallbackBiomesCalled[0] = true;
                return false;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }

            @Override
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                java.util.Arrays.fill(outSolidY, 80);
                java.util.Arrays.fill(outFluidY, BaselineGrid.NO_FLUID);
                java.util.Arrays.fill(outSurfaceY, 80);
                java.util.Arrays.fill(outFlags, 0);
                return true;
            }

            @Override
            public boolean surfaceBiomes(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] terrainY,
                final int[] outBiomeIds
            ) {
                surfaceBiomesCalled[0] = true;
                java.util.Arrays.fill(outBiomeIds, 35);
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        final BaselineGrid grid = LodSampling.sample(sampler, false, 0, 0, 0);
        assertNotNull(grid);
        assertTrue(surfaceBiomesCalled[0]);
        org.junit.jupiter.api.Assertions.assertFalse(fallbackBiomesCalled[0]);
        assertEquals(35, grid.biomeId[BaselineGrid.index(0, 0)]);
    }

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
    void lod4EndSamplingDoesNotSpreadLandBeyondItsSamplingCell() {
        final BaselineSampler isolatedLand = new BaselineSampler() {
            @Override
            public boolean biomes(
                final int scale,
                final int x,
                final int z,
                final int w,
                final int h,
                final int[] out
            ) {
                java.util.Arrays.fill(out, CubiomesBiomeIds.SMALL_END_ISLANDS);
                return true;
            }

            @Override
            public boolean heights(
                final int x4,
                final int z4,
                final int w,
                final int h,
                final int[] outY
            ) {
                return false;
            }

            @Override
            public boolean endHeights(
                final int x4,
                final int z4,
                final int w,
                final int h,
                final int[] outY
            ) {
                for (int dz = 0; dz < h; dz++) {
                    for (int dx = 0; dx < w; dx++) {
                        outY[dz * w + dx] = x4 + dx == 0 && z4 + dz == 0 ? 64 : 0;
                    }
                }
                return true;
            }
        };

        final BaselineGrid grid = LodSampling.sample(isolatedLand, true, 4, 0, 0);
        assertNotNull(grid);
        assertNotEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(0, 0)]);
        assertNotEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(1, 0)]);
        assertNotEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(0, 1)]);
        assertEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(2, 0)]);
        assertEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(0, 2)]);
        assertEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(-1, 0)]);
        assertEquals(BaselineGrid.NO_SURFACE, grid.terrainY[BaselineGrid.index(0, -1)]);
    }

    @org.junit.jupiter.api.Test
    void lod4EndRefinementMatchesTheSouthNeighborsNorthEdge() {
        final int blocksPerTile = 256 << 4;
        final PositionBasedFakeSampler edgeSampler = new PositionBasedFakeSampler(blocksPerTile);
        final BaselineGrid here = LodSampling.sample(edgeSampler, true, 4, 0, 0);
        final BaselineGrid south = LodSampling.sample(
            edgeSampler, true, 4, 0, blocksPerTile
        );
        assertNotNull(here);
        assertNotNull(south);

        for (int x = 0; x < BaselineGrid.PIXELS; x++) {
            assertEquals(
                here.terrainY[BaselineGrid.index(x, 256)],
                south.terrainY[BaselineGrid.index(x, 0)],
                "height mismatch at x=" + x
            );
            assertEquals(
                here.biomeId[BaselineGrid.index(x, 256)],
                south.biomeId[BaselineGrid.index(x, 0)],
                "biome mismatch at x=" + x
            );
        }
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
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                java.util.Arrays.fill(outSolidY, 40);
                java.util.Arrays.fill(outFluidY, BaselineDeriver.WATER_LEVEL);
                java.util.Arrays.fill(outSurfaceY, BaselineDeriver.WATER_LEVEL);
                java.util.Arrays.fill(outFlags, BaselineGrid.SURFACE_FLUID);
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
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        final int index = zz * w + xx;
                        final int solid = Math.floorDiv(blockX + xx * stride, 4);
                        outSolidY[index] = solid;
                        outFluidY[index] = BaselineGrid.NO_FLUID;
                        outSurfaceY[index] = solid;
                        outFlags[index] = 0;
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

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 3, 4})
    void overworldSamplingUsesResolvedSurfaceColumnsInsteadOfReconstructingTheWaterline(final int lod) {
        final int coastBlockX = 4 << lod;
        final boolean[] surfaceColumnsCalled = {false};
        final BaselineSampler coastSampler = new BaselineSampler() {
            @Override
            public boolean biomes(final int scale, final int x, final int z, final int w, final int h, final int[] out) {
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        out[zz * w + xx] = (x + xx) * scale < coastBlockX ? 0 : 1;
                    }
                }
                return true;
            }

            @Override
            public boolean heights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                java.util.Arrays.fill(outY, 100);
                return true;
            }

            @Override
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                surfaceColumnsCalled[0] = true;
                for (int zz = 0; zz < h; zz++) {
                    for (int xx = 0; xx < w; xx++) {
                        final int index = zz * w + xx;
                        if (blockX + xx * stride < coastBlockX) {
                            outSolidY[index] = 40;
                            outFluidY[index] = BaselineDeriver.WATER_LEVEL;
                            outSurfaceY[index] = BaselineDeriver.WATER_LEVEL;
                            outFlags[index] = BaselineGrid.SURFACE_FLUID;
                        } else {
                            outSolidY[index] = 100;
                            outFluidY[index] = BaselineGrid.NO_FLUID;
                            outSurfaceY[index] = 100;
                            outFlags[index] = 0;
                        }
                    }
                }
                return true;
            }

            @Override
            public boolean endHeights(final int x4, final int z4, final int w, final int h, final int[] outY) {
                return false;
            }
        };

        final BaselineGrid grid = LodSampling.sample(coastSampler, false, lod, 0, 0);
        assertNotNull(grid);
        org.junit.jupiter.api.Assertions.assertTrue(
            surfaceColumnsCalled[0],
            "Overworld sampling must consume cubiomes-resolved base columns"
        );
        final int oceanEdge = BaselineGrid.index(3, 0);
        assertEquals(0, grid.biomeId[oceanEdge]);
        assertTrue(
            grid.terrainY[oceanEdge] < BaselineDeriver.WATER_LEVEL,
            "sparse exact floor interpolation must keep the ocean column below its water surface"
        );
        assertEquals(BaselineDeriver.WATER_LEVEL, grid.fluidY[oceanEdge]);
        assertEquals(BaselineDeriver.WATER_LEVEL, grid.baseSurfaceY[oceanEdge]);
        assertEquals(BaselineGrid.SURFACE_FLUID, grid.surfaceFlags[oceanEdge]);

        final DerivedGrid derived = BaselineDeriver.derive(grid);
        assertEquals(
            SurfaceKind.WATER.ordinal(), derived.kind[oceanEdge],
            "a final-layer ocean pixel must remain water when aggregated height samples cross the shoreline"
        );
        assertEquals(BaselineDeriver.WATER_LEVEL, derived.surfaceY[oceanEdge]);
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
            public boolean surfaceColumns(
                final int blockX,
                final int blockZ,
                final int w,
                final int h,
                final int stride,
                final int[] outSolidY,
                final int[] outFluidY,
                final int[] outSurfaceY,
                final int[] outFlags
            ) {
                java.util.Arrays.fill(outSolidY, 70);
                java.util.Arrays.fill(outFluidY, BaselineGrid.NO_FLUID);
                java.util.Arrays.fill(outSurfaceY, 70);
                java.util.Arrays.fill(outFlags, 0);
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

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void fineLodsSampleOneBiomePerPixelBecauseTheyAlreadyReachTheNativeGrid(final int lod) {
        final BaselineGrid grid = LodSampling.sample(SAMPLER, false, lod, 0, 0);
        assertNotNull(grid);
        assertEquals(BaselineGrid.NO_SUPERSAMPLING, grid.subPerAxis);
        org.junit.jupiter.api.Assertions.assertFalse(grid.supersampled());
    }

    @ParameterizedTest
    @ValueSource(ints = {3, 4})
    void coarseLodsSupersampleBiomesSoThinFeaturesSurviveBetweenPixelCentres(final int lod) {
        final BaselineGrid grid = LodSampling.sample(new RiverStripeFakeSampler(1 << lod), false, lod, 0, 0);
        assertNotNull(grid);
        assertEquals(2, grid.subPerAxis);
        assertEquals(4, grid.subCount());

        final int idx = BaselineGrid.index(0, 0);
        assertEquals(RiverStripeFakeSampler.PLAINS, grid.biomeId[idx], "the pixel centre must still miss the river");
        assertEquals(0, grid.surfaceFlags[idx], "so the per-pixel classification stays dry land");

        // The sub-sample half a pixel east lands on the stripe and reclassifies as water.
        assertEquals(RiverStripeFakeSampler.RIVER, grid.subBiomeId[grid.subIndex(idx, 1, 0)]);
        assertEquals(
            BaselineGrid.SURFACE_FLUID, grid.subSurfaceFlags[grid.subIndex(idx, 1, 0)],
            "a river sub-sample below sea level must resolve to fluid even though its pixel did not"
        );
        assertEquals(RiverStripeFakeSampler.PLAINS, grid.subBiomeId[grid.subIndex(idx, 0, 0)]);
        assertEquals(0, grid.subSurfaceFlags[grid.subIndex(idx, 0, 0)]);
    }

    @org.junit.jupiter.api.Test
    void endTilesAreNeverSupersampledBecauseTheirBiomesDoNotVaryWithinAPixel() {
        final BaselineGrid grid = LodSampling.sample(SAMPLER, true, 4, 0, 0);
        assertNotNull(grid);
        assertEquals(BaselineGrid.NO_SUPERSAMPLING, grid.subPerAxis);
    }

    @org.junit.jupiter.api.Test
    void samplersWithoutSurfaceBiomeSupportFallBackToThePixelBiomeInEverySubSample() {
        // PositionBasedFakeSampler leaves BaselineSampler#surfaceBiomes at its false default, so
        // the supersampled fetch cannot run and must degrade to the pre-supersampling behaviour
        // rather than failing the tile.
        final BaselineGrid grid = LodSampling.sample(SAMPLER, false, 4, 0, 0);
        assertNotNull(grid);
        assertTrue(grid.supersampled());
        for (int z = 0; z < 4; z++) {
            for (int x = 0; x < 4; x++) {
                final int idx = BaselineGrid.index(x, z);
                for (int sz = 0; sz < 2; sz++) {
                    for (int sx = 0; sx < 2; sx++) {
                        assertEquals(
                            grid.biomeId[idx], grid.subBiomeId[grid.subIndex(idx, sx, sz)],
                            "sub-sample (" + sx + "," + sz + ") of pixel (" + x + "," + z + ")"
                        );
                    }
                }
            }
        }
    }
}
