package cn.net.rms.confluxmap.core.trail;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/** Bounded recent player positions for the minimap and fullscreen-map trail overlays. */
public final class PlayerTrail {
    private static final double MIN_SAMPLE_DISTANCE_SQUARED = 0.25;
    private static final int MAX_SAMPLES = 4096;

    private final Deque<Sample> samples = new ArrayDeque<>();
    private boolean hasLastPosition;
    private double lastX;
    private double lastZ;

    public synchronized void record(
        final double x,
        final double z,
        final long recordedAtNanos,
        final long retentionNanos
    ) {
        validate(x, z, retentionNanos);
        prune(recordedAtNanos, retentionNanos);

        if (hasLastPosition) {
            final double dx = x - lastX;
            final double dz = z - lastZ;
            if (dx * dx + dz * dz < MIN_SAMPLE_DISTANCE_SQUARED) {
                return;
            }
        }

        samples.addLast(new Sample(x, z, recordedAtNanos));
        hasLastPosition = true;
        lastX = x;
        lastZ = z;
        while (samples.size() > MAX_SAMPLES) {
            samples.removeFirst();
        }
    }

    public synchronized List<Sample> snapshot(final long nowNanos, final long retentionNanos) {
        if (retentionNanos <= 0L) {
            throw new IllegalArgumentException("retention must be positive");
        }
        prune(nowNanos, retentionNanos);
        return List.copyOf(samples);
    }

    public synchronized void clear() {
        samples.clear();
        hasLastPosition = false;
    }

    private void prune(final long nowNanos, final long retentionNanos) {
        while (!samples.isEmpty() && nowNanos - samples.getFirst().recordedAtNanos() > retentionNanos) {
            samples.removeFirst();
        }
    }

    private static void validate(final double x, final double z, final long retentionNanos) {
        if (!Double.isFinite(x) || !Double.isFinite(z)) {
            throw new IllegalArgumentException("trail coordinates must be finite");
        }
        if (retentionNanos <= 0L) {
            throw new IllegalArgumentException("retention must be positive");
        }
    }

    public record Sample(double x, double z, long recordedAtNanos) {
    }
}
