package cn.net.rms.confluxmap.nativepredict;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.task.CpuTopology;
import cn.net.rms.confluxmap.core.task.WorkerThreadPolicy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the AUTO benchmark orchestration over fake seams: a virtual clock plus counting
 * workloads pin the verdict boundary, the fault/timeout paths that must not cache, and the
 * no-work paths (library unavailable, prewarm already applied, cache hit). Every test
 * resets the process-global verdict slot afterwards.
 */
class GpuBenchmarkTest {
    private static final Logger LOGGER = LogManager.getLogger("GpuBenchmarkTest");

    private static final CpuTopology TOPOLOGY = CpuTopology.uniform(8, "uniform test");

    /**
     * Virtual clock that only moves when the sleeper or the workload moves it, mirroring a
     * real wall clock where reading costs nothing - so timed ratios see exactly the
     * workload's cost.
     */
    private static final class SteppingClock implements LongSupplier {
        private final AtomicLong now = new AtomicLong(1_000_000L);

        @Override
        public long getAsLong() {
            return now.get();
        }

        void advance(final long millis) {
            now.addAndGet(millis);
        }

        long current() {
            return now.get();
        }
    }

    /** Workload whose per-tile costs are fixed pushes of the virtual clock. */
    private static final class TimedWorkload implements GpuBenchmark.Workload {
        private final SteppingClock clock;
        private final long cpuMillisPerTile;
        private final long gpuMillisPerTile;
        final AtomicInteger cpuTiles = new AtomicInteger();
        final AtomicInteger gpuBatches = new AtomicInteger();
        volatile boolean cpuFails;
        volatile boolean gpuFails;

        TimedWorkload(final SteppingClock clock, final long cpuMillisPerTile, final long gpuMillisPerTile) {
            this.clock = clock;
            this.cpuMillisPerTile = cpuMillisPerTile;
            this.gpuMillisPerTile = gpuMillisPerTile;
        }

        @Override
        public boolean cpuTile(final CubiomesContext ctx, final int tileIndex) {
            cpuTiles.incrementAndGet();
            clock.advance(cpuMillisPerTile);
            return !cpuFails;
        }

        @Override
        public boolean gpuBatch(final CubiomesContext ctx, final int firstTile, final int tileCount) {
            gpuBatches.incrementAndGet();
            clock.advance(gpuMillisPerTile * tileCount);
            return !gpuFails;
        }
    }

    /** Records pool sizes and mode transitions; the pool runs tasks on the calling thread. */
    private static final class Recording {
        final List<Integer> poolSizes = new ArrayList<>();
        final List<Integer> modes = new ArrayList<>();
        final AtomicInteger poolBuilds = new AtomicInteger();
    }

    @AfterEach
    void resetVerdict() {
        GpuBackend.applyAutoVerdict(null);
        GpuBackend.configure(PredictionBackend.AUTO);
    }

    private static GpuBenchmark.BenchmarkContext context(
        final Path cacheFile,
        final AtomicReference<PredictionBackend> configured,
        final SteppingClock clock,
        final TimedWorkload workload,
        final Recording recording,
        final IntSupplier buildBusy,
        final Supplier<GpuBackend.GpuStats> stats
    ) {
        return new GpuBenchmark.BenchmarkContext(
            new GpuBenchmarkStore(cacheFile, LOGGER),
            configured::get,
            defaultWorkerCount(),
            new GpuBenchmark.Seams(
                clock,
                () -> true,
                buildBusy,
                () -> 1,
                stats,
                (mcVersion, seed, dim, flags) -> null,
                workload,
                threads -> {
                    recording.poolSizes.add(threads);
                    recording.poolBuilds.incrementAndGet();
                    return runnable -> runnable.run();
                },
                recording.modes::add,
                clock::advance
            ),
            LOGGER
        );
    }

    private static int defaultWorkerCount() {
        return WorkerThreadPolicy.resolve(ConfluxConfig.WorkerThreadMode.AUTO, 0, TOPOLOGY);
    }

