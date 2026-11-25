package cn.net.rms.confluxmap.nativepredict;

import java.util.Arrays;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.apache.logging.log4j.Logger;

/**
 * One-shot AUTO backend benchmark: measures this machine's two production forms - the CPU
 * worker pool sampling per-tile, and the single-threaded GPU wave batch - on a fixed tile
 * workload, and caches the winner keyed on hardware identity (see {@link GpuBenchmarkStore}).
 * Runs on a background daemon thread from the client startup; every ordinary startup takes
 * the cache-hit path and measures nothing.
 *
 * <p>Leg semantics: the CPU leg uses the {@code default} production worker count (the
 * AUTO-topology resolution), never the user's tuned pool, so the verdict answers "which
 * engine wins on this hardware in the default production form" and worker-config changes
 * do not invalidate it. The GPU leg runs inside a FORCE window so no dispatch silently
 * falls back to the CPU reference while being timed; a benchmark fault never degrades
 * production (no {@code NativeLib.disableForSession}, no batch-refusal latch) and simply
 * leaves the verdict undecided for the next boot. A cold OpenCL JIT outlasting {@link
 * #BUILD_WAIT_CAP_MILLIS} also leaves no verdict: the driver cache is warm by the next
 * boot, which then measures for real.
 */
public final class GpuBenchmark {
    /** Fixed sampling seed (the pinned E2E seed) - any stable value works, it only times. */
    public static final long SEED = 146008555L;

    /**
     * Tiles per leg. Sized so one round takes about a second on the dev GPU (RTX 4060
     * Laptop: ~15 ms per 16 tiles measured, noise-dominated at that size) and the batch
     * overhead is amortized; changing this or the per-tile shape bumps {@link
     * GpuBenchmarkStore#WORKLOAD_VERSION}.
     */
    public static final int TILE_COUNT = 1024;

    /**
     * GPU wins at cpuElapsed/gpuElapsed >= this factor, compared in integer percent to
     * avoid float-boundary flakiness; ties and near-ties go to CPU, the conservative side.
     */
    public static final int GPU_WIN_PERCENT = 115;

    /** Longest wait for the OpenCL build before giving up without a verdict. */
    public static final long BUILD_WAIT_CAP_MILLIS = 30_000;

    /** Cache identity for a machine whose engine probed no OpenCL device. */
    public static final String NO_DEVICE = "none";

    private static final int ROUNDS = 3;
    private static final long BUILD_POLL_MILLIS = 250;
    /**
     * Fixed benchmark identity: one supported 1.18+ Overworld generator (default preset).
     * DIM 0 and FLAGS 0 mirror {@code PredictionDimensions.OVERWORLD} and {@code
     * WorldPreset.DEFAULT.cubiomesFlags()} (core.predict values, kept literal here so this
     * class stays inside nativepredict's dependency direction).
     */
    static final int MC_VERSION = McVersions.toCubiomes("1.21").orElseThrow();
    static final int DIM = 0;
    static final int FLAGS = 0;

    /** Terminal outcome of one benchmark run, for tests and the startup log. */
    public record Outcome(
        Result result, PredictionBackend winner, double cpuMillisPerTile, double gpuMillisPerTile
    ) {
        public enum Result {
            SKIPPED_NO_LIBRARY,
            SKIPPED_PREWARMED,
            VERDICT_CACHED,
            VERDICT_NO_DEVICE,
            VERDICT_MEASURED,
            TIMEOUT_NO_BUILD,
            FAULT
        }
    }

    @FunctionalInterface
    public interface ContextFactory {
        CubiomesContext get(int mcVersion, long seed, int dim, int flags);
    }

    /** The fixed workload in both production forms; each call returns false on any failure. */
    public interface Workload {
        boolean cpuTile(CubiomesContext ctx, int tileIndex);

        boolean gpuBatch(CubiomesContext ctx, int firstTile, int tileCount);
    }

    /** Minimal pool seam; close stops the pool. */
    public interface TilePool {
        void execute(Runnable task);

        default void close() {
        }
    }

