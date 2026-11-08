package cn.net.rms.confluxmap.core.predict;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.MapExecutors;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.tile.TileUpdate;
import cn.net.rms.confluxmap.nativepredict.GpuBackend;
import cn.net.rms.confluxmap.nativepredict.McVersions;
import cn.net.rms.confluxmap.nativepredict.NativeLib;
import cn.net.rms.confluxmap.nativepredict.PredictionBackend;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fixed-seed, fixed-tile-set wall-clock comparison of GPU-batched wave composition against the
 * per-tile CPU baseline, driven through the real {@link PredictionTileService} pipeline
 * (admission, wave barriers, batch dispatch, repair, compose, upload queue). Each cell runs a
 * fresh service per round, marks one padded-ring viewport dirty in a single pump (the
 * {@code stagedTilesFormOneWaveThroughTheBatchSeam} formation), and measures wall from that pump
 * until every ring tile has uploaded; one warmup round precedes the three recorded rounds and
 * the median is reported.
 *
 * <p>The worker pool is sized to {@link WaveComposer#WAVE_CAP} so the matrix holds the topology
 * constant across machines: ring sizes 8/16/24 are exactly 1/2/3 consecutive waves, which is
 * the amortization question (does the GPU/CPU wall ratio grow with tile count past the
 * single-wave cap). The CPU leg runs with the seam admission gate closed
 * ({@link GpuBackend#batchDispatchAvailable}), so every stage stays per tile on every member -
 * the fair pre-redesign topology, not a refusal-repair loop.
 *
 * <p>GPU engagement is asserted per round through the engine's process-global dispatch counters:
 * a GPU round whose counters stand still is a dead engine publishing fake "GPU" numbers, and a
 * CPU round that moves them is a backend leak. {@code gpuHeightDelta} reads as 1 per batched
 * wave for shapes the entries serve (the first wave at lods 2-4); a +1-per-tile excess above
 * the wave count is the single-shot trickle past the first wave, or lod 1's per-tile
 * biome-stage height work.
 *
 * <p>The table goes to stdout rather than a logger because the common test classpath has no
 * Log4j configuration, so info-level lines never reach the console or the JUnit XML report.
 */
@Tag("benchmark")
class WaveCompositionBenchmarkTest {
    /** Same fixed seed {@code BatchDispatchParityTest} and {@code PredictionNativeIntegrationTest} use. */
    private static final long SEED = 146008555L;
    private static final String MC_VERSION = "1.21.4";
    private static final int[] LODS = {1, 2, 3, 4};
    /**
     * Viewport side per tile count: the padded ring of a side-s square is (s+2)^2 - s^2 = 4s+4
     * tiles, so sides 1/3/5 give 8/16/24 staged tiles - exactly 1/2/3 waves of eight. Ring-only
     * counts are always even and at least 8, so a six-tile single wave is not reachable through
     * this formation; the wave-cap-sized worker pool keeps every cell at whole waves instead.
     */
    private static final int[] VIEWPORT_SIDES = {1, 3, 5};
    private static final int WARMUP_ROUNDS = 1;
    private static final int MEASURED_ROUNDS = 3;
    private static final long ROUND_TIMEOUT_MS = 120_000L;
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("wave-composition-benchmark");

    private int mcVersion;

    private record RoundOutcome(long wallNanos, long surfaceDispatchDelta, long heightDispatchDelta) {
    }

    private record CellResult(PredictionBackend backend, int lod, int tiles, long[] wallNanos,
        long surfaceDispatchDelta, long heightDispatchDelta) {
    }

    @BeforeEach
    void requireNativeServingGpu() {
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final OptionalInt mc = McVersions.toCubiomes(MC_VERSION);
        Assumptions.assumeTrue(mc.isPresent(), "McVersions must know \"" + MC_VERSION + "\"");
        mcVersion = mc.getAsInt();
        // The GPU/CPU comparison is only meaningful where the engine actually serves stages on a
        // GPU; on a CPU-fallback machine both legs would measure the same reference implementation.
        final NativeBaselineSampler probe = new NativeBaselineSampler(mcVersion, SEED, PredictionDimensions.OVERWORLD, 0);
        final long before = probe.gpuStageStats();
        assertTrue(probe.overviewHeightsCoarse(0, 0, 130, 130, 4, new int[130 * 130]),
            "probe tile must sample");
        Assumptions.assumeTrue((probe.gpuStageStats() - before) >>> 32 > 0,
            "engine is not serving stages on a GPU; the backend comparison is unobservable");
        Assumptions.assumeTrue(GpuBackend.stats() != null,
            "engine stats export unavailable; GPU engagement cannot be asserted");
    }

    @Test
    void measureGpuVersusCpuWaveCompositionWall() throws InterruptedException {
        final GpuBackend.GpuStats stats = GpuBackend.stats();
        System.out.printf(Locale.ROOT,
            "[wave-bench] seed=%d mc=%s(%d) device=%s mode=%s workers=waveCap=%d"
                + " lods=%s tiles=%s rounds=%d(+%d warmup)%n",
            SEED, MC_VERSION, mcVersion, stats.device(), GpuBackend.engineModeLabel(stats.engineMode()),
            WaveComposer.WAVE_CAP, Arrays.toString(LODS), Arrays.toString(ringSizes()),
            MEASURED_ROUNDS, WARMUP_ROUNDS);

        final List<CellResult> cells = new ArrayList<>();
        final long benchStart = System.nanoTime();
        for (final int lod : LODS) {
            for (final int side : VIEWPORT_SIDES) {
                for (final PredictionBackend backend : List.of(PredictionBackend.GPU, PredictionBackend.CPU)) {
                    cells.add(runCell(backend, lod, side));
                }
            }
        }
        System.out.printf(Locale.ROOT, "[wave-bench] total bench wall %.1f s%n",
            (System.nanoTime() - benchStart) / 1e9);
        printTable(cells);
    }

    private CellResult runCell(final PredictionBackend backend, final int lod, final int side)
        throws InterruptedException {
        GpuBackend.configure(backend);
        final int tiles = 4 * side + 4;
        for (int round = 0; round < WARMUP_ROUNDS; round++) {
            runRound(backend, lod, side);
        }
        final long[] walls = new long[MEASURED_ROUNDS];
        long surfaceDelta = 0;
        long heightDelta = 0;
        for (int round = 0; round < MEASURED_ROUNDS; round++) {
            final RoundOutcome outcome = runRound(backend, lod, side);
            walls[round] = outcome.wallNanos();
            surfaceDelta = outcome.surfaceDispatchDelta();
            heightDelta = outcome.heightDispatchDelta();
        }
        final double medianMs = medianMs(walls);
        System.out.printf(Locale.ROOT,
            "[wave-bench] %s lod=%d tiles=%d median=%.0fms perTile=%.1fms wall=%s%n",
            backend == PredictionBackend.GPU ? "gpu" : "cpu", lod, tiles,
            medianMs, medianMs / tiles, wallsMs(walls));
        return new CellResult(backend, lod, tiles, walls, surfaceDelta, heightDelta);
    }

    /**
     * One cold round on a fresh harness: viewport in, ring marked dirty by one preload pump,
     * wall from that pump until every ring tile has landed in the upload queue. The engagement
     * and completeness assertions run on warmup rounds too - they are what keeps a silently
     * dead engine or a mid-cell refusal latch from publishing numbers measured on the wrong
     * topology.
     */
    private RoundOutcome runRound(final PredictionBackend backend, final int lod, final int side)
        throws InterruptedException {
        final SessionGuard sessionGuard = new SessionGuard();
        sessionGuard.begin(WORLD, DIM);
        final MapExecutors executors = new MapExecutors(WaveComposer.WAVE_CAP);
        final TileService uploads = new TileService(
            new MapWorldService(), executors, new ConfluxConfig(), new DaylightModel()
        );
        final PredictionState state = new PredictionState();
        state.setPresets(WorldPreset.DEFAULT, WorldPreset.DEFAULT);
        state.setSeed(SEED, mcVersion);
        final PredictionTileService service = new PredictionTileService(sessionGuard, state, executors, uploads);
        try {
            service.setViewport(DIM, lod, 0, side - 1, 0, side - 1);
            final Set<TileKey> expected = paddedRing(lod, side);
            final long[] dispatchBefore = dispatchCounters();
            final long start = System.nanoTime();
            service.preloadViewportPaddedRing();
            final Set<TileKey> uploaded = awaitUploaded(uploads, expected);
            final long wallNanos = System.nanoTime() - start;
            final long[] dispatchAfter = dispatchCounters();
            final long surfaceDelta = dispatchAfter[0] - dispatchBefore[0];
            final long heightDelta = dispatchAfter[1] - dispatchBefore[1];

            if (!uploaded.equals(expected)) {
                final Set<TileKey> missing = new HashSet<>(expected);
                missing.removeAll(uploaded);
                final Set<TileKey> extra = new HashSet<>(uploaded);
                extra.removeAll(expected);
                fail("round did not compose the ring exactly; missing=" + missing + " extra=" + extra);
            }
            if (backend == PredictionBackend.GPU) {
                assertTrue(surfaceDelta > 0 || heightDelta > 0,
                    "GPU round moved no engine dispatch counter; the engine is not engaging and"
                        + " the numbers would be CPU reference results mislabeled as GPU");
                assertTrue(GpuBackend.batchDispatchAvailable(),
                    "batch seam latched off during a GPU round; waves after the latch run"
                        + " leader-serialized per-tile repairs, not the batched topology");
            } else {
                assertTrue(surfaceDelta == 0 && heightDelta == 0,
                    "CPU round moved the engine dispatch counters (surface=" + surfaceDelta
                        + " height=" + heightDelta + "); the CPU leg must never touch the engine");
            }
            return new RoundOutcome(wallNanos, surfaceDelta, heightDelta);
        } finally {
            executors.shutdown(2000L);
        }
    }

    /** Polls the upload queue until every expected tile landed, or the round timeout expires. */
    private static Set<TileKey> awaitUploaded(final TileService uploads, final Set<TileKey> expected)
        throws InterruptedException {
        final Set<TileKey> uploaded = new HashSet<>();
        final long deadline = System.currentTimeMillis() + ROUND_TIMEOUT_MS;
        while (uploaded.size() < expected.size() && System.currentTimeMillis() < deadline) {
            for (final TileUpdate update : uploads.drainUploads(expected.size())) {
                uploaded.add(update.key());
            }
            if (uploaded.size() < expected.size()) {
                Thread.sleep(5);
            }
        }
        return uploaded;
    }

    /**
     * The tiles {@link PredictionTileService#preloadViewportPaddedRing} stages for a side-s
     * square viewport at tiles {@code (0..s-1)^2}: the (s+2)x(s+2) rect minus its interior.
     */
    private static Set<TileKey> paddedRing(final int lod, final int side) {
        final String layer = PredictionDimensions.layer(DIM).cacheId() + PredictedTileKeys.SUFFIX;
        final Set<TileKey> keys = new LinkedHashSet<>();
        for (int tileZ = -1; tileZ <= side; tileZ++) {
            for (int tileX = -1; tileX <= side; tileX++) {
                final boolean interior = tileX >= 0 && tileX < side && tileZ >= 0 && tileZ < side;
                if (!interior) {
                    keys.add(new TileKey(WORLD, DIM, layer, lod, tileX, tileZ));
                }
            }
        }
        return keys;
    }

    private static long[] dispatchCounters() {
        final GpuBackend.GpuStats stats = GpuBackend.stats();
        assertTrue(stats != null,
            "engine stats export vanished mid-run; engagement deltas are unobservable");
        return new long[] {stats.dispatches(), stats.heightDispatches()};
    }

    private static int[] ringSizes() {
        final int[] sizes = new int[VIEWPORT_SIDES.length];
        for (int i = 0; i < VIEWPORT_SIDES.length; i++) {
            sizes[i] = 4 * VIEWPORT_SIDES[i] + 4;
        }
        return sizes;
    }

    private static String wallsMs(final long[] wallNanos) {
        final StringBuilder builder = new StringBuilder("[");
        for (int i = 0; i < wallNanos.length; i++) {
            if (i > 0) {
                builder.append(", ");
            }
            builder.append(String.format(Locale.ROOT, "%.0f", wallNanos[i] / 1e6));
        }
        return builder.append(']').toString();
    }

    /** Median of the recorded wall samples, in milliseconds. */
    private static double medianMs(final long[] wallNanos) {
        final long[] sorted = wallNanos.clone();
        Arrays.sort(sorted);
        return sorted[MEASURED_ROUNDS / 2] / 1e6;
    }

    private static void printTable(final List<CellResult> cells) {
        System.out.printf(Locale.ROOT,
            "%n[wave-bench] ==== per-cell walls (median of %d, ms) ====%n"
                + "backend  lod tiles   median    perTile  gpuSurfDisp gpuHeightDisp%n",
            MEASURED_ROUNDS);
        for (final CellResult cell : cells) {
            final double median = medianMs(cell.wallNanos());
            System.out.printf(Locale.ROOT, "%-7s %3d %5d %8.0f %11.1f %11d %13d%n",
                cell.backend() == PredictionBackend.GPU ? "gpu" : "cpu",
                cell.lod(), cell.tiles(), median, median / cell.tiles(),
                cell.surfaceDispatchDelta(), cell.heightDispatchDelta());
        }
        System.out.printf(Locale.ROOT, "%n[wave-bench] ==== gpu/cpu wall ratio per (lod, tiles) ====%n");
        for (int i = 0; i + 1 < cells.size(); i += 2) {
            final CellResult gpu = cells.get(i);
            final CellResult cpu = cells.get(i + 1);
            if (gpu.backend() != PredictionBackend.GPU || cpu.backend() != PredictionBackend.CPU
                || gpu.lod() != cpu.lod() || gpu.tiles() != cpu.tiles()) {
                continue;
            }
            final double gpuMedian = medianMs(gpu);
            final double cpuMedian = medianMs(cpu);
            System.out.printf(Locale.ROOT, "lod=%d tiles=%d : gpu/cpu = %.2fx  (gpu %.0fms, cpu %.0fms)%n",
                gpu.lod(), gpu.tiles(), cpuMedian / gpuMedian, gpuMedian, cpuMedian);
        }
    }

    private static double medianMs(final CellResult cell) {
        return medianMs(cell.wallNanos());
    }
}