    /** Context variant on a uniform-2 topology (one worker): the virtual clock cannot
     * compress parallel wall time, and with a single thread the wall IS the thread total. */
    private static GpuBenchmark.BenchmarkContext singleThreadContext(
        final Path cacheFile,
        final AtomicReference<PredictionBackend> configured,
        final SteppingClock clock,
        final TimedWorkload workload,
        final Recording recording,
        final IntSupplier buildBusy,
        final Supplier<GpuBackend.GpuStats> stats
    ) {
        return new GpuBenchmark.BenchmarkContext(
            cacheFile == null ? null : new GpuBenchmarkStore(cacheFile, LOGGER),
            configured::get,
            1,
            new GpuBenchmark.Seams(
                clock, () -> true, buildBusy, () -> 1, stats,
                (mcVersion, seed, dim, flags) -> null, workload,
                threads -> {
                    recording.poolSizes.add(threads);
                    recording.poolBuilds.incrementAndGet();
                    return runnable -> runnable.run();
                },
                recording.modes::add, clock::advance),
            LOGGER
        );
    }

    private static GpuBenchmarkStore.Key expectedKey(final String gpuDevice) {
        return new GpuBenchmarkStore.Key(
            GpuBenchmarkStore.WORKLOAD_VERSION,
            CpuIdentity.cpuBrand(),
            CpuIdentity.logicalProcessors(),
            gpuDevice,
            PredictorVersion.CFX_ABI,
            PredictorVersion.BASELINE_ALGO
        );
    }

    private static Supplier<GpuBackend.GpuStats> liveStats(final String device) {
        final GpuBackend.GpuStats stats =
            new GpuBackend.GpuStats(CubiomesNative.GPU_MODE_AUTO, device, 1, 1, false);
        return () -> stats;
    }

    @Test
    void verdictBoundaryAtExactly1_15GoesToGpu(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        // Per-tile sampling cost 23 ms vs 20 ms is exactly the 1.15x factor (single thread,
        // so the virtual wall clock equals the thread total).
        final TimedWorkload workload = new TimedWorkload(clock, 23, 20);
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(singleThreadContext(
            tmp.resolve("b.json"),
            new AtomicReference<>(PredictionBackend.AUTO),
            clock, workload, new Recording(), () -> 0, liveStats("RTX 4060")
        ));

        assertEquals(GpuBenchmark.Outcome.Result.VERDICT_MEASURED, outcome.result());
        assertEquals(PredictionBackend.GPU, GpuBackend.autoVerdict());
    }

    @Test
    void belowBoundaryPicksCpu(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final TimedWorkload workload = new TimedWorkload(clock, 22, 20); // 1.1x < 1.15x
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(singleThreadContext(
            tmp.resolve("b.json"),
            new AtomicReference<>(PredictionBackend.AUTO),
            clock, workload, new Recording(), () -> 0, liveStats("Radeon 780M")
        ));

        assertEquals(GpuBenchmark.Outcome.Result.VERDICT_MEASURED, outcome.result());
        assertEquals(PredictionBackend.CPU, GpuBackend.autoVerdict());
    }

    @Test
    void timeoutWritesNoCache(@TempDir final Path tmp) throws IOException {
        final SteppingClock clock = new SteppingClock();
        final Path file = tmp.resolve("b.json");
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(context(
            file, new AtomicReference<>(PredictionBackend.AUTO),
            clock, new TimedWorkload(clock, 10, 10), new Recording(),
            () -> 1, liveStats("RTX 4060") // build never lands
        ));

        assertEquals(GpuBenchmark.Outcome.Result.TIMEOUT_NO_BUILD, outcome.result());
        assertFalse(Files.exists(file), "a timed-out wait must not cache anything");
        assertNull(GpuBackend.autoVerdict());
    }

    @Test
    void warmupBoundedByTheSameCap(@TempDir final Path tmp) throws IOException {
        final SteppingClock clock = new SteppingClock();
        final Path file = tmp.resolve("b.json");
        // No warmup thread this boot (CPU config switched to AUTO mid-session), so the
        // benchmark itself becomes the builder: the warmup must not out-wait the cap.
        final TimedWorkload workload = new TimedWorkload(clock, 10, 10);
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(new GpuBenchmark.BenchmarkContext(
            new GpuBenchmarkStore(file, LOGGER),
            () -> PredictionBackend.AUTO,
            defaultWorkerCount(),
            new GpuBenchmark.Seams(
                clock, () -> true, () -> 0,
                () -> {
                    // A cold JIT that runs past the deadline.
                    clock.advance(GpuBenchmark.BUILD_WAIT_CAP_MILLIS + 1_000);
                    return 1;
                },
                liveStats("RTX 4060"),
                (mcVersion, seed, dim, flags) -> null, workload,
                threads -> runnable -> runnable.run(),
                mode -> {
                }, clock::advance),
            LOGGER
        ));

        assertEquals(GpuBenchmark.Outcome.Result.TIMEOUT_NO_BUILD, outcome.result());
        assertFalse(Files.exists(file), "an over-cap warmup must not cache anything");
        assertNull(GpuBackend.autoVerdict());
    }

