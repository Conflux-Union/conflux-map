package cn.net.rms.confluxmap.core.task;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class MapExecutorsTest {
    @Test
    void shutdownLetsQueuedWorkerTasksFinishSubmittingIoBeforeIoCloses() throws Exception {
        final MapExecutors executors = new MapExecutors();
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
        awaitWorkerShutdown(executors);
        releaseWorker.countDown();
        shutdown.join(3_000L);

        assertNull(workerFailure.get());
        assertTrue(ioFinished.await(1, TimeUnit.SECONDS));
    }

    private static void awaitWorkerShutdown(final MapExecutors executors) throws InterruptedException {
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (!executors.workers().isShutdown() && System.nanoTime() < deadline) {
            Thread.sleep(1L);
        }
        assertTrue(executors.workers().isShutdown());
    }
}
