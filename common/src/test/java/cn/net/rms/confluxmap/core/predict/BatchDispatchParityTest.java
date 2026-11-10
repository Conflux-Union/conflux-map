package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.nativepredict.CubiomesContext;
import cn.net.rms.confluxmap.nativepredict.CubiomesContexts;
import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Bit parity of the native batch dispatch entries against per-tile dispatch
 * through the same sampler: a wave's mixed tile shapes must come out identical
 * whether the shim submits them as one batch (chunked by the engine's
 * per-dispatch point cap, macro lattices interpolated host-side) or the caller
 * samples the tiles one by one. Skips like {@link PredictionNativeIntegrationTest}
 * where the native library cannot load, and additionally where the engine does
 * not actually serve stages - a CPU-fallback machine never exercises the batch
 * path, so there is nothing to compare.
 */
class BatchDispatchParityTest {
    /** Same fixed seed {@code PredictionNativeIntegrationTest} uses. */
    private static final long SEED = 146008555L;

    /**
     * Mixed-shape wave at distinct origins: strides 1/2 route through the
     * shim's macro-lattice path, 4/8 through the direct kernel, and the
     * stride-8 tiles carry a different (truncated) CfxNoiseDetail class than
     * the rest, so the batch must chunk them apart.
     */
    private static final int[][] TILES = {
        {1000, 1000, 130, 130, 4},
        {2000, 1000, 65, 65, 8},
        {3000, 1000, 130, 65, 4},
        {1000, 2000, 128, 128, 1},
        {2000, 2000, 66, 66, 2},
        {3000, 2000, 130, 130, 4},
        {1000, 3000, 65, 65, 8},
        {2000, 3000, 130, 65, 4},
    };

    private NativeBaselineSampler sampler;

