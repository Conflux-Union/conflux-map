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
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalInt;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
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
 * until every ring tile has been drained from the upload queue - the measurement boundary is the
 * upload queue, not a rendered frame or a texture upload. One warmup round (reported as the cold
 * sample) precedes the recorded rounds and the median is reported. Seed, origin, LOD/tile
 * matrix, round counts, and leg order are {@link WaveBenchmarkOptions} properties; the defaults
 * reproduce the historical matrix exactly.
 *
 * <p>The worker pool is sized to {@link WaveComposer#WAVE_CAP} so the matrix holds the topology
 * constant across machines: ring sizes 8/16/24 are exactly 1/2/3 consecutive waves, which is
 * the amortization question (does the GPU/CPU speedup grow with tile count past the single-wave
 * cap). The CPU leg runs with the seam admission gate closed
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
 * <p>Every round also snapshots the engine's process-global batch metrics ({@link
 * GpuBackend#metrics}) around its measured phase; the per-cell columns and the closing summary
 * block accumulate the deltas over the measured rounds - members/points/kernels/reads plus the
 * member and points histograms, so the waves' batch distribution reads straight off a run. The
 * counters only move through the two batch entries, so CPU rounds report zeros, and a library
 * predating the export degrades the columns to "-".
 *
 * <p>With timing instrumentation on, every round also totals the per-tile six-stage wall
 * vectors ({@link LodSampling.OverworldStage}, including wave members' even shares of batched
 * stages) and reports them per cell alongside the poll-granular first-tile latency and the cold
 * warmup sample. Stage columns are cumulative worker time - concurrent workers make those sums
 * larger than the wall they run inside, so they are never shown as fractions of wall. The
 * speedup column is CPU wall / GPU wall; the measurement boundary stays the upload queue, so
 * sched/upload-wait columns are the live client's debug logs, not this harness's.
 *
 * <p>The table goes to stdout rather than a logger because the common test classpath has no
 * Log4j configuration, so info-level lines never reach the console or the JUnit XML report.
 */
@Tag("benchmark")
class WaveCompositionBenchmarkTest {
    private static final String MC_VERSION = "1.21.4";
    private static final long ROUND_TIMEOUT_MS = 120_000L;
    private static final DimensionId DIM = DimensionId.OVERWORLD;
    private static final WorldIdentity WORLD = WorldIdentity.singleplayer("wave-composition-benchmark");
    private static final String TIMING_FLAG = "confluxmap.debugPredictionTiming";

    // Slot layout mirror of the GPU_METRICS_* defines in native/cubiomes/gpu.h (the slots
    // this harness reads; the histograms span [14, 46] and [47, 67]).
    private static final int SLOT_BATCH_MEMBERS = 1;
    private static final int SLOT_BATCH_POINTS = 2;
    private static final int SLOT_KERNEL_LAUNCHES = 3;
    private static final int SLOT_D2H_READS = 5;
    private static final int SLOT_MEMBER_HIST = 14;
    private static final int SLOT_POINT_HIST = 47;
    private static final int MEMBER_HIST_SLOTS = 33;
    private static final int POINT_HIST_SLOTS = 21;
    private static final int HIST_COLUMN_CHARS = 40;

    private int mcVersion;
    private int warmupRounds;
    private int measuredRounds;

    /**
     * One recorded round of one cell. The stage totals are the sum over every ring tile of the
     * tile's own six-stage wall vector (batched stages carry their even member share inside the
     * vector), so they read as cumulative worker time. The metrics delta is the per-slot change
     * of the engine's process-global batch metrics across the round's measured phase, or null
     * when the library predates the metrics export.
     */
    private record RoundOutcome(
        long wallNanos,
        long surfaceDispatchDelta,
        long heightDispatchDelta,
        long firstDrainNanos,
        long[] overworldStageTotals,
        long[] metricsDelta
    ) {
    }

    /** One cell: raw per-round samples plus per-stage medians. */
    private record CellResult(
        PredictionBackend backend,
        int lod,
        int tiles,
        long[] wallNanos,
        long coldWallNanos,
        long[] firstUploadNanos,
        long[] medianOverworldStageNanos,
        long surfaceDispatchDelta,
        long heightDispatchDelta,
        long[] metricsDelta
    ) {
    }

    @BeforeEach
    void requireNativeServingGpu() {
        // Timing instrumentation is load-bearing for this harness: the six-stage wall vectors
        // only exist when the debug-timing flag is set, and the benchmark JVM is forked, so the
        // flag never leaks into :common:test.
        System.setProperty(TIMING_FLAG, "true");
        Assumptions.assumeTrue(NativeLib.initForTests(), "native prediction library unavailable on this platform");
        final OptionalInt mc = McVersions.toCubiomes(MC_VERSION);
        Assumptions.assumeTrue(mc.isPresent(), "McVersions must know \"" + MC_VERSION + "\"");
        mcVersion = mc.getAsInt();
        // The GPU/CPU comparison is only meaningful where the engine actually serves stages on a
        // GPU; on a CPU-fallback machine both legs would measure the same reference implementation.
        final NativeBaselineSampler probe =
            new NativeBaselineSampler(mcVersion, WaveBenchmarkOptions.seed(), PredictionDimensions.OVERWORLD, 0);
        final long before = probe.gpuStageStats();
        assertTrue(probe.overviewHeightsCoarse(0, 0, 130, 130, 4, new int[130 * 130]),
            "probe tile must sample");
        Assumptions.assumeTrue((probe.gpuStageStats() - before) >>> 32 > 0,
            "engine is not serving stages on a GPU; the backend comparison is unobservable");
        Assumptions.assumeTrue(GpuBackend.stats() != null,
            "engine stats export unavailable; GPU engagement cannot be asserted");
        warmupRounds = WaveBenchmarkOptions.warmupRounds();
        measuredRounds = WaveBenchmarkOptions.measuredRounds();
    }

    @AfterEach
    void clearTimingFlag() {
        System.clearProperty(TIMING_FLAG);
    }

    @Test
    void measureGpuVersusCpuWaveCompositionWall() throws InterruptedException {
        final GpuBackend.GpuStats stats = GpuBackend.stats();
        final WaveBenchmarkOptions.Scenario scenario = WaveBenchmarkOptions.scenario();
        System.out.printf(Locale.ROOT,
            "[wave-bench] mc=%s(%d) device=%s mode=%s workers=waveCap=%d scenario=%s"
                + " seed=%d origin=(%d,%d) lods=%s sides=%s rounds=%d(+%d warmup) order=%s%n",
            MC_VERSION, mcVersion, stats.device(), GpuBackend.engineModeLabel(stats.engineMode()),
            WaveComposer.WAVE_CAP, scenario.name(), WaveBenchmarkOptions.seed(),
            WaveBenchmarkOptions.originTileX(), WaveBenchmarkOptions.originTileZ(),
            Arrays.toString(WaveBenchmarkOptions.lods()), Arrays.toString(WaveBenchmarkOptions.sides()),
            measuredRounds, warmupRounds,
            WaveBenchmarkOptions.cpuFirst() ? "cpu-first" : "gpu-first");

        final List<CellResult> cells = new ArrayList<>();
        final long benchStart = System.nanoTime();
        for (final int lod : WaveBenchmarkOptions.lods()) {
            for (final int side : WaveBenchmarkOptions.sides()) {
                for (final PredictionBackend backend : backendsInOrder()) {
                    cells.add(runCell(backend, lod, side));
                }
            }
        }
        System.out.printf(Locale.ROOT, "[wave-bench] total bench wall %.1f s%n",
            (System.nanoTime() - benchStart) / 1e9);
        printTable(cells);
    }

    private static List<PredictionBackend> backendsInOrder() {
        return WaveBenchmarkOptions.cpuFirst()
            ? List.of(PredictionBackend.CPU, PredictionBackend.GPU)
            : List.of(PredictionBackend.GPU, PredictionBackend.CPU);
    }

    private CellResult runCell(final PredictionBackend backend, final int lod, final int side)
        throws InterruptedException {
        GpuBackend.configure(backend);
        final int tiles = 4 * side + 4;
        long coldWallNanos = -1L;
        for (int round = 0; round < warmupRounds; round++) {
            final RoundOutcome warmup = runRound(backend, lod, side);
            if (round == 0) {
                coldWallNanos = warmup.wallNanos();
            }
        }
        final long[] walls = new long[measuredRounds];
        final long[] firstUploads = new long[measuredRounds];
        final long[][] overworldStages = new long[measuredRounds][];
        long surfaceDelta = 0;
        long heightDelta = 0;
        long[] metricsDelta = null;
        for (int round = 0; round < measuredRounds; round++) {
            final RoundOutcome outcome = runRound(backend, lod, side);
            walls[round] = outcome.wallNanos();
            firstUploads[round] = outcome.firstDrainNanos();
            overworldStages[round] = outcome.overworldStageTotals();
            surfaceDelta = outcome.surfaceDispatchDelta();
            heightDelta = outcome.heightDispatchDelta();
            metricsDelta = addMetrics(metricsDelta, outcome.metricsDelta());
        }
        final double medianMs = medianMs(walls);
        System.out.printf(Locale.ROOT,
            "[wave-bench] %s lod=%d tiles=%d median=%.0fms perTile=%.1fms first=%.1fms"
                + " cold=%.0fms wall=%s first=%s%n",
            backend == PredictionBackend.GPU ? "gpu" : "cpu", lod, tiles,
            medianMs, medianMs / tiles, medianMs(firstUploads),
            coldWallNanos / 1e6, wallsMs(walls), wallsMs(firstUploads));
        return new CellResult(backend, lod, tiles, walls, coldWallNanos, firstUploads,
            medianPerStage(overworldStages), surfaceDelta, heightDelta, metricsDelta);
    }

    /**
     * One cold round on a fresh harness: viewport in, ring marked dirty by one preload pump,
     * wall from that pump until every ring tile has left the upload queue. Each drained update
     * is reported back through {@link PredictionTileService#onPredictedUploaded} with a zero
     * texture-upload cost - the harness has no render thread, and the zero keeps the queue
     * boundary honest. The engagement and completeness assertions run on warmup rounds too -
     * they are what keeps a silently dead engine or a mid-cell refusal latch from publishing
     * numbers measured on the wrong topology.
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
        state.setSeed(WaveBenchmarkOptions.seed(), mcVersion);
        final PredictionTileService service =
            new PredictionTileService(sessionGuard, state, executors, uploads);
        try {
            final int originX = WaveBenchmarkOptions.originTileX();
            final int originZ = WaveBenchmarkOptions.originTileZ();
            service.setViewport(DIM, lod, originX, originX + side - 1, originZ, originZ + side - 1);
            final Set<TileKey> expected = paddedRing(lod, side, originX, originZ);
            final long[] dispatchBefore = dispatchCounters();
            final long[] metricsBefore = GpuBackend.metrics();
            final long start = System.nanoTime();
            service.preloadViewportPaddedRing();
            final RoundDrain drained = awaitUploaded(service, uploads, expected, start);
            final long wallNanos = System.nanoTime() - start;
            final long[] dispatchAfter = dispatchCounters();
            final long[] metricsAfter = GpuBackend.metrics();
            final long surfaceDelta = dispatchAfter[0] - dispatchBefore[0];
            final long heightDelta = dispatchAfter[1] - dispatchBefore[1];
            final long[] stageTotals = stageTotals(service, expected);

            if (!drained.uploaded().equals(expected)) {
                final Set<TileKey> missing = new HashSet<>(expected);
                missing.removeAll(drained.uploaded());
                final Set<TileKey> extra = new HashSet<>(drained.uploaded());
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
            return new RoundOutcome(wallNanos, surfaceDelta, heightDelta,
                drained.firstDrainNanos() - start, stageTotals,
                metricsDelta(metricsBefore, metricsAfter));
        } finally {
            executors.shutdown(2000L);
        }
    }

    private static long[] stageTotals(final PredictionTileService service, final Set<TileKey> expected) {
        final Map<TileKey, long[]> vectors = service.tileOverworldStageNanosForTest();
        final long[] totals = new long[LodSampling.OVERWORLD_STAGE_COUNT];
        for (final TileKey key : expected) {
            final long[] vector = vectors.get(key);
            assertTrue(vector != null,
                "staged tile " + key + " must expose a six-stage wall vector under timing");
            for (int stage = 0; stage < totals.length; stage++) {
                totals[stage] += vector[stage];
            }
        }
        return totals;
    }

    /**
     * Polls the upload queue until every expected tile drained, reporting each drained update
     * back to the service so the timing vectors land; or the round timeout expires. The first
     * drain moment is the poll-granular first-tile latency sample (at most one poll interval
     * late).
     */
    private static RoundDrain awaitUploaded(
        final PredictionTileService service,
        final TileService uploads,
        final Set<TileKey> expected,
        final long startNanos
    ) throws InterruptedException {
        final Set<TileKey> uploaded = new HashSet<>();
        long firstDrainNanos = -1L;
        final long deadline = System.currentTimeMillis() + ROUND_TIMEOUT_MS;
        while (uploaded.size() < expected.size() && System.currentTimeMillis() < deadline) {
            for (final TileUpdate update : uploads.drainUploads(expected.size())) {
                if (firstDrainNanos < 0L) {
                    firstDrainNanos = System.nanoTime();
                }
                uploaded.add(update.key());
                service.onPredictedUploaded(update.key(), 0L);
            }
            if (uploaded.size() < expected.size()) {
                Thread.sleep(5);
            }
        }
        return new RoundDrain(uploaded, firstDrainNanos);
    }

    private record RoundDrain(Set<TileKey> uploaded, long firstDrainNanos) {
    }

    /**
     * The tiles {@link PredictionTileService#preloadViewportPaddedRing} stages for a side-s
     * square viewport at tiles {@code (originX..originX+s-1) x (originZ..originZ+s-1)}: the
     * (s+2)x(s+2) rect minus its interior.
     */
    private static Set<TileKey> paddedRing(final int lod, final int side, final int originX, final int originZ) {
        final String layer = PredictionDimensions.layer(DIM).cacheId() + PredictedTileKeys.SUFFIX;
        final Set<TileKey> keys = new LinkedHashSet<>();
        for (int tileZ = originZ - 1; tileZ <= originZ + side; tileZ++) {
            for (int tileX = originX - 1; tileX <= originX + side; tileX++) {
                final boolean interior = tileX >= originX && tileX < originX + side
                    && tileZ >= originZ && tileZ < originZ + side;
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

    /** Median of the recorded samples (upper-middle element), in milliseconds. */
    private static double medianMs(final long[] samples) {
        final long[] sorted = samples.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2] / 1e6;
    }

    /** Per-stage medians across the recorded rounds; a null round vector contributes nothing. */
    private static long[] medianPerStage(final long[][] perRound) {
        final int width = perRound.length > 0 ? perRound[0].length : 0;
        final long[] medians = new long[width];
        for (int stage = 0; stage < width; stage++) {
            final long[] column = new long[perRound.length];
            int used = 0;
            for (final long[] round : perRound) {
                if (round != null && stage < round.length) {
                    column[used++] = round[stage];
                }
            }
            if (used > 0) {
                final long[] sorted = Arrays.copyOf(column, used);
                Arrays.sort(sorted);
                medians[stage] = sorted[used / 2];
            }
        }
        return medians;
    }

    private static void printTable(final List<CellResult> cells) {
        final String[] stageNames = stageNames();
        final boolean metricsOn = cells.stream().anyMatch(cell -> cell.metricsDelta() != null);
        System.out.printf(Locale.ROOT,
            "%n[wave-bench] ==== per-cell walls and first-tile (median of %d, ms;%s) ====%n"
                + "backend  lod tiles   median    perTile     first      cold  gpuSurfDisp gpuHeightDisp"
                + " members  points kernels reads memberHist pointHist%n",
            WaveBenchmarkOptions.measuredRounds(),
            metricsOn ? " batch metrics summed over the measured rounds" : " metrics=unavailable");
        for (final CellResult cell : cells) {
            final long[] metrics = cell.metricsDelta();
            final double median = medianMs(cell.wallNanos());
            System.out.printf(Locale.ROOT,
                "%-7s %3d %5d %8.0f %11.1f %8.1f %9.0f %11d %13d %7s %8s %7s %6s %-40s %-40s%n",
                cell.backend() == PredictionBackend.GPU ? "gpu" : "cpu",
                cell.lod(), cell.tiles(), median, median / cell.tiles(),
                medianMs(cell.firstUploadNanos()), cell.coldWallNanos() / 1e6,
                cell.surfaceDispatchDelta(), cell.heightDispatchDelta(),
                metrics != null ? String.valueOf(metrics[SLOT_BATCH_MEMBERS]) : "-",
                metrics != null ? String.valueOf(metrics[SLOT_BATCH_POINTS]) : "-",
                metrics != null ? String.valueOf(metrics[SLOT_KERNEL_LAUNCHES]) : "-",
                metrics != null ? String.valueOf(metrics[SLOT_D2H_READS]) : "-",
                metrics != null ? truncateHist(renderMemberHist(metrics)) : "-",
                metrics != null ? truncateHist(renderPointHist(metrics)) : "-");
        }

        // Stage medians, grouped per (lod, tiles) so the GPU and CPU legs sit side by side.
        // Stage columns are cumulative worker time (Σ over tiles and workers), NOT wall shares.
        System.out.printf(Locale.ROOT,
            "%n[wave-bench] ==== per-stage medians (Σworker ms; batched stages split the batch wall "
                + "evenly across members) ====%n"
                + "lod tiles backend " + String.join(" ", stageNames) + "     wall    first%n");
        final Map<String, List<CellResult>> grouped = new LinkedHashMap<>();
        for (final CellResult cell : cells) {
            grouped.computeIfAbsent(cell.lod() + "x" + cell.tiles(), k -> new ArrayList<>()).add(cell);
        }
        for (final List<CellResult> group : grouped.values()) {
            for (final CellResult cell : group) {
                final StringBuilder row = new StringBuilder(String.format(Locale.ROOT,
                    "%3d %5d %-7s", cell.lod(), cell.tiles(),
                    cell.backend() == PredictionBackend.GPU ? "gpu" : "cpu"));
                for (final long stage : cell.medianOverworldStageNanos()) {
                    row.append(String.format(Locale.ROOT, " %8.1f", stage / 1e6));
                }
                row.append(String.format(Locale.ROOT, " %8.1f %8.1f%n",
                    medianMs(cell.wallNanos()), medianMs(cell.firstUploadNanos())));
                System.out.print(row);
            }
        }

        System.out.printf(Locale.ROOT,
            "%n[wave-bench] ==== gpu-vs-cpu wall speedup per (lod, tiles) ====%n");
        final Map<String, CellResult> byBackend = new HashMap<>();
        for (final CellResult cell : cells) {
            byBackend.put(cell.lod() + "x" + cell.tiles() + ":" + cell.backend(), cell);
        }
        for (final Map.Entry<String, List<CellResult>> group : grouped.entrySet()) {
            final CellResult gpu = byBackend.get(group.getKey() + ":" + PredictionBackend.GPU);
            final CellResult cpu = byBackend.get(group.getKey() + ":" + PredictionBackend.CPU);
            if (gpu == null || cpu == null) {
                continue;
            }
            final double gpuMedian = medianMs(gpu.wallNanos());
            final double cpuMedian = medianMs(cpu.wallNanos());
            System.out.printf(Locale.ROOT,
                "lod=%d tiles=%d : speedup(cpu/gpu wall) = %.2fx  (gpu %.0fms, cpu %.0fms,"
                    + " first-tile gpu %.1fms cpu %.1fms)%n",
                gpu.lod(), gpu.tiles(), cpuMedian / gpuMedian, gpuMedian, cpuMedian,
                medianMs(gpu.firstUploadNanos()), medianMs(cpu.firstUploadNanos()));
        }

        // Per-cell batch metrics so the "batch counters scale with wave count" acceptance
        // reads directly from the run: the first pump admits a leader wave through the seam,
        // the rest trickle through per-tile dispatch the entries refuse.
        System.out.printf(Locale.ROOT,
            "%n[wave-bench] ==== gpu batch metric deltas per cell (summed over %d measured rounds) ====%n",
            WaveBenchmarkOptions.measuredRounds());
        boolean metricsPrinted = false;
        for (final CellResult cell : cells) {
            final long[] metrics = cell.metricsDelta();
            if (cell.backend() != PredictionBackend.GPU || metrics == null) {
                continue;
            }
            System.out.printf(Locale.ROOT,
                "lod=%d tiles=%d : members=%d points=%d kernels=%d reads=%d memberHist=[%s] pointHist=[%s]%n",
                cell.lod(), cell.tiles(), metrics[SLOT_BATCH_MEMBERS], metrics[SLOT_BATCH_POINTS],
                metrics[SLOT_KERNEL_LAUNCHES], metrics[SLOT_D2H_READS],
                renderMemberHist(metrics), renderPointHist(metrics));
            metricsPrinted = true;
        }
        if (!metricsPrinted) {
            System.out.printf(Locale.ROOT, "metrics=unavailable%n");
        }
    }

    /** Per-slot delta of two engine metrics snapshots, or null when the export is unavailable. */
    private static long[] metricsDelta(final long[] before, final long[] after) {
        if (before == null || after == null) {
            return null;
        }
        final long[] delta = new long[after.length];
        for (int slot = 0; slot < delta.length; slot++) {
            delta[slot] = after[slot] - before[slot];
        }
        return delta;
    }

    /** Accumulates one round's metrics delta into a cell's running totals. */
    private static long[] addMetrics(final long[] totals, final long[] delta) {
        if (delta == null) {
            return totals;
        }
        final long[] out = totals != null ? totals : new long[delta.length];
        for (int slot = 0; slot < delta.length; slot++) {
            out[slot] += delta[slot];
        }
        return out;
    }

    /** Member-count histogram render, "m1x2 m8x1" style, nonzero entries only. */
    private static String renderMemberHist(final long[] delta) {
        final StringBuilder builder = new StringBuilder();
        for (int slot = 0; slot < MEMBER_HIST_SLOTS; slot++) {
            if (delta[SLOT_MEMBER_HIST + slot] != 0L) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append('m').append(slot).append('x').append(delta[SLOT_MEMBER_HIST + slot]);
            }
        }
        return builder.toString();
    }

    /** Points histogram render, "p4K x2" style, nonzero entries only; slot k covers (2^(k-1), 2^k]. */
    private static String renderPointHist(final long[] delta) {
        final StringBuilder builder = new StringBuilder();
        for (int slot = 0; slot < POINT_HIST_SLOTS; slot++) {
            if (delta[SLOT_POINT_HIST + slot] != 0L) {
                if (builder.length() > 0) {
                    builder.append(' ');
                }
                builder.append('p').append(pointMagnitude(slot))
                    .append(" x").append(delta[SLOT_POINT_HIST + slot]);
            }
        }
        return builder.toString();
    }

    /** Humanized magnitude of a points-histogram slot: its {@code 2^k} points cap, 1K/4K/.../1M. */
    private static String pointMagnitude(final int slot) {
        if (slot < 10) {
            return String.valueOf(1L << slot);
        }
        return slot < 20 ? (1L << (slot - 10)) + "K" : "1M";
    }

    /** Keeps the per-cell table's histogram columns readable. */
    private static String truncateHist(final String render) {
        return render.length() <= HIST_COLUMN_CHARS
            ? render
            : render.substring(0, HIST_COLUMN_CHARS - 3) + "...";
    }

    private static String[] stageNames() {
        final String[] names = new String[LodSampling.OVERWORLD_STAGE_COUNT];
        for (int i = 0; i < names.length; i++) {
            names[i] = switch (LodSampling.OverworldStage.values()[i]) {
                case H_LATTICE -> "hLattice";
                case ANCHOR_BIOMES -> "anchorBio";
                case BAND -> "band";
                case EXACT_COLUMNS -> "exactCol";
                case CELLS -> "cells";
                case COMPOSE -> "compose";
            };
        }
        return names;
    }
}
