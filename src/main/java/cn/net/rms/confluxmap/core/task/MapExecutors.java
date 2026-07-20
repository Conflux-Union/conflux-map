package cn.net.rms.confluxmap.core.task;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Thread pools owned by the mod: a small worker pool for sampling/compositing
 * and one IO thread for disk reads/writes. All threads are daemons so a hung
 * task can never block game shutdown.
 */
public final class MapExecutors {
    private final ExecutorService workers;
    private final ExecutorService io;
    private final int workerCount;

    public MapExecutors() {
        workerCount = Math.max(1, Math.min(3, Runtime.getRuntime().availableProcessors() / 2 - 1));
        workers = Executors.newFixedThreadPool(workerCount, factory("ConfluxMap-Worker"));
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

    public ExecutorService io() {
        return io;
    }

    public int workerCount() {
        return workerCount;
    }

    /**
     * Stops accepting worker jobs, drains them while IO remains open, then drains queued IO. This
     * ordering lets an already-accepted worker finish scheduling its persistence work.
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