    @FunctionalInterface
    public interface PoolFactory {
        TilePool newPool(int threads);
    }

    @FunctionalInterface
    public interface ModeControl {
        void set(int mode);
    }

    @FunctionalInterface
    public interface Sleeper {
        void sleep(long millis) throws InterruptedException;
    }

    /** Every native/OS touchpoint, injectable for the orchestration tests. */
    public record Seams(
        LongSupplier clock,
        BooleanSupplier libraryReady,
        IntSupplier buildBusy,
        IntSupplier warmup,
        Supplier<GpuBackend.GpuStats> stats,
        ContextFactory contexts,
        Workload workload,
        PoolFactory pools,
        ModeControl mode,
        Sleeper sleeper
    ) {
    }

    public record BenchmarkContext(
        GpuBenchmarkStore store,
        Supplier<PredictionBackend> configured,
        int cpuLegThreads,
        Seams seams,
        Logger logger
    ) {
    }

    private static final AtomicBoolean STARTED = new AtomicBoolean();

    private GpuBenchmark() {
    }

    /** Spawns the one benchmark thread per process; further calls are no-ops. */
    public static void runAsync(final BenchmarkContext ctx) {
        if (!STARTED.compareAndSet(false, true)) {
            return;
        }
        final Thread worker = new Thread(() -> {
            try {
                run(ctx);
            } catch (final Throwable fault) {
                ctx.logger().warn("AUTO backend benchmark failed", fault);
            } finally {
                CubiomesContexts.closeAllOnThisThread();
            }
        }, "confluxmap-gpu-benchmark");
        worker.setDaemon(true);
        worker.start();
    }

    /** Synchronous core; package-private for tests. */
    static Outcome run(final BenchmarkContext ctx) {
        if (!ctx.seams().libraryReady().getAsBoolean()) {
            return new Outcome(Outcome.Result.SKIPPED_NO_LIBRARY, null, 0, 0);
        }
        // A prewarm-applied CPU verdict already decided this boot; the OFF engine never
        // probes a device, so there is nothing here to re-measure or re-validate.
        if (GpuBackend.autoVerdict() != null && ctx.configured().get() == PredictionBackend.AUTO) {
            return new Outcome(Outcome.Result.SKIPPED_PREWARMED, GpuBackend.autoVerdict(), 0, 0);
        }
        if (!ensureEngineReady(ctx)) {
            ctx.logger().info(
                "AUTO backend benchmark: OpenCL build still compiling after {} ms; no verdict this boot",
                BUILD_WAIT_CAP_MILLIS);
            return new Outcome(Outcome.Result.TIMEOUT_NO_BUILD, null, 0, 0);
        }
        final GpuBackend.GpuStats stats = ctx.seams().stats().get();
        if (stats == null || stats.engineDead()) {
            ctx.logger().info("AUTO backend benchmark: engine snapshot missing or latched dead; no verdict this boot");
            return new Outcome(Outcome.Result.FAULT, null, 0, 0);
        }
        final String device = stats.device() == null ? "" : stats.device().strip();
        final GpuBenchmarkStore store = ctx.store();
        final GpuBenchmarkStore.Key key = key(device.isEmpty() ? NO_DEVICE : device);
        final Optional<GpuBenchmarkStore.Entry> cached = store.load(key);
        if (cached.isPresent()) {
            apply(ctx, cached.get().winner());
            ctx.logger().info("AUTO backend verdict from cache: {} (device {})",
                cached.get().winner(), device.isEmpty() ? NO_DEVICE : device);
            return new Outcome(Outcome.Result.VERDICT_CACHED, cached.get().winner(), 0, 0);
        }
        if (device.isEmpty()) {
            store.save(new GpuBenchmarkStore.Entry(
                key, PredictionBackend.CPU, 0, 0, ctx.seams().clock().getAsLong()));
            apply(ctx, PredictionBackend.CPU);
            ctx.logger().info("AUTO backend verdict: no OpenCL device, serving CPU");
            return new Outcome(Outcome.Result.VERDICT_NO_DEVICE, PredictionBackend.CPU, 0, 0);
        }

        final int threads = Math.max(1, ctx.cpuLegThreads());
        final long cpuElapsed = cpuLeg(ctx, threads);
        if (cpuElapsed < 0) {
            ctx.logger().info("AUTO backend benchmark: CPU leg failed; no verdict this boot");
            return new Outcome(Outcome.Result.FAULT, null, 0, 0);
        }
        final long gpuElapsed = gpuLeg(ctx);
        if (gpuElapsed < 0) {
            ctx.logger().info("AUTO backend benchmark: GPU leg failed; no verdict this boot");
            return new Outcome(Outcome.Result.FAULT, null, 0, 0);
        }
        // The backends differ only in the sampling segment (compose/shade is shared), so
        // the discriminator is per-tile sampling cost: the CPU leg's thread-ms total
        // against the GPU leg's single-thread wall, over the same tile base.
        final double cpuMillisPerTile = elapsedMillisPerTile(cpuElapsed * threads);
        final double gpuMillisPerTile = elapsedMillisPerTile(gpuElapsed);
        final PredictionBackend winner =
            100L * cpuElapsed * threads >= (long) GPU_WIN_PERCENT * gpuElapsed
                ? PredictionBackend.GPU
                : PredictionBackend.CPU;
        store.save(new GpuBenchmarkStore.Entry(
            key, winner, cpuMillisPerTile, gpuMillisPerTile, ctx.seams().clock().getAsLong()));
        apply(ctx, winner);
        ctx.logger().info(
            "AUTO backend benchmark: cpu sampling {} ms/tile gpu {} ms/tile -> {} (device {})",
            String.format("%.2f", cpuMillisPerTile), String.format("%.2f", gpuMillisPerTile),
            winner, device);
        return new Outcome(Outcome.Result.VERDICT_MEASURED, winner, cpuMillisPerTile, gpuMillisPerTile);
    }

