package cn.net.rms.confluxmap.server;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class SyncPerformanceFormatterTest {
    @Test
    void printsEveryLodWithExplicitUnitsAndEmptyStates() {
        final List<String> lines = SyncPerformanceFormatter.format(List.of(
            new SyncPerformanceMonitor.LodSnapshot(
                0, 2L, 1_500_000L, 100_000L, 200_000L, 300_000L, 50_000L, 1_536L
            ),
            new SyncPerformanceMonitor.LodSnapshot(
                3, 1L, 4_000_000L, 200_000L, 1_000_000L, 1_500_000L, 100_000L, 900L
            )
        ), 4);

        assertEquals(List.of(
            "Conflux Map sync performance (current connection, averages per completed sync item):",
            "LOD 0: samples=2, total=1.50 ms, traffic=1.50 KiB",
            "  queue=0.10 ms, I/O/scan=0.20 ms, compute=0.30 ms, encode=0.05 ms, other=0.85 ms",
            "LOD 1: no completed sync samples",
            "LOD 2: no completed sync samples",
            "LOD 3: samples=1, total=4.00 ms, traffic=900 B",
            "  queue=0.20 ms, I/O/scan=1.00 ms, compute=1.50 ms, encode=0.10 ms, other=1.20 ms",
            "LOD 4: no completed sync samples"
        ), lines);
    }
}
