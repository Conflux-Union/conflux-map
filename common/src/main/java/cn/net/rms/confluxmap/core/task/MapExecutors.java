package cn.net.rms.confluxmap.core.task;

import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pools owned by the mod: a bounded worker pool for sampling/compositing,
 * a small scan pool for multi-second region-file backfill scans, and one IO
 * thread for disk reads/writes. All threads are daemons so a hung task can
 * never block game shutdown.
 */
public final class MapExecutors {
    private static final int SCAN_THREADS = 2;

    private final ExecutorService workers;
    private final ExecutorService scans;
    private final ExecutorService io;
    private final int workerCount;

    /**
     * Pool sized by the AUTO worker-thread policy; the production wiring passes the
     * user's chosen mode explicitly, and this constructor stays for tests and tools
     * that just want the default behavior.
     */
    public MapExecutors() {
        this(WorkerThreadPolicy.resolve(
            ConfluxConfig.WorkerThreadMode.AUTO,
            ConfluxConfig.DEFAULT_CUSTOM_WORKER_THREADS,
            CpuTopologyDetector.defaults().detect()
        ));
    }

    /**
     * @param workerCount worker-pool size; the caller decides it (see
     *     {@link WorkerThreadPolicy}) and the constructor only enforces a minimum of 1
     */
    public MapExecutors(final int workerCount) {
        this.workerCount = Math.max(1, workerCount);
        workers = Executors.newFixedThreadPool(this.workerCount, factory("ConfluxMap-Worker"));
        scans = Executors.newFixedThreadPool(SCAN_THREADS, factory("ConfluxMap-Scan"));
        io = Executors.newSingleThreadExecutor(factory("ConfluxMap-IO"));
    }

    private static ThreadFactory factory(final String prefix) {
        final AtomicInteger n = new AtomicInteger();
        return runnable -> {
            final Thread thread = new Thread(runnable, prefix + "-" + n.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }

    public ExecutorService workers() {
        return workers;
    }

    public ExecutorService scans() {
        return scans;
    }

    public ExecutorService io() {
        return io;
    }

    public int workerCount() {
        return workerCount;
    }

    /**
     * Stops accepting worker and scan jobs, drains them while IO remains open, then drains queued
     * IO. This ordering lets an already-accepted task finish scheduling its persistence work.
     */
    public void shutdown(final long timeoutMs) {
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(Math.max(0L, timeoutMs));
        boolean interrupted = false;
        workers.shutdown();
        try {
            workers.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            interrupted = true;
        }
        scans.shutdown();
        try {
            scans.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            interrupted = true;
        }
        io.shutdown();
        try {
            io.awaitTermination(remainingNanos(deadline), TimeUnit.NANOSECONDS);
        } catch (final InterruptedException e) {
            interrupted = true;
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static long remainingNanos(final long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }
}