    /** Hardware-and-library identity the verdict is cached under. */
    public static GpuBenchmarkStore.Key key(final String gpuDevice) {
        return new GpuBenchmarkStore.Key(
            GpuBenchmarkStore.WORKLOAD_VERSION,
            CpuIdentity.cpuBrand(),
            CpuIdentity.logicalProcessors(),
            gpuDevice,
            PredictorVersion.CFX_ABI,
            PredictorVersion.BASELINE_ALGO
        );
    }

    private static void apply(final BenchmarkContext ctx, final PredictionBackend winner) {
        GpuBackend.applyAutoVerdict(winner);
        if (ctx.configured().get() == PredictionBackend.AUTO) {
            GpuBackend.configure(winner);
        }
    }

    /**
     * Waits for the engine's OpenCL build under the {@link #BUILD_WAIT_CAP_MILLIS} cap,
     * covering both wait forms: polling the busy flag while another thread builds, and the
     * case where nobody is building yet (a boot whose config was CPU never started the
     * warmup) - there the warmup runs on a helper daemon so a cold JIT cannot park this
     * thread past the cap; it keeps building in the background for the next boot.
     */
    private static boolean ensureEngineReady(final BenchmarkContext ctx) {
        final long deadline = ctx.seams().clock().getAsLong() + BUILD_WAIT_CAP_MILLIS;
        while (ctx.seams().buildBusy().getAsInt() != 0) {
            if (ctx.seams().clock().getAsLong() >= deadline) {
                return false;
            }
            try {
                ctx.seams().sleeper().sleep(BUILD_POLL_MILLIS);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        final Thread warmup = new Thread(
            () -> ctx.seams().warmup().getAsInt(), "confluxmap-gpu-benchmark-warmup");
        warmup.setDaemon(true);
        warmup.start();
        try {
            warmup.join(Math.max(1L, deadline - ctx.seams().clock().getAsLong()));
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return ctx.seams().clock().getAsLong() < deadline;
    }

    /**
     * CPU leg: the default production worker count samples per-tile through this thread's
     * context, tiles interleaved across threads. Returns the median round elapsed in
     * millis, or -1 on any tile failure.
     */
    private static long cpuLeg(final BenchmarkContext ctx, final int threads) {
        final TilePool pool = ctx.seams().pools().newPool(threads);
        final long[] elapsed = new long[ROUNDS];
        try {
            for (int round = 0; round < ROUNDS; round++) {
                final AtomicBoolean ok = new AtomicBoolean(true);
                final CountDownLatch done = new CountDownLatch(threads);
                final long start = ctx.seams().clock().getAsLong();
                for (int worker = 0; worker < threads; worker++) {
                    final int offset = worker;
                    pool.execute(() -> {
                        try {
                            final CubiomesContext threadCtx =
                                ctx.seams().contexts().get(MC_VERSION, SEED, DIM, FLAGS);
                            for (int tile = offset; tile < TILE_COUNT; tile += threads) {
                                if (!ctx.seams().workload().cpuTile(threadCtx, tile)) {
                                    ok.set(false);
                                }
                            }
                        } catch (final Throwable fault) {
                            // The benchmark must never degrade production through the
                            // shared latches; a leg fault is just no verdict this boot.
                            ok.set(false);
                        } finally {
                            CubiomesContexts.closeAllOnThisThread();
                            done.countDown();
                        }
                    });
                }
                await(done);
                elapsed[round] = ctx.seams().clock().getAsLong() - start;
                if (!ok.get() || elapsed[round] <= 0) {
                    return -1;
                }
            }
        } finally {
            pool.close();
        }
        return median(elapsed);
    }

    /** GPU leg: one thread, FORCE window around the whole measurement. -1 on failure. */
    private static long gpuLeg(final BenchmarkContext ctx) {
        ctx.seams().mode().set(CubiomesNative.GPU_MODE_FORCE);
        try {
            final long[] elapsed = new long[ROUNDS];
            for (int round = 0; round < ROUNDS; round++) {
                final long start = ctx.seams().clock().getAsLong();
                final CubiomesContext leader =
                    ctx.seams().contexts().get(MC_VERSION, SEED, DIM, FLAGS);
                if (!ctx.seams().workload().gpuBatch(leader, 0, TILE_COUNT)) {
                    return -1;
                }
                elapsed[round] = ctx.seams().clock().getAsLong() - start;
                if (elapsed[round] <= 0) {
                    return -1;
                }
            }
            return median(elapsed);
        } finally {
            ctx.seams().mode().set(GpuBackend.engineMode(ctx.configured().get()));
        }
    }

    private static void await(final CountDownLatch done) {
        try {
            done.await();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static double elapsedMillisPerTile(final long elapsedMillis) {
        return elapsedMillis <= 0 ? 0 : (double) elapsedMillis / TILE_COUNT;
    }

    private static long median(final long[] values) {
        final long[] sorted = values.clone();
        Arrays.sort(sorted);
        return sorted[sorted.length / 2];
    }

    /** Production seams: the real natives (optional exports degrade), wall clock, real pool. */
    public static Seams productionSeams() {
        return new Seams(
            System::currentTimeMillis,
            NativeLib::available,
            () -> {
                try {
                    return CubiomesNative.cfxGpuBuildBusy();
                } catch (final UnsatisfiedLinkError staleLibrary) {
                    return 0;
                }
            },
            () -> {
                try {
                    return CubiomesNative.cfxGpuWarmup();
                } catch (final UnsatisfiedLinkError staleLibrary) {
                    return -1;
                }
            },
            GpuBackend::stats,
            CubiomesContexts::get,
            new Lod2TileWorkload(),
            threads -> {
                final ExecutorService pool = Executors.newFixedThreadPool(threads, task -> {
                    final Thread worker = new Thread(task, "confluxmap-gpu-benchmark-cpu");
                    worker.setDaemon(true);
                    return worker;
                });
                return new TilePool() {
                    @Override
                    public void execute(final Runnable task) {
                        pool.execute(task);
                    }

                    @Override
                    public void close() {
                        pool.shutdownNow();
                    }
                };
            },
            CubiomesNative::cfxSetGpuMode,
            Thread::sleep
        );
    }
}