    @BeforeEach
    void requireNativeServingGpu() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        sampler = new NativeBaselineSampler(
            McVersions.toCubiomes("1.21.4").orElseThrow(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        // The batch entries are refused wholesale without a live engine, so a
        // CPU-fallback machine (no fp64 OpenCL device, mode off) has nothing
        // to compare - skip the same way a missing library skips.
        final long before = sampler.gpuStageStats();
        assertTrue(sampler.overviewHeightsCoarse(0, 0, 130, 130, 4, new int[130 * 130]),
            "probe tile must sample");
        Assumptions.assumeTrue((sampler.gpuStageStats() - before) >>> 32 > 0,
            "engine is not serving stages on a GPU; batch parity is unobservable");
    }

    @Test
    void unsupportedLegacyBatchesDoNotDisableModernBatchDispatch() {
        assertTrue(GpuBackend.batchDispatchAvailable());
        final NativeBaselineSampler legacy = new NativeBaselineSampler(
            McVersions.toCubiomes("1.17.1").orElseThrow(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        final int[] origins = {0, 256};
        final int[] sizes = {130, 130};
        final int[] strides = {4, 4};
        final int[] out = new int[2 * 130 * 130];
        final int[] failMask = new int[1];
        assertNotEquals(0, legacy.overviewHeightsCoarseBatch(
            origins, origins, sizes, sizes, strides, 2, out, failMask
        ));
        assertTrue(GpuBackend.batchDispatchAvailable(), "legacy height refusal must stay local");
        assertNotEquals(0, legacy.surfaceBiomesBatch(
            origins, origins, sizes, sizes, strides, out, 2, new int[out.length], failMask
        ));
        assertTrue(GpuBackend.batchDispatchAvailable(), "legacy biome refusal must stay local");
        assertEquals(0, sampler.overviewHeightsCoarseBatch(
            origins, origins, sizes, sizes, strides, 2, out, failMask
        ));
        assertEquals(0, failMask[0]);
    }

    @Test
    void batchHeightsMatchPerTile() {
        final int total = totalCells(TILES);
        final int[] batchOut = new int[total];
        final int[] failMask = new int[1];
        assertEquals(0, sampler.overviewHeightsCoarseBatch(
            column(TILES, 0), column(TILES, 1), column(TILES, 2), column(TILES, 3),
            column(TILES, 4), TILES.length, batchOut, failMask
        ), "batch dispatch must be attempted");
        assertEquals(0, failMask[0], "every mixed-shape tile must be batch-eligible");

        int maxCells = 0;
        for (final int[] tile : TILES) {
            maxCells = Math.max(maxCells, tile[2] * tile[3]);
        }
        final int[] tileOut = new int[maxCells];
        int offset = 0;
        for (final int[] tile : TILES) {
            final int w = tile[2];
            final int h = tile[3];
            assertTrue(sampler.overviewHeightsCoarse(tile[0], tile[1], w, h, tile[4], tileOut),
                "per-tile dispatch failed for " + Arrays.toString(tile));
            assertArrayEquals(
                Arrays.copyOfRange(batchOut, offset, offset + w * h),
                Arrays.copyOf(tileOut, w * h),
                "tile " + Arrays.toString(tile) + " diverged from per-tile dispatch"
            );
            offset += w * h;
        }
    }

    @Test
    void voronoiShaAccessorMatchesJavaDerivation() throws NoSuchAlgorithmException {
        // The shim accessor must serve the context's stored sha; the Java derivation over the
        // seed (cubiomes' getVoronoiSHA) is the independent reference.
        final CubiomesContext ctx = CubiomesContexts.get(
            McVersions.toCubiomes("1.21.4").orElseThrow(), SEED, PredictionDimensions.OVERWORLD, 0
        );
        assertEquals(
            voronoiSha(SEED), ctx.voronoiSha(),
            "the sha accessor must return the context's stored voronoi sha"
        );
    }

    @Test
    void batchBiomesMatchPerTile() throws NoSuchAlgorithmException {
        final int total = totalCells(TILES);
        // The production pairing: surfaceBiomes consumes the raw overview
        // heights - the shim and the kernel clamp them at 62 themselves.
        final int[] terrainY = new int[total];
        final int[] heightsFailMask = new int[1];
        assertEquals(0, sampler.overviewHeightsCoarseBatch(
            column(TILES, 0), column(TILES, 1), column(TILES, 2), column(TILES, 3),
            column(TILES, 4), TILES.length, terrainY, heightsFailMask
        ), "heights batch feeding the terrain planes must be attempted");

        final long sha = voronoiSha(SEED);
        final int[] batchOut = new int[total];
        final int[] failMask = new int[1];
        assertEquals(0, sampler.surfaceBiomesBatch(
            sha, column(TILES, 0), column(TILES, 1), column(TILES, 2), column(TILES, 3),
            column(TILES, 4), terrainY, TILES.length, batchOut, failMask
        ), "biome batch dispatch must be attempted");
        assertEquals(0, failMask[0], "every mixed-shape tile must be batch-eligible");
        // A probe from a different generator must refuse instead of borrowing
        // this handle's packed state.
        assertNotEquals(0, sampler.surfaceBiomesBatch(
            sha + 1, column(TILES, 0), column(TILES, 1), column(TILES, 2), column(TILES, 3),
            column(TILES, 4), terrainY, TILES.length, new int[total], new int[1]
        ), "a mismatched voronoi sha must refuse the batch");

        int offset = 0;
        for (final int[] tile : TILES) {
            final int w = tile[2];
            final int h = tile[3];
            final int[] tileTerrain = Arrays.copyOfRange(terrainY, offset, offset + w * h);
            final int[] tileBiomes = new int[w * h];
            assertTrue(sampler.surfaceBiomes(tile[0], tile[1], w, h, tile[4], tileTerrain, tileBiomes),
                "per-tile dispatch failed for " + Arrays.toString(tile));
            assertArrayEquals(
                Arrays.copyOfRange(batchOut, offset, offset + w * h),
                tileBiomes,
                "tile " + Arrays.toString(tile) + " diverged from per-tile dispatch"
            );
            offset += w * h;
        }
    }

    @Test
    void oversizedBatchSplitsAndStillMatches() {
        // 17 blocks of 256x256 = 1,114,112 points, past the engine's 1<<20
        // per-dispatch cap: the shim must split (16 + 1 tiles) and stay
        // bit-identical to per-tile dispatch across the chunk boundary.
        final int n = 17;
        final int side = 256;
        final int[] blockX = new int[n];
        final int[] blockZ = new int[n];
        final int[] w = new int[n];
        final int[] h = new int[n];
        final int[] stride = new int[n];
        for (int i = 0; i < n; i++) {
            blockX[i] = 10_000 + i * 1_100;
            blockZ[i] = -50_000;
            w[i] = side;
            h[i] = side;
            stride[i] = 4;
        }
        final int[] batchOut = new int[n * side * side];
        final int[] failMask = new int[1];
        assertEquals(0, sampler.overviewHeightsCoarseBatch(
            blockX, blockZ, w, h, stride, n, batchOut, failMask
        ), "oversized batch dispatch must be attempted");
        assertEquals(0, failMask[0], "all oversized tiles must be batch-eligible");

        final int[] tileOut = new int[side * side];
        for (int i = 0; i < n; i++) {
            assertTrue(sampler.overviewHeightsCoarse(blockX[i], blockZ[i], side, side, 4, tileOut),
                "per-tile dispatch failed for tile " + i);
            assertArrayEquals(
                Arrays.copyOfRange(batchOut, i * side * side, (i + 1) * side * side),
                Arrays.copyOf(tileOut, side * side),
                "chunk-boundary tile " + i + " diverged from per-tile dispatch"
            );
        }
    }

    private static int totalCells(final int[][] tiles) {
        int total = 0;
        for (final int[] tile : tiles) {
            total += tile[2] * tile[3];
        }
        return total;
    }

    private static int[] column(final int[][] tiles, final int index) {
        final int[] out = new int[tiles.length];
        for (int i = 0; i < tiles.length; i++) {
            out[i] = tiles[i][index];
        }
        return out;
    }

    /**
     * cubiomes' {@code getVoronoiSHA}: SHA-256 over the seed's little-endian
     * bytes, with the first 8 digest bytes read back little-endian.
     */
    private static long voronoiSha(final long seed) throws NoSuchAlgorithmException {
        final byte[] message = new byte[8];
        for (int i = 0; i < 8; i++) {
            message[i] = (byte) (seed >>> (8 * i));
        }
        final byte[] digest = MessageDigest.getInstance("SHA-256").digest(message);
        long sha = 0;
        for (int i = 7; i >= 0; i--) {
            sha = (sha << 8) | (digest[i] & 0xffL);
        }
        return sha;
    }
}
