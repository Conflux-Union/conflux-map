package cn.net.rms.confluxmap.nativepredict;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.OptionalInt;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Exercises the real JNI path end to end: {@link NativeLib} loads the built native library and
 * every test below drives it through {@link CubiomesContext}/{@link CubiomesContexts}. Every
 * test starts with {@code Assumptions.assumeTrue(NativeLib.initForTests())} so a machine without
 * a working native build for its platform skips these instead of failing the whole module.
 *
 * <p>The biome/structure golden values below were captured by running this exact code once and
 * printing the results, then freezing them as constants (see the plan's slice S1 process) - they
 * are not independently derived. They pin down "cubiomes commit {@link
 * PredictorVersion#CUBIOMES_COMMIT_12}, this seed" to a fixed answer; a future cubiomes upgrade
 * that changes worldgen would need to deliberately re-freeze them.
 */
class CubiomesNativeTest {
    /** Arbitrary fixed seed used by every test in this class - chosen once, then frozen. */
    private static final long SEED = 146008555L;
    private static final int OVERWORLD = 0;
    private static final int END = 1;
    /** cubiomes {@code enum StructureType} ordinal for Village at this pinned commit. */
    private static final int VILLAGE = 5;

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

    private static CubiomesContext open(final int dim) {
        final CubiomesContext ctx = CubiomesContext.create(mc17(), SEED, dim, 0);
        assertNotNull(ctx, "context creation must succeed for a valid version/dim");
        return ctx;
    }

    @Test
    void biomeGridMatchesFrozenGoldenValues() {
        // verified against cubiomes 32a72991c22a on 2026-07-20: 8x8 grid at scale 4, offset (-32,-32)
        final int[] expected = {
            2, 2, 35, 35, 35, 35, 35, 35,
            2, 2, 35, 35, 35, 35, 35, 35,
            2, 2, 35, 35, 35, 35, 35, 35,
            35, 35, 35, 35, 35, 35, 35, 35,
            35, 35, 35, 35, 35, 35, 35, 35,
            35, 35, 35, 35, 35, 35, 35, 35,
            35, 35, 35, 35, 35, 35, 35, 35,
            35, 35, 35, 35, 35, 35, 35, 35,
        };
        try (CubiomesContext ctx = open(OVERWORLD)) {
            final int[] out = new int[8 * 8];
            assertEquals(0, ctx.biomes(4, -32, -32, 8, 8, out));
            assertArrayEquals(expected, out);
        }
    }

    @Test
    void stridedQueriesMatchDenseGridSelection() {
        final int w = 5;
        final int h = 4;
        final int stride = 3;
        final int rawW = (w - 1) * stride + 1;
        final int rawH = (h - 1) * stride + 1;
        try (CubiomesContext ctx = open(OVERWORLD)) {
            final int[] denseBiomes = new int[rawW * rawH];
            final int[] stridedBiomes = new int[w * h];
            assertEquals(0, ctx.biomes(4, -20, 30, rawW, rawH, denseBiomes));
            assertEquals(0, ctx.biomesStrided(4, -20, 30, w, h, stride, stridedBiomes));

            final int[] denseY = new int[rawW * rawH];
            final int[] denseIds = new int[rawW * rawH];
            final int[] stridedY = new int[w * h];
            final int[] stridedIds = new int[w * h];
            assertEquals(0, ctx.heights(-20, 30, rawW, rawH, denseY, denseIds));
            assertEquals(0, ctx.heightsStrided(-20, 30, w, h, stride, stridedY, stridedIds));

            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    final int dense = (z * stride) * rawW + x * stride;
                    final int sampled = z * w + x;
                    assertEquals(denseBiomes[dense], stridedBiomes[sampled]);
                    assertEquals(denseY[dense], stridedY[sampled]);
                    assertEquals(denseIds[dense], stridedIds[sampled]);
                }
            }
        }

        try (CubiomesContext ctx = open(END)) {
            final int[] denseY = new int[rawW * rawH];
            final int[] stridedY = new int[w * h];
            assertEquals(0, ctx.endHeights(-20, 30, rawW, rawH, denseY));
            assertEquals(0, ctx.endHeightsStrided(-20, 30, w, h, stride, stridedY));
            for (int z = 0; z < h; z++) {
                for (int x = 0; x < w; x++) {
                    assertEquals(denseY[(z * stride) * rawW + x * stride], stridedY[z * w + x]);
                }
            }
        }
    }

    @Test
    void scaleOneStridedBiomeRowsMatchDenseTileAtReportedStripeCoordinate() {
        final int w = 3;
        final int h = 3;
        try (CubiomesContext ctx = CubiomesContext.create(mc17(), 6512112982729996127L, OVERWORLD, 0)) {
            assertNotNull(ctx);
            final int[] dense = new int[w * h];
            final int[] first = new int[w * h];
            final int[] second = new int[w * h];
            assertEquals(0, ctx.biomes(1, -830, -438, w, h, dense));
            assertEquals(0, ctx.biomesStrided(1, -830, -438, w, h, 1, first));
            assertEquals(0, ctx.biomesStrided(1, -830, -438, w, h, 1, second));
            assertArrayEquals(first, second, "the row-wise result must be deterministic across calls");
            assertArrayEquals(dense, first, "stride-one sampling must be identical to one dense scale-one query");
        }
    }

    @Test
    void spotBiomesShowARealisticMix() {
        // verified against cubiomes 32a72991c22a on 2026-07-20: savanna(35), river(7), forest(4),
        // desert_hills(17), desert(2), ocean(0) - a plausible, non-degenerate mix of land/water.
        try (CubiomesContext ctx = open(OVERWORLD)) {
            assertEquals(35, spotBiome(ctx, 0, 0));
            assertEquals(7, spotBiome(ctx, 100, 100));
            assertEquals(4, spotBiome(ctx, -100, -100));
            assertEquals(17, spotBiome(ctx, 500, 500));
            assertEquals(2, spotBiome(ctx, -500, -500));
            assertEquals(0, spotBiome(ctx, 0, 200));
        }
    }

    private static int spotBiome(final CubiomesContext ctx, final int x4, final int z4) {
        final int[] out = new int[1];
        assertEquals(0, ctx.biomes(4, x4, z4, 1, 1, out));
        return out[0];
    }

    @Test
    void heightsAreInRangeAndDeterministicAcrossCallsAndThreads() throws InterruptedException {
        try (CubiomesContext ctx = open(OVERWORLD)) {
            final int[] y1 = new int[32 * 32];
            final int[] ids1 = new int[32 * 32];
            assertEquals(0, ctx.heights(0, 0, 32, 32, y1, ids1));
            for (final int y : y1) {
                assertTrue(y >= -64 && y <= 320, "height out of range: " + y);
            }

            final int[] y2 = new int[32 * 32];
            final int[] ids2 = new int[32 * 32];
            assertEquals(0, ctx.heights(0, 0, 32, 32, y2, ids2));
            assertArrayEquals(y1, y2, "two calls on the same context must be bit-identical");
            assertArrayEquals(ids1, ids2);
        }

        final int[][] fromThread = new int[1][];
        final Throwable[] threadFailure = new Throwable[1];
        final Thread worker = new Thread(() -> {
            try (CubiomesContext ctx = open(OVERWORLD)) {
                final int[] y = new int[32 * 32];
                final int[] ids = new int[32 * 32];
                ctx.heights(0, 0, 32, 32, y, ids);
                fromThread[0] = y;
            } catch (final Throwable t) {
                threadFailure[0] = t;
            }
        });
        worker.start();
        worker.join();
        assertNull(threadFailure[0], "worker thread must not fail");

        try (CubiomesContext ctx = open(OVERWORLD)) {
            final int[] y = new int[32 * 32];
            final int[] ids = new int[32 * 32];
            ctx.heights(0, 0, 32, 32, y, ids);
            assertArrayEquals(y, fromThread[0], "two independent contexts (one per thread) must agree exactly");
        }

        // Seed 0, tile (4,26): Vanilla 1.21.1 generates a small island that the old
        // NP_DEPTH-only approximation submerged. Values come directly from
        // ChunkGenerator#getHeight(OCEAN_FLOOR_WG) at the corresponding block coordinates.
        try (CubiomesContext ctx = CubiomesContext.create(mc21(), 0L, OVERWORLD, 0)) {
            assertNotNull(ctx);
            final int width = 10;
            final int[] y = new int[width * 5];
            final int[] ids = new int[width * 5];
            assertEquals(0, ctx.heights(295, 1678, width, 5, y, ids));
            assertEquals(63, y[0]); // block (1180,6712)
            assertEquals(64, y[1 * width + 2]); // block (1188,6716)
            assertEquals(64, y[1 * width + 4]); // block (1196,6716)
            assertEquals(64, y[2 * width + 5]); // block (1200,6720)
            assertEquals(63, y[3 * width + 7]); // block (1208,6724)
            assertEquals(65, y[4 * width + 9]); // block (1216,6728)

            final int[] solidY = new int[2];
            final int[] fluidY = new int[2];
            final int[] surfaceY = new int[2];
            final int[] surfaceFlags = new int[2];
            assertEquals(0, ctx.surfaceColumns(
                0, 8192, 2, 1, 4,
                solidY, fluidY, surfaceY, surfaceFlags
            ));
            for (int i = 0; i < solidY.length; i++) {
                assertTrue(solidY[i] < 62, "seed-0 tile (0,32) sample should be ocean terrain");
                assertEquals(62, fluidY[i]);
                assertEquals(62, surfaceY[i]);
                assertEquals(1, surfaceFlags[i]);
            }
        }
    }

    @Test
    void endHeightsArePlausibleAndDeterministic() {
        try (CubiomesContext ctx = open(END)) {
            final int[] y1 = new int[32 * 32];
            assertEquals(0, ctx.endHeights(0, 0, 32, 32, y1));
            boolean anyNonZero = false;
            for (final int y : y1) {
                assertTrue(y == 0 || (y >= -64 && y <= 320), "end height out of range: " + y);
                anyNonZero |= y != 0;
            }
            assertTrue(anyNonZero, "expected at least some real surface near the End main island (0 = void)");

            final int[] y2 = new int[32 * 32];
            assertEquals(0, ctx.endHeights(0, 0, 32, 32, y2));
            assertArrayEquals(y1, y2, "repeat query on the same context must be bit-identical");
        }
    }

    @Test
    void naturalTreeCandidatesExposePinnedCoordinatesAndPartialCoverage() {
        final long featureSeed = 6512112982729996127L;
        try (CubiomesContext ctx = CubiomesContext.create(mc17(), featureSeed, OVERWORLD, 0)) {
            assertNotNull(ctx);
            final int capacity = 64;
            final int[] xs = new int[capacity];
            final int[] ys = new int[capacity];
            final int[] zs = new int[capacity];
            final int[] types = new int[capacity];
            final int[] biomes = new int[capacity];
            final int[] flags = new int[capacity];
            final int[] count = new int[1];

            assertEquals(0, ctx.treeCandidates(16, 0, xs, ys, zs, types, biomes, flags, count));
            assertEquals(10, count[0]);
            assertEquals(258, xs[0]);
            assertEquals(4, zs[0]);
            assertEquals(2, types[0], "cubiomes TREE_SPRUCE");
            assertEquals(30, biomes[0], "cubiomes snowy_taiga");
            assertTrue((flags[0] & (1 << 5)) != 0, "the first candidate X/Z must be exact");
            assertTrue((flags[0] & (1 << 6)) != 0, "the first candidate type must be exact");

            assertEquals(
                CubiomesContext.STATUS_FEATURE_PARTIAL,
                ctx.treeCandidates(59, -316, xs, ys, zs, types, biomes, flags, count),
                "unsupported biome decorators must not masquerade as an exact empty chunk"
            );
        }
    }

    @Test
    void structurePositionsAreDeterministicAndRoundTrip() {
        // verified against cubiomes 32a72991c22a on 2026-07-20: 16 village attempts in this 4x4
        // region block (regions [-2,1]x[-2,1]); first entry is region (-2,-2).
        try (CubiomesContext ctx = open(OVERWORLD)) {
            final long[] out = new long[32];
            final int count = ctx.structures(VILLAGE, -2, -2, 1, 1, out);
            assertEquals(16, count);

            final int firstX = (int) (out[0] >> 32);
            final int firstZ = (int) (out[0] & 0xffffffffL);
            assertEquals(-736, firstX);
            assertEquals(-752, firstZ);

            for (int i = 0; i < count; i++) {
                final int x = (int) (out[i] >> 32);
                final int z = (int) (out[i] & 0xffffffffL);
                final long repacked = ((long) x << 32) | (z & 0xffffffffL);
                assertEquals(out[i], repacked, "pack/unpack round trip must be exact, including negative coordinates");
            }

            final long[] again = new long[32];
            assertEquals(count, ctx.structures(VILLAGE, -2, -2, 1, 1, again));
            assertArrayEquals(out, again, "structure search must be deterministic");

            final boolean viable = ctx.structureViable(VILLAGE, firstX, firstZ);
            assertEquals(viable, ctx.structureViable(VILLAGE, firstX, firstZ), "viability check must be deterministic");
        }
    }

    @Test
    void badMcVersionReturnsNullContext() {
        assertNull(CubiomesContext.create(-999, SEED, OVERWORLD, 0));
    }

    @Test
    void oversizedQueryIsRejectedWithoutCrashingTheJvm() {
        try (CubiomesContext ctx = open(OVERWORLD)) {
            // 2000*2000 = 4,000,000 cells, over the shim's 1<<20 cap; the array itself is sized
            // generously so it's the native bounds check being exercised, not the Java-side one.
            final int[] out = new int[2_000 * 2_000];
            final int status = ctx.biomes(4, 0, 0, 2_000, 2_000, out);
            assertTrue(status != 0, "oversized query must be rejected with a nonzero status");
        }
    }

    @Test
    void contextsCacheIsPerThreadAndEpochInvalidatesLazily() {
        final CubiomesContext first = CubiomesContexts.get(mc17(), SEED, OVERWORLD, 0);
        final CubiomesContext again = CubiomesContexts.get(mc17(), SEED, OVERWORLD, 0);
        assertSame(first, again, "same thread + same key must reuse the cached context");

        CubiomesContexts.bumpEpoch();
        final CubiomesContext afterBump = CubiomesContexts.get(mc17(), SEED, OVERWORLD, 0);
        assertNotSame(first, afterBump, "bumpEpoch must force a fresh context on next access");

        CubiomesContexts.closeAllOnThisThread();
    }

    /**
     * Pre-seeds a corrupt file at the exact path {@link NativeLib#init} would extract to, in a
     * base directory nothing has ever loaded from, then verifies {@code init} notices the hash
     * mismatch and overwrites it with the real bytes before loading. This deliberately does NOT
     * corrupt the shared {@code confluxmap-native-test} directory the other tests already loaded
     * from in this JVM: doing that to an already-{@code System.load}ed library's backing file
     * truncates a mapping the OS still has paged in and reliably SIGBUSes the JVM on the next
     * call into it - not a shim bug, just not something any native loader can survive.
     */
    @Test
    void reinitAfterCorruptionReExtracts() throws IOException {
        Assumptions.assumeTrue(
            System.getProperty("confluxmap.nativeLib") == null,
            "skipped: -Dconfluxmap.nativeLib override bypasses extraction entirely"
        );
        final Path freshBaseDir = Files.createTempDirectory("confluxmap-native-corrupt-test-");
        final Path libFile = NativeLib.resolveExtractedPathForTests(freshBaseDir);

        Files.createDirectories(libFile.getParent());
        Files.write(libFile, new byte[] {1, 2, 3, 4});
        assertEquals(4, Files.size(libFile));

        assertTrue(NativeLib.init(freshBaseDir), "init must still succeed after finding a corrupt file on disk");
        assertTrue(Files.size(libFile) > 4, "corrupted file must be re-extracted back to its proper (much larger) content");
    }
}