    @Test
    void noDeviceCachesCpuVerdictWithNone(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final Path file = tmp.resolve("b.json");
        final TimedWorkload workload = new TimedWorkload(clock, 10, 10);
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(context(
            file, new AtomicReference<>(PredictionBackend.AUTO),
            clock, workload, new Recording(), () -> 0, liveStats("")
        ));

        assertEquals(GpuBenchmark.Outcome.Result.VERDICT_NO_DEVICE, outcome.result());
        final Optional<GpuBenchmarkStore.Entry> loaded =
            new GpuBenchmarkStore(file, LOGGER).load(expectedKey(GpuBenchmark.NO_DEVICE));
        assertTrue(loaded.isPresent(), "the none-device verdict must be cached");
        assertEquals(PredictionBackend.CPU, loaded.get().winner());
    }

    @Test
    void engineDeadIsFaultNotAVerdict(@TempDir final Path tmp) throws IOException {
        final SteppingClock clock = new SteppingClock();
        final Path file = tmp.resolve("b.json");
        final GpuBackend.GpuStats dead =
            new GpuBackend.GpuStats(CubiomesNative.GPU_MODE_AUTO, "RTX 4060", 1, 1, true);
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(context(
            file, new AtomicReference<>(PredictionBackend.AUTO),
            clock, new TimedWorkload(clock, 10, 10), new Recording(),
            () -> 0, () -> dead
        ));

        assertEquals(GpuBenchmark.Outcome.Result.FAULT, outcome.result());
        assertFalse(Files.exists(file), "a latched-dead engine is transient until proven otherwise");
    }

    @Test
    void faultInLegWritesNoCache(@TempDir final Path tmp) throws IOException {
        final SteppingClock clock = new SteppingClock();
        final Path file = tmp.resolve("b.json");
        final TimedWorkload workload = new TimedWorkload(clock, 10, 10);
        workload.cpuFails = true;
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(context(
            file, new AtomicReference<>(PredictionBackend.AUTO),
            clock, workload, new Recording(), () -> 0, liveStats("RTX 4060")
        ));

        assertEquals(GpuBenchmark.Outcome.Result.FAULT, outcome.result());
        assertFalse(Files.exists(file));
    }

    @Test
    void appliesVerdictOnlyWhenStillAuto(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final Recording recording = new Recording();
        final AtomicReference<PredictionBackend> configured = new AtomicReference<>(PredictionBackend.AUTO);
        final TimedWorkload workload = new TimedWorkload(clock, 10, 30); // CPU would win
        // The user flips the row to GPU right before the legs run; applying the CPU
        // verdict anyway would push the engine OFF against the explicit choice.
        final Supplier<GpuBackend.GpuStats> stats = new Supplier<>() {
            @Override
            public GpuBackend.GpuStats get() {
                configured.set(PredictionBackend.GPU);
                return liveStats("RTX 4060").get();
            }
        };
        // Single-thread context: the verdict under test is CPU (10 ms/tile beats 30), and
        // the multi-thread virtual clock cannot express the new per-tile metric.
        GpuBenchmark.run(singleThreadContext(
            tmp.resolve("b.json"), configured, clock, workload, recording, () -> 0, stats));

        assertEquals(PredictionBackend.CPU, GpuBackend.autoVerdict(), "the verdict is still stored for next boot");
        assertFalse(recording.modes.contains(CubiomesNative.GPU_MODE_OFF),
            "no engine mode may be pushed on behalf of an explicit user choice");
    }

