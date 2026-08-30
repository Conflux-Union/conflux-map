package cn.net.rms.confluxmap.core.task;

import java.util.function.LongSupplier;

/**
 * Bounds one client tick's synchronous visible-terrain sampling. Work may burst while it stays
 * inside a short wall-clock slice, with both a configured minimum and a hard snapshot cap.
 */
public final class CaptureTickBudget {
    static final long VISIBLE_NANOS = 12_000_000L;
    static final int MAX_VISIBLE_SNAPSHOTS = 256;

    private final int minimumSnapshots;
    private final int maximumSnapshots;
    private final long deadlineNanos;
    private final LongSupplier nanoTime;

    private CaptureTickBudget(
        final int minimumSnapshots,
        final int maximumSnapshots,
        final long deadlineNanos,
        final LongSupplier nanoTime
    ) {
        this.minimumSnapshots = minimumSnapshots;
        this.maximumSnapshots = maximumSnapshots;
        this.deadlineNanos = deadlineNanos;
        this.nanoTime = nanoTime;
    }

    public static CaptureTickBudget visible(
        final int configuredSnapshots, final LongSupplier nanoTime
    ) {
        final int minimum = Math.min(
            MAX_VISIBLE_SNAPSHOTS, Math.max(1, configuredSnapshots)
        );
        final long started = nanoTime.getAsLong();
        return new CaptureTickBudget(
            minimum,
            MAX_VISIBLE_SNAPSHOTS,
            started + VISIBLE_NANOS,
            nanoTime
        );
    }

    public boolean canCapture(final int capturedSnapshots) {
        return canCapture(capturedSnapshots, false);
    }

    public boolean canCapture(
        final int capturedSnapshots, final boolean tickAlreadyWorked
    ) {
        final int minimum = tickAlreadyWorked ? 0 : minimumSnapshots;
        return capturedSnapshots < maximumSnapshots
            && (capturedSnapshots < minimum || nanoTime.getAsLong() < deadlineNanos);
    }

    public boolean canFinish(final int finishedResults) {
        return finishedResults < maximumSnapshots
            && (finishedResults == 0 || nanoTime.getAsLong() < deadlineNanos);
    }

    /** Largest candidate batch worth sorting/draining for this tick. */
    public int maximumCandidates() {
        return maximumSnapshots;
    }
}
