package cn.net.rms.confluxmap.paper;

import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/** Runs asynchronous work while publishing only the newest submitted generation. */
final class PaperSupersedingTask<T> {
    private final Executor executor;
    private Callable<T> pending;
    private T completed;
    private Exception failure;
    private boolean scheduled;

    PaperSupersedingTask(final Executor executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    synchronized boolean submit(final Callable<T> job) {
        Objects.requireNonNull(job, "job");
        pending = job;
        completed = null;
        failure = null;
        if (scheduled) {
            return true;
        }
        scheduled = true;
        try {
            executor.execute(this::runLatest);
            return true;
        } catch (final RejectedExecutionException e) {
            scheduled = false;
            pending = null;
            return false;
        }
    }

    synchronized T completed() {
        return completed;
    }

    synchronized Exception failure() {
        return failure;
    }

    private void runLatest() {
        while (true) {
            final Callable<T> job;
            synchronized (this) {
                job = pending;
                pending = null;
            }

            T result = null;
            Exception problem = null;
            try {
                result = job.call();
            } catch (final Exception e) {
                problem = e;
            }

            synchronized (this) {
                if (pending != null) {
                    continue;
                }
                scheduled = false;
                if (problem != null) {
                    failure = problem;
                    return;
                }
                completed = result;
                return;
            }
        }
    }
}
