package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.model.SurfaceKind;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.util.OptionalInt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real JNI-backed prediction pipeline end to end (sampling through {@link
 * NativeBaselineSampler}, exactly as {@code PredictionTileService} does), the same {@code
 * Assumptions.assumeTrue(NativeLib.initForTests())} gate {@code
 * nativepredict.CubiomesNativeTest} uses so a machine without a working native build for its
 * platform skips these instead of failing the whole module.
 */
class PredictionNativeIntegrationTest {
    /** Same fixed seed {@code nativepredict.CubiomesNativeTest} uses. */
    private static final long SEED = 146008555L;

    @BeforeEach
    void requireNative() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
    }

    private static int mc17() {
        final OptionalInt mc = McVersions.toCubiomes("1.17");
        assertTrue(mc.isPresent(), "McVersions must know \"1.17\"");
        return mc.getAsInt();
    }

    private static int mc21() {
        final OptionalInt mc = McVersions.toCubiomes("1.21.1");
        assertTrue(mc.isPresent(), "McVersions must know \"1.21.1\"");
        return mc.getAsInt();
    }

    private static int[] composeTile(final int mcVersion, final int dim, final boolean end, final int lod, final int tileOriginX, final int tileOriginZ) {
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mcVersion, SEED, dim, 0);
        final BaselineGrid grid = LodSampling.sample(sampler, end, lod, tileOriginX, tileOriginZ);
        assertNotNull(grid, "native sampling must succeed for a valid version/seed");
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, SEED, lod, tileOriginX, tileOriginZ);
        return PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());
    }

    @Test
    void overworldLod2TileComposeIsDeterministicAcrossRunsAndThreads() throws InterruptedException {
        final int mcVersion = mc17();

        final int[] first = composeTile(mcVersion, PredictionDimensions.OVERWORLD, false, 2, 0, 0);
        final int[] second = composeTile(mcVersion, PredictionDimensions.OVERWORLD, false, 2, 0, 0);
        assertArrayEquals(first, second, "two composes on the same thread must be bit-identical");

        final int[][] fromThread = new int[1][];
        final Throwable[] threadFailure = new Throwable[1];
        final Thread worker = new Thread(() -> {
            try {
                fromThread[0] = composeTile(mcVersion, PredictionDimensions.OVERWORLD, false, 2, 0, 0);
            } catch (final Throwable t) {
                threadFailure[0] = t;
            }
        });
        worker.start();
        worker.join();
        assertNotNull(fromThread[0], "worker thread must have produced a result: " + threadFailure[0]);
        assertArrayEquals(first, fromThread[0], "a second thread's own context must agree exactly");
    }

    @Test
    void endTileFarFromTheMainIslandHasVoidTransparentPixels() {
        final int mcVersion = mc17();
        // Far enough from the End's main island (a few hundred blocks around the origin) that the
        // vast inter-island void dominates a 1024-block-wide LOD2 tile.
        final int[] pixels = composeTile(mcVersion, PredictionDimensions.END, true, 2, 20_000, 20_000);
        boolean anyTransparent = false;
        for (final int argb : pixels) {
            if (Argb.alpha(argb) == 0) {
                anyTransparent = true;
                break;
            }
        }
        assertTrue(anyTransparent, "expected at least one void/transparent pixel far from the End's main island");
    }

    @Test
    void reportedOceanCoordinatesKeepTheirVanillaWaterArea() {
        final long reportedSeed = 6512112982729996127L;
        final int blockX = -2819;
        final int blockZ = -96;
        final int lod = 4;
        final int originX = TileMath.blockToTile(blockX, lod) * TileMath.blocksPerTile(lod);
        final int originZ = TileMath.blockToTile(blockZ, lod) * TileMath.blocksPerTile(lod);
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc17(), reportedSeed, PredictionDimensions.OVERWORLD, 0);
        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, originX, originZ);
        assertNotNull(grid);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        final int pixelX = TileMath.blockToPixelInTile(blockX, lod);
        final int pixelZ = TileMath.blockToPixelInTile(blockZ, lod);
        assertEquals(
            SurfaceKind.WATER,
            SurfaceKind.byOrdinal(derived.kind[BaselineGrid.index(pixelX, pixelZ)]),
            "the reported ocean coordinate must not turn into green land at the LOD4 threshold"
        );

        final NativeBaselineSampler modernSampler = new NativeBaselineSampler(
            mc21(),
            0L,
            PredictionDimensions.OVERWORLD,
            0
        );
        final BaselineGrid modernGrid = LodSampling.sample(
            modernSampler,
            false,
            0,
            49 * BaselineGrid.PIXELS,
            -52 * BaselineGrid.PIXELS
        );
        assertNotNull(modernGrid);
        final DerivedGrid modernDerived = BaselineDeriver.derive(modernGrid);
        CanopyStylizer.apply(
            modernDerived,
            modernGrid,
            0L,
            0,
            49 * BaselineGrid.PIXELS,
            -52 * BaselineGrid.PIXELS
        );
        int waterPixels = 0;
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final byte kind = modernDerived.kind[BaselineGrid.index(x, z)];
                waterPixels += SurfaceKind.byOrdinal(kind) == SurfaceKind.WATER ? 1 : 0;
            }
        }
        assertTrue(
            Math.abs(waterPixels - 14_210) <= 1_000,
            "seed-0 tile (49,-52) water area drifted from Vanilla: " + waterPixels
        );
    }

    // The broken scale-one row sampler produced 217 and 106 wide scanline transitions here;
    // clean terrain still has some from long coastlines, so each fixture keeps its own bound.

    @Test
    void reportedLod1CoordinateDoesNotProduceHorizontalStripeCorruption() {
        final int[] pixels = composeReportedTile(1, -633, -477);
        final int wideTransitions = wideHorizontalTransitions(pixels);
        assertTrue(
            wideTransitions < 100,
            "found " + wideTransitions + " scanline boundaries changing over half the tile width"
        );
    }

    @Test
    void reportedLod0CoordinateDoesNotProduceHorizontalStripeCorruption() {
        final int[] pixels = composeReportedTile(0, -822, -430);
        final int wideTransitions = wideHorizontalTransitions(pixels);
        assertTrue(
            wideTransitions < 64,
            "found " + wideTransitions + " scanline boundaries changing over half the tile width"
        );
    }

    @Test
    void largeBiomesFlagChangesTheBiomeLayout() {
        final int width = 128;
        final int[] defaultBiomes = new int[width * width];
        final int[] largeBiomes = new int[width * width];
        final NativeBaselineSampler defaultSampler = new NativeBaselineSampler(mc17(), SEED, PredictionDimensions.OVERWORLD, 0);
        final NativeBaselineSampler largeSampler = new NativeBaselineSampler(
            mc17(), SEED, PredictionDimensions.OVERWORLD, WorldPreset.LARGE_BIOMES.cubiomesFlags()
        );
        assertTrue(defaultSampler.biomes(4, 0, 0, width, width, defaultBiomes));
        assertTrue(largeSampler.biomes(4, 0, 0, width, width, largeBiomes));
        // Same seed, same area: the LARGE_BIOMES generator flag must actually reach cubiomes,
        // which shows up as a different (zoomed-out) biome layout over a 512x512-block window.
        assertFalse(
            java.util.Arrays.equals(defaultBiomes, largeBiomes),
            "LARGE_BIOMES flag did not change the generated layout"
        );
    }

    @Test
    void modernSurfaceBiomesReuseTerrainWithoutChangingExactIds() {
        final int width = 32;
        final int stride = 2;
        final int blockX = -320;
        final int blockZ = 144;
        final int cells = width * width;
        final NativeBaselineSampler sampler = new NativeBaselineSampler(
            mc21(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        final int[] solid = new int[cells];
        final int[] fluid = new int[cells];
        final int[] surface = new int[cells];
        final int[] flags = new int[cells];
        assertTrue(sampler.surfaceColumns(
            blockX, blockZ, width, width, stride, solid, fluid, surface, flags
        ));
        final int[] reused = new int[cells];
        assertTrue(sampler.surfaceBiomes(
            blockX, blockZ, width, width, stride, solid, reused
        ));
        final int[] independentlyGenerated = new int[cells];
        assertTrue(sampler.biomesStrided(
            1, blockX, blockZ, width, width, stride, independentlyGenerated
        ));
        assertArrayEquals(
            independentlyGenerated,
            reused,
            "reusing exact terrain heights must preserve the previous final-surface biome ids"
        );
    }

    @Test
    void overviewPipelineHasTheSameQualityContractAcrossVersionsAndLods() {
        assertOverviewQuality(mc17(), 0);
        assertOverviewQuality(mc17(), 4);
        assertOverviewQuality(mc21(), 0);
        assertOverviewQuality(mc21(), 4);
    }

    private static void assertOverviewQuality(final int mcVersion, final int lod) {
        final NativeBaselineSampler sampler = new NativeBaselineSampler(
            mcVersion, SEED, PredictionDimensions.OVERWORLD, 0
        );
        final BaselineGrid overview = LodSampling.sample(sampler, false, lod, 0, 0);
        assertNotNull(overview);

        final int width = 16;
        final int cells = width * width;
        final int stride = 1 << lod;
        final int[] exactY = new int[cells];
        final int[] exactFluid = new int[cells];
        final int[] exactSurface = new int[cells];
        final int[] exactFlags = new int[cells];
        final int[] exactBiomes = new int[cells];
        assertTrue(sampler.surfaceColumns(
            0, 0, width, width, stride,
            exactY, exactFluid, exactSurface, exactFlags
        ));
        assertTrue(sampler.surfaceBiomes(
            0, 0, width, width, stride, exactY, exactBiomes
        ));

        final DerivedGrid overviewDerived = BaselineDeriver.derive(overview);
        final int[] errors = new int[cells];
        long errorSum = 0L;
        int fluidMatches = 0;
        int biomeMatches = 0;
        for (int z = 0; z < width; z++) {
            for (int x = 0; x < width; x++) {
                final int exactIndex = z * width + x;
                final int overviewIndex = BaselineGrid.index(x, z);
                final int error = Math.abs(overview.terrainY[overviewIndex] - exactY[exactIndex]);
                errors[exactIndex] = error;
                errorSum += error;
                final SurfaceKind overviewKind = SurfaceKind.byOrdinal(overviewDerived.kind[overviewIndex]);
                final boolean overviewFluid = overviewKind == SurfaceKind.WATER || overviewKind == SurfaceKind.ICE;
                final boolean exactFluidSurface = (exactFlags[exactIndex] & BaselineGrid.SURFACE_FLUID) != 0;
                fluidMatches += overviewFluid == exactFluidSurface ? 1 : 0;
                biomeMatches += overview.biomeId[overviewIndex] == exactBiomes[exactIndex] ? 1 : 0;
            }
        }
        java.util.Arrays.sort(errors);
        final double meanError = errorSum / (double) cells;
        final int p95Error = errors[(int) (cells * 0.95)];
        assertTrue(meanError <= 8.0, "overview height MAE=" + meanError + " at mc=" + mcVersion + ", lod=" + lod);
        assertTrue(p95Error <= 24, "overview height p95=" + p95Error + " at mc=" + mcVersion + ", lod=" + lod);
        assertTrue(
            fluidMatches >= cells * 0.85,
            "overview fluid agreement=" + fluidMatches + "/" + cells + " at mc=" + mcVersion + ", lod=" + lod
        );
        assertTrue(
            biomeMatches >= cells * 0.90,
            "overview biome agreement=" + biomeMatches + "/" + cells + " at mc=" + mcVersion + ", lod=" + lod
        );
    }

    private static int[] composeReportedTile(final int lod, final int blockX, final int blockZ) {
        final long seed = 6512112982729996127L;
        final int originX = TileMath.blockToTile(blockX, lod) * TileMath.blocksPerTile(lod);
        final int originZ = TileMath.blockToTile(blockZ, lod) * TileMath.blocksPerTile(lod);
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc17(), seed, PredictionDimensions.OVERWORLD, 0);
        final BaselineGrid grid = LodSampling.sample(sampler, false, lod, originX, originZ);
        assertNotNull(grid);
        final DerivedGrid derived = BaselineDeriver.derive(grid);
        CanopyStylizer.apply(derived, grid, seed, lod, originX, originZ);
        return PredictedTileComposer.compose(derived, grid, PredictionPalette.defaults());
    }

    private static int wideHorizontalTransitions(final int[] pixels) {
        int wide = 0;
        for (int z = 1; z < 256; z++) {
            int changed = 0;
            for (int x = 0; x < 256; x++) {
                // Ignore sub-step channel rounding that changes fewer than three RGB units in
                // total; the old full-strength contour corruption remains far above this floor.
                if (rgbDelta(pixels[z * 256 + x], pixels[(z - 1) * 256 + x]) >= 1) {
                    changed++;
                }
            }
            if (changed > 128) {
                wide++;
            }
        }
        return wide;
    }

    private static int rgbDelta(final int a, final int b) {
        return (
            Math.abs(Argb.red(a) - Argb.red(b))
                + Math.abs(Argb.green(a) - Argb.green(b))
                + Math.abs(Argb.blue(a) - Argb.blue(b))
        ) / 3;
    }

    /**
     * Real-generation guard for biome supersampling. At LOD4 one output pixel spans 16 blocks -
     * wider than a river - so a single lookup per pixel misses most thin features. The
     * sub-samples must surface biomes the per-pixel grid never sees; if they ever stop doing so,
     * meandering rivers and shorelines are back to breaking into straight, gappy lines.
     */
    @Test
    void coarseLodSubSamplesFindThinBiomesThePixelCentresMiss() {
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc21(), SEED, 0, 0);
        final BaselineGrid grid = LodSampling.sample(sampler, false, 4, 0, 0);
        assertNotNull(grid);
        assertTrue(grid.supersampled(), "LOD4 must supersample biomes");

        int pixelsWhoseSubSamplesDisagree = 0;
        int subSamplesUnseenAtTheCentre = 0;
        for (int z = 0; z < BaselineGrid.PIXELS; z++) {
            for (int x = 0; x < BaselineGrid.PIXELS; x++) {
                final int idx = BaselineGrid.index(x, z);
                boolean disagrees = false;
                for (int sz = 0; sz < grid.subPerAxis; sz++) {
                    for (int sx = 0; sx < grid.subPerAxis; sx++) {
                        if (grid.subBiomeId[grid.subIndex(idx, sx, sz)] != grid.biomeId[idx]) {
                            disagrees = true;
                            subSamplesUnseenAtTheCentre++;
                        }
                    }
                }
                if (disagrees) {
                    pixelsWhoseSubSamplesDisagree++;
                }
            }
        }

        assertTrue(
            pixelsWhoseSubSamplesDisagree > BaselineGrid.PIXELS * BaselineGrid.PIXELS / 100,
            "expected a real share of LOD4 pixels to contain a biome their centre misses, got "
                + pixelsWhoseSubSamplesDisagree + " of " + (BaselineGrid.PIXELS * BaselineGrid.PIXELS)
        );
        assertTrue(subSamplesUnseenAtTheCentre > 0);
    }

    /** Fine LODs already sample at or below the native biome grid, so they must not pay for 4x. */
    @Test
    void fineLodsDoNotPayForBiomeSupersampling() {
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc21(), SEED, 0, 0);
        for (final int lod : new int[] {0, 1, 2}) {
            final BaselineGrid grid = LodSampling.sample(sampler, false, lod, 0, 0);
            assertNotNull(grid);
            assertEquals(
                BaselineGrid.NO_SUPERSAMPLING, grid.subPerAxis,
                "LOD" + lod + " already reaches the native biome grid"
            );
        }
    }

    /**
     * Pins the overview terrain estimator against cubiomes' own exact column generation.
     *
     * <p>The estimator lives in {@code native/shim/confluxnative.c} and re-derives four helpers
     * that are {@code static} inside cubiomes' {@code biomenoise.c}. Nothing in the build stops
     * those from drifting apart when the submodule pin moves, so this measures the estimate
     * against {@code surfaceColumns}' real generated heights and fails if it stops tracking them.
     *
     * <p>Bounds come from measurement: mean absolute error across these spots is ~1.4 blocks (worst
     * single spot 2.6, in swamp). The noise-free estimator this replaced averaged ~3.2, so the
     * 2.5-block aggregate bound also catches a silent revert to it, while the per-spot bound of 8
     * catches outright divergence without being flaky on rough terrain.
     */
    @Test
    void overviewHeightsTrackExactGeneration() {
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc21(), SEED, 0, 0);
        final int[][] spots = {{0, 0}, {5000, -12000}, {-154944, -95552}, {250000, -300000}};
        final int side = 48;
        final int cells = side * side;
        double totalError = 0;
        int samples = 0;
        for (final int[] spot : spots) {
            for (final int stride : new int[] {4, 16}) {
                final int[] estimated = new int[cells];
                final int[] solidY = new int[cells];
                final int[] fluidY = new int[cells];
                final int[] surfaceY = new int[cells];
                final int[] flags = new int[cells];
                assertTrue(sampler.overviewHeights(spot[0], spot[1], side, side, stride, estimated));
                assertTrue(sampler.surfaceColumns(
                    spot[0], spot[1], side, side, stride, solidY, fluidY, surfaceY, flags
                ));
                double spotError = 0;
                for (int i = 0; i < cells; i++) {
                    spotError += Math.abs(estimated[i] - solidY[i]);
                }
                final double spotMean = spotError / cells;
                assertTrue(
                    spotMean < 8.0,
                    "overview height diverged from real generation at (" + spot[0] + "," + spot[1]
                        + ") stride " + stride + ": mean absolute error " + spotMean + " blocks"
                );
                totalError += spotError;
                samples += cells;
            }
        }
        final double mean = totalError / samples;
        assertTrue(
            mean < 2.5,
            "overview height must solve for the 3D terrain noise, not just the offset spline;"
                + " mean absolute error was " + mean + " blocks"
        );
    }
}
