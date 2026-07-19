package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
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

    private static int[] composeTile(final int mcVersion, final int dim, final boolean end, final int lod, final int tileOriginX, final int tileOriginZ) {
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mcVersion, SEED, dim);
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
    void reportedOceanCoordinateStaysWaterAtLod4() {
        final long reportedSeed = 6512112982729996127L;
        final int blockX = -2819;
        final int blockZ = -96;
        final int lod = 4;
        final int originX = TileMath.blockToTile(blockX, lod) * TileMath.blocksPerTile(lod);
        final int originZ = TileMath.blockToTile(blockZ, lod) * TileMath.blocksPerTile(lod);
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc17(), reportedSeed, PredictionDimensions.OVERWORLD);
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

    private static int[] composeReportedTile(final int lod, final int blockX, final int blockZ) {
        final long seed = 6512112982729996127L;
        final int originX = TileMath.blockToTile(blockX, lod) * TileMath.blocksPerTile(lod);
        final int originZ = TileMath.blockToTile(blockZ, lod) * TileMath.blocksPerTile(lod);
        final NativeBaselineSampler sampler = new NativeBaselineSampler(mc17(), seed, PredictionDimensions.OVERWORLD);
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
                if (pixels[z * 256 + x] != pixels[(z - 1) * 256 + x]) {
                    changed++;
                }
            }
            if (changed > 128) {
                wide++;
            }
        }
        return wide;
    }
}
