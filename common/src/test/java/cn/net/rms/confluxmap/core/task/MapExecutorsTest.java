package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MapExecutorsTest {
    @Test
    void workerCountIsHonoredWithAMinimumOfOne() {
        final MapExecutors four = new MapExecutors(4);
        final MapExecutors none = new MapExecutors(0);

        assertEquals(4, four.workerCount());
        assertEquals(1, none.workerCount());

        four.shutdown(100L);
        none.shutdown(100L);
    }

    @Test
    void shutdownLetsQueuedWorkerTasksFinishSubmittingIoBeforeIoCloses() throws Exception {
        final MapExecutors executors = new MapExecutors(2);
        final CountDownLatch workerStarted = new CountDownLatch(1);
        final CountDownLatch releaseWorker = new CountDownLatch(1);
        final CountDownLatch ioFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> workerFailure = new AtomicReference<>();
        executors.workers().execute(() -> {
            workerStarted.countDown();
            try {
                releaseWorker.await();
                executors.io().execute(ioFinished::countDown);
            } catch (final Throwable failure) {
                workerFailure.set(failure);
            }
        });
        assertTrue(workerStarted.await(1, TimeUnit.SECONDS));

        final Thread shutdown = new Thread(() -> executors.shutdown(2_000L));
        shutdown.start();
        awaitPoolShutdown(executors.workers());
        releaseWorker.countDown();
        shutdown.join(3_000L);

        assertNull(workerFailure.get());
        assertTrue(ioFinished.await(1, TimeUnit.SECONDS));
    }

    @Test
    void scanWorkRunsOnDedicatedThreads() throws Exception {
        final MapExecutors executors = new MapExecutors(2);
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final AtomicReference<String> threadName = new AtomicReference<>();
            executors.scans().execute(() -> {
                threadName.set(Thread.currentThread().getName());
                done.countDown();
            });
            assertTrue(done.await(2, TimeUnit.SECONDS));
            assertTrue(threadName.get().startsWith("ConfluxMap-Scan"),
                "scan work must not share the worker pool, ran on " + threadName.get());
        } finally {
            executors.shutdown(1_000L);
        }
    }

    @Test
    void shutdownDrainsScanWorkBeforeIoCloses() throws Exception {
        final MapExecutors executors = new MapExecutors(2);
        final CountDownLatch scanStarted = new CountDownLatch(1);
        final CountDownLatch releaseScan = new CountDownLatch(1);
        final CountDownLatch ioFinished = new CountDownLatch(1);
        final AtomicReference<Throwable> scanFailure = new AtomicReference<>();
        executors.scans().execute(() -> {
            scanStarted.countDown();
            try {
                releaseScan.await();
                executors.io().execute(ioFinished::countDown);
            } catch (final Throwable failure) {
                scanFailure.set(failure);
            }
        });
        assertTrue(scanStarted.await(1, TimeUnit.SECONDS));

        final Thread shutdown = new Thread(() -> executors.shutdown(2_000L));
        shutdown.start();
        awaitPoolShutdown(executors.scans());
        releaseScan.countDown();
        shutdown.join(3_000L);

        assertNull(scanFailure.get());
        assertTrue(ioFinished.await(1, TimeUnit.SECONDS));
    }

    private static void awaitPoolShutdown(final java.util.concurrent.ExecutorService pool)
        throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!pool.isShutdown() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(pool.isShutdown());
    }
}