    @Test
    void gpuLegRunsInsideForceWindowAndRestoresIt(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final Recording recording = new Recording();
        final TimedWorkload workload = new TimedWorkload(clock, 10, 5);
        GpuBenchmark.run(context(
            tmp.resolve("b.json"), new AtomicReference<>(PredictionBackend.AUTO),
            clock, workload, recording, () -> 0, liveStats("RTX 4060")
        ));

        assertTrue(recording.modes.contains(CubiomesNative.GPU_MODE_FORCE),
            "the GPU leg must run under force so no dispatch silently falls back to the CPU");
        assertEquals(
            CubiomesNative.GPU_MODE_AUTO,
            recording.modes.get(recording.modes.size() - 1).intValue(),
            "the user's engine mode must be restored after the force window"
        );
    }

    @Test
    void skipsWhenLibraryUnavailable(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final TimedWorkload workload = new TimedWorkload(clock, 10, 10);
        final GpuBenchmark.BenchmarkContext ctx = new GpuBenchmark.BenchmarkContext(
            new GpuBenchmarkStore(tmp.resolve("b.json"), LOGGER),
            () -> PredictionBackend.AUTO,
            defaultWorkerCount(),
            new GpuBenchmark.Seams(
                clock, () -> false, () -> 0, () -> 1, liveStats("RTX 4060"),
                (mcVersion, seed, dim, flags) -> null, workload,
                threads -> {
                    throw new AssertionError("no pool may be built without the library");
                },
                mode -> {
                }, millis -> {
                }
            ),
            LOGGER
        );

        assertEquals(GpuBenchmark.Outcome.Result.SKIPPED_NO_LIBRARY, GpuBenchmark.run(ctx).result());
    }

    @Test
    void skipsWhenPrewarmAlreadyApplied(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        GpuBackend.applyAutoVerdict(PredictionBackend.CPU);
        final GpuBenchmark.BenchmarkContext ctx = new GpuBenchmark.BenchmarkContext(
            new GpuBenchmarkStore(tmp.resolve("b.json"), LOGGER),
            () -> PredictionBackend.AUTO,
            defaultWorkerCount(),
            new GpuBenchmark.Seams(
                clock, () -> true,
                () -> {
                    throw new AssertionError("a prewarmed boot must not wait on the build");
                },
                () -> 1, liveStats("RTX 4060"),
                (mcVersion, seed, dim, flags) -> null, new TimedWorkload(clock, 10, 10),
                threads -> {
                    throw new AssertionError("no pool may be built after prewarm");
                },
                mode -> {
                }, millis -> {
                }
            ),
            LOGGER
        );

        assertEquals(GpuBenchmark.Outcome.Result.SKIPPED_PREWARMED, GpuBenchmark.run(ctx).result());
    }

    @Test
    void cacheHitAppliesWithoutLegs(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final Path file = tmp.resolve("b.json");
        final GpuBenchmarkStore store = new GpuBenchmarkStore(file, LOGGER);
        store.save(new GpuBenchmarkStore.Entry(
            expectedKey("RTX 4060"), PredictionBackend.CPU, 5, 1, clock.current()));

        final Recording recording = new Recording();
        final GpuBenchmark.Outcome outcome = GpuBenchmark.run(context(
            file, new AtomicReference<>(PredictionBackend.AUTO),
            clock, new TimedWorkload(clock, 10, 10), recording, () -> 0, liveStats("RTX 4060")
        ));

        assertEquals(GpuBenchmark.Outcome.Result.VERDICT_CACHED, outcome.result());
        assertEquals(PredictionBackend.CPU, GpuBackend.autoVerdict());
        assertEquals(0, recording.poolBuilds.get(), "a cache hit runs neither leg");
    }

    @Test
    void cpuLegUsesDefaultProductionWorkerCount(@TempDir final Path tmp) {
        final SteppingClock clock = new SteppingClock();
        final Recording recording = new Recording();
        GpuBenchmark.run(context(
            tmp.resolve("b.json"), new AtomicReference<>(PredictionBackend.AUTO),
            clock, new TimedWorkload(clock, 10, 10), recording, () -> 0, liveStats("RTX 4060")
        ));

        assertEquals(
            List.of(WorkerThreadPolicy.resolve(ConfluxConfig.WorkerThreadMode.AUTO, 0, TOPOLOGY)),
            recording.poolSizes,
            "the CPU leg must measure the default production pool, not the user's tuned pool"
        );
    }
}
