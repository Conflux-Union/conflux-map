package cn.net.rms.confluxmap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PaperProgressiveSchedulerTest {
    @Test
    void fourWatchedTilesAllAdvanceWithinFourWorkerSteps() {
        final PaperProgressiveScheduler<Integer, Task> scheduler =
            new PaperProgressiveScheduler<>();
        final Map<Integer, Task> tasks = new LinkedHashMap<>();
        for (int key = 0; key < 4; key++) {
            tasks.put(key, new Task());
        }

        for (int step = 0; step < 4; step++) {
            scheduler.tick(tasks, ignored -> true, Task::complete, Task::advance);
        }

        assertEquals(1, tasks.get(0).steps);
        assertEquals(1, tasks.get(1).steps);
        assertEquals(1, tasks.get(2).steps);
        assertEquals(1, tasks.get(3).steps);
    }

    @Test
    void visibleTasksRemainPreferredOverBackgroundTasks() {
        final PaperProgressiveScheduler<Integer, Task> scheduler =
            new PaperProgressiveScheduler<>();
        final Map<Integer, Task> tasks = new LinkedHashMap<>();
        tasks.put(0, new Task());
        tasks.put(1, new Task());
        tasks.put(2, new Task());

        for (int step = 0; step < 4; step++) {
            scheduler.tick(tasks, key -> key < 2, Task::complete, Task::advance);
        }

        assertEquals(2, tasks.get(0).steps);
        assertEquals(2, tasks.get(1).steps);
        assertEquals(0, tasks.get(2).steps);
    }

    @Test
    void completedTasksAreSkippedWithoutBreakingRoundRobinOrder() {
        final PaperProgressiveScheduler<Integer, Task> scheduler =
            new PaperProgressiveScheduler<>();
        final Map<Integer, Task> tasks = new LinkedHashMap<>();
        tasks.put(0, new Task());
        tasks.put(1, new Task(true));
        tasks.put(2, new Task());

        scheduler.tick(tasks, ignored -> true, Task::complete, Task::advance);
        scheduler.tick(tasks, ignored -> true, Task::complete, Task::advance);

        assertEquals(1, tasks.get(0).steps);
        assertEquals(0, tasks.get(1).steps);
        assertEquals(1, tasks.get(2).steps);
    }

    private static final class Task {
        private final boolean complete;
        private int steps;

        Task() {
            this(false);
        }

        Task(final boolean complete) {
            this.complete = complete;
        }

        boolean complete() {
            return complete;
        }

        void advance() {
            steps++;
        }
    }
}
