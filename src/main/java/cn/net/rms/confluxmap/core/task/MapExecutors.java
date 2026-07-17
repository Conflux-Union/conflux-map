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

    /** Blocks up to {@code timeoutMs} for queued IO (cache flushes) to finish. */
    public void shutdown(final long timeoutMs) {
        workers.shutdown();
        io.shutdown();
        try {
            io.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
