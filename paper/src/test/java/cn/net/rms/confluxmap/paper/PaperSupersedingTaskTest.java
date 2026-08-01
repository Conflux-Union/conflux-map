package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PaperSupersedingTaskTest {
    @Test
    void repeatedRequestsKeepOnlyOneWorkerTaskAndPublishTheNewestResult() {
        final QueuedExecutor worker = new QueuedExecutor();
        final AtomicInteger executions = new AtomicInteger();
        final PaperSupersedingTask<Integer> task = new PaperSupersedingTask<>(worker);

        for (int request = 0; request < 100; request++) {
            final int value = request;
            assertTrue(task.submit(() -> {
                executions.incrementAndGet();
                return value;
            }));
        }

        assertEquals(1, worker.queued());
        worker.runNext();
        assertEquals(99, task.completed());
        assertEquals(1, executions.get());
    }

    @Test
    void requestSubmittedDuringWorkRunsImmediatelyAfterTheCurrentJob() {
        final QueuedExecutor worker = new QueuedExecutor();
        final AtomicInteger executions = new AtomicInteger();
        @SuppressWarnings("unchecked")
        final PaperSupersedingTask<Integer>[] holder = new PaperSupersedingTask[1];
        holder[0] = new PaperSupersedingTask<>(worker);

        assertTrue(holder[0].submit(() -> {
            executions.incrementAndGet();
            assertTrue(holder[0].submit(() -> {
                executions.incrementAndGet();
                return 2;
            }));
            return 1;
        }));

        worker.runNext();

        assertEquals(2, holder[0].completed());
        assertEquals(2, executions.get());
        assertEquals(0, worker.queued());
    }

    private static final class QueuedExecutor implements Executor {
        private final ArrayDeque<Runnable> queue = new ArrayDeque<>();

        @Override
        public void execute(final Runnable command) {
            queue.addLast(command);
        }

        int queued() {
            return queue.size();
        }

        void runNext() {
            queue.removeFirst().run();
        }
    }
}
