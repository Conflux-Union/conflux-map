package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import cn.net.rms.confluxmap.core.net.Proto;
import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPerformanceMonitorTest {
    @Test
    void partialResponsesBecomeOneCompletedTileSample() {
        final SyncPerformanceMonitor monitor = new SyncPerformanceMonitor();
        final SyncPerformanceMonitor.CumulativeWork task = new SyncPerformanceMonitor.CumulativeWork(
            7L, 110L, 20L, 30L
        );

        monitor.record(delivery(3, 100L, 200L, 11, 101, 5L, 3L, task));
        monitor.record(delivery(3, 300L, 400L, 13, 103, 7L, 5L,
            new SyncPerformanceMonitor.CumulativeWork(7L, 110L, 50L, 70L)));
        monitor.record(delivery(3, 500L, 700L, 17, 107, 11L, 7L,
            new SyncPerformanceMonitor.CumulativeWork(7L, 110L, 80L, 100L),
            Proto.PATCH_MODE_ABSOLUTE));

        assertEquals(
            new SyncPerformanceMonitor.LodSnapshot(3, 1L, 600L, 23L, 80L, 100L, 15L, 352L),
            monitor.snapshots().get(0)
        );
    }

    @Test
    void reusedProgressiveWorkCountsOnlyWorkAfterThisPlayersFirstResponse() {
        final SyncPerformanceMonitor monitor = new SyncPerformanceMonitor();

        monitor.record(delivery(4, 100L, 200L, 10, 90, 2L, 3L,
            new SyncPerformanceMonitor.CumulativeWork(9L, 1L, 1_000L, 2_000L)));
        monitor.record(delivery(4, 300L, 500L, 10, 90, 4L, 5L,
            new SyncPerformanceMonitor.CumulativeWork(9L, 1L, 1_030L, 2_050L),
            Proto.PATCH_MODE_UNCHANGED));

        assertEquals(
            new SyncPerformanceMonitor.LodSnapshot(4, 1L, 400L, 6L, 30L, 50L, 8L, 200L),
            monitor.snapshots().get(0)
        );
    }

    @Test
    void completedTilesAreAveragedWithinTheirOwnLod() {
        final SyncPerformanceMonitor monitor = new SyncPerformanceMonitor();
        monitor.record(delivery(0, 0L, 100L, 20, 80, 10L, 4L,
            SyncPerformanceMonitor.CumulativeWork.NONE, Proto.PATCH_MODE_ABSOLUTE,
            new SyncPerformanceMonitor.DirectWork(30L, 20L)));
        monitor.record(delivery(0, 200L, 500L, 40, 160, 30L, 6L,
            SyncPerformanceMonitor.CumulativeWork.NONE, Proto.PATCH_MODE_UNCHANGED,
            new SyncPerformanceMonitor.DirectWork(50L, 70L)));
        monitor.record(delivery(2, 600L, 1_000L, 50, 250, 20L, 10L,
            SyncPerformanceMonitor.CumulativeWork.NONE, Proto.PATCH_MODE_UNAVAILABLE,
            new SyncPerformanceMonitor.DirectWork(90L, 110L)));

        assertEquals(List.of(
            new SyncPerformanceMonitor.LodSnapshot(0, 2L, 200L, 20L, 40L, 45L, 5L, 150L),
            new SyncPerformanceMonitor.LodSnapshot(2, 1L, 400L, 20L, 90L, 110L, 10L, 300L)
        ), monitor.snapshots());
    }

    @Test
    void regionDeliveryCombinesPageAndBaselineServerWork() {
        final SyncPerformanceMonitor monitor = new SyncPerformanceMonitor();

        monitor.record(delivery(
            2, 100L, 500L, 40, 160, 20L, 10L,
            new SyncPerformanceMonitor.CumulativeWork(3L, 120L, 50L, 60L),
            Proto.PATCH_MODE_ABSOLUTE,
            new SyncPerformanceMonitor.DirectWork(0L, 70L)
        ));

        assertEquals(
            new SyncPerformanceMonitor.LodSnapshot(
                2, 1L, 400L, 20L, 50L, 130L, 10L, 200L
            ),
            monitor.snapshots().get(0)
        );
    }

    @Test
    void abandonedPartialAttemptDoesNotPolluteALaterSync() {
        final SyncPerformanceMonitor monitor = new SyncPerformanceMonitor();
        monitor.record(delivery(
            3, 0L, 10L, 20, 80, 1L, 1L, SyncPerformanceMonitor.CumulativeWork.NONE
        ));

        monitor.record(delivery(
            3, 31_000_000_000L, 31_000_000_100L, 30, 90, 2L, 3L,
            SyncPerformanceMonitor.CumulativeWork.NONE, Proto.PATCH_MODE_UNCHANGED
        ));

        assertEquals(
            new SyncPerformanceMonitor.LodSnapshot(3, 1L, 100L, 2L, 0L, 0L, 3L, 120L),
            monitor.snapshots().get(0)
        );
    }

    private static SyncPerformanceMonitor.Delivery delivery(
        final int lod,
        final long receivedAtNanos,
        final long deliveredAtNanos,
        final int requestBytes,
        final int responseBytes,
        final long queueNanos,
        final long encodeNanos,
        final SyncPerformanceMonitor.CumulativeWork cumulativeWork
    ) {
        return delivery(
            lod, receivedAtNanos, deliveredAtNanos, requestBytes, responseBytes,
            queueNanos, encodeNanos, cumulativeWork, Proto.PATCH_MODE_PARTIAL
        );
    }

    private static SyncPerformanceMonitor.Delivery delivery(
        final int lod,
        final long receivedAtNanos,
        final long deliveredAtNanos,
        final int requestBytes,
        final int responseBytes,
        final long queueNanos,
        final long encodeNanos,
        final SyncPerformanceMonitor.CumulativeWork cumulativeWork,
        final int mode
    ) {
        return delivery(
            lod, receivedAtNanos, deliveredAtNanos, requestBytes, responseBytes,
            queueNanos, encodeNanos, cumulativeWork, mode,
            SyncPerformanceMonitor.DirectWork.NONE
        );
    }

    private static SyncPerformanceMonitor.Delivery delivery(
        final int lod,
        final long receivedAtNanos,
        final long deliveredAtNanos,
        final int requestBytes,
        final int responseBytes,
        final long queueNanos,
        final long encodeNanos,
        final SyncPerformanceMonitor.CumulativeWork cumulativeWork,
        final int mode,
        final SyncPerformanceMonitor.DirectWork directWork
    ) {
        return new SyncPerformanceMonitor.Delivery(
            0, lod, 4, 5, receivedAtNanos, requestBytes, mode, responseBytes,
            queueNanos, encodeNanos, deliveredAtNanos, directWork, cumulativeWork
        );
    }
}
