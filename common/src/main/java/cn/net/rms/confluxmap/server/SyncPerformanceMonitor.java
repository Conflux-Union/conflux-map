package cn.net.rms.confluxmap.server;

import cn.net.rms.confluxmap.core.net.Proto;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Aggregates one player's completed correction-tile syncs for the current connection. */
public final class SyncPerformanceMonitor {
    private static final long ATTEMPT_IDLE_TIMEOUT_NANOS = 30_000_000_000L;

    /** Server work performed directly while building one response. */
    public record DirectWork(long ioNanos, long computeNanos) {
        public static final DirectWork NONE = new DirectWork(0L, 0L);

        public DirectWork {
            ioNanos = Math.max(0L, ioNanos);
            computeNanos = Math.max(0L, computeNanos);
        }
    }

    /** Monotonic work counters for one shared progressive tile task. */
    public record CumulativeWork(
        long workId,
        long startedNanos,
        long ioNanos,
        long computeNanos
    ) {
        public static final CumulativeWork NONE = new CumulativeWork(0L, 0L, 0L, 0L);

        public CumulativeWork {
            ioNanos = Math.max(0L, ioNanos);
            computeNanos = Math.max(0L, computeNanos);
        }

        public boolean present() {
            return workId > 0L;
        }
    }

    /** One delivered MAP_PATCH and the server-side measurements that produced it. */
    public record Delivery(
        int dimIndex,
        int lod,
        int tileX,
        int tileZ,
        long requestReceivedNanos,
        int requestBytes,
        int patchMode,
        int responseBytes,
        long queueNanos,
        long encodeNanos,
        long deliveredNanos,
        DirectWork directWork,
        CumulativeWork cumulativeWork
    ) {
        public Delivery {
            requestBytes = Math.max(0, requestBytes);
            responseBytes = Math.max(0, responseBytes);
            queueNanos = Math.max(0L, queueNanos);
            encodeNanos = Math.max(0L, encodeNanos);
            directWork = directWork == null ? DirectWork.NONE : directWork;
            cumulativeWork = cumulativeWork == null ? CumulativeWork.NONE : cumulativeWork;
        }
    }

    /** Per-completed-sync-item averages for one LOD. */
    public record LodSnapshot(
        int lod,
        long samples,
        long averageTotalNanos,
        long averageQueueNanos,
        long averageIoNanos,
        long averageComputeNanos,
        long averageEncodeNanos,
        long averageTrafficBytes
    ) {
        public long averageOtherNanos() {
            final long classified = averageQueueNanos + averageIoNanos
                + averageComputeNanos + averageEncodeNanos;
            return Math.max(0L, averageTotalNanos - classified);
        }
    }

    private record TileKey(int dimIndex, int lod, int tileX, int tileZ) {
    }

    private static final class Attempt {
        long startedNanos;
        long lastDeliveredNanos;
        long queueNanos;
        long ioNanos;
        long computeNanos;
        long encodeNanos;
        long trafficBytes;
        final Map<Long, CumulativeWork> workCursors = new HashMap<>();

        Attempt(final long startedNanos) {
            this.startedNanos = startedNanos;
        }
    }

    private static final class Totals {
        long samples;
        long totalNanos;
        long queueNanos;
        long ioNanos;
        long computeNanos;
        long encodeNanos;
        long trafficBytes;

        LodSnapshot snapshot(final int lod) {
            return new LodSnapshot(
                lod,
                samples,
                totalNanos / samples,
                queueNanos / samples,
                ioNanos / samples,
                computeNanos / samples,
                encodeNanos / samples,
                trafficBytes / samples
            );
        }
    }

    private final Map<TileKey, Attempt> active = new HashMap<>();
    private final Map<Integer, Totals> totalsByLod = new TreeMap<>();

    public synchronized void record(final Delivery delivery) {
        final TileKey key = new TileKey(
            delivery.dimIndex(), delivery.lod(), delivery.tileX(), delivery.tileZ()
        );
        Attempt attempt = active.get(key);
        if (attempt == null || delivery.requestReceivedNanos() - attempt.lastDeliveredNanos
            > ATTEMPT_IDLE_TIMEOUT_NANOS) {
            attempt = new Attempt(delivery.requestReceivedNanos());
            active.put(key, attempt);
        }
        attempt.startedNanos = Math.min(attempt.startedNanos, delivery.requestReceivedNanos());
        attempt.lastDeliveredNanos = delivery.deliveredNanos();
        attempt.queueNanos += delivery.queueNanos();
        attempt.encodeNanos += delivery.encodeNanos();
        attempt.trafficBytes += (long) delivery.requestBytes() + delivery.responseBytes();
        attempt.ioNanos += delivery.directWork().ioNanos();
        attempt.computeNanos += delivery.directWork().computeNanos();
        recordCumulative(attempt, delivery.cumulativeWork());

        if (delivery.patchMode() == Proto.PATCH_MODE_PARTIAL) {
            return;
        }

        active.remove(key);
        final Totals totals = totalsByLod.computeIfAbsent(delivery.lod(), ignored -> new Totals());
        totals.samples++;
        totals.totalNanos += Math.max(0L, delivery.deliveredNanos() - attempt.startedNanos);
        totals.queueNanos += attempt.queueNanos;
        totals.ioNanos += attempt.ioNanos;
        totals.computeNanos += attempt.computeNanos;
        totals.encodeNanos += attempt.encodeNanos;
        totals.trafficBytes += attempt.trafficBytes;
    }

    public synchronized List<LodSnapshot> snapshots() {
        final List<LodSnapshot> result = new ArrayList<>(totalsByLod.size());
        for (final Map.Entry<Integer, Totals> entry : totalsByLod.entrySet()) {
            if (entry.getValue().samples > 0L) {
                result.add(entry.getValue().snapshot(entry.getKey()));
            }
        }
        return List.copyOf(result);
    }

    private static void recordCumulative(final Attempt attempt, final CumulativeWork current) {
        if (!current.present()) {
            return;
        }
        final CumulativeWork previous = attempt.workCursors.put(current.workId(), current);
        if (previous != null) {
            attempt.ioNanos += Math.max(0L, current.ioNanos() - previous.ioNanos());
            attempt.computeNanos += Math.max(0L, current.computeNanos() - previous.computeNanos());
        } else if (current.startedNanos() >= attempt.startedNanos) {
            attempt.ioNanos += current.ioNanos();
            attempt.computeNanos += current.computeNanos();
        }
    }
}
