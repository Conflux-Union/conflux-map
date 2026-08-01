package cn.net.rms.confluxmap.server;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Produces compact chat lines for the server-side sync performance command. */
public final class SyncPerformanceFormatter {
    private SyncPerformanceFormatter() {
    }

    public static List<String> format(
        final List<SyncPerformanceMonitor.LodSnapshot> snapshots,
        final int maxLod
    ) {
        final Map<Integer, SyncPerformanceMonitor.LodSnapshot> byLod = new HashMap<>();
        for (final SyncPerformanceMonitor.LodSnapshot snapshot : snapshots) {
            byLod.put(snapshot.lod(), snapshot);
        }
        final List<String> lines = new ArrayList<>(2 * Math.max(0, maxLod) + 2);
        lines.add("Conflux Map sync performance (current connection, averages per completed sync item):");
        for (int lod = 0; lod <= maxLod; lod++) {
            final SyncPerformanceMonitor.LodSnapshot snapshot = byLod.get(lod);
            if (snapshot == null || snapshot.samples() <= 0L) {
                lines.add("LOD " + lod + ": no completed sync samples");
                continue;
            }
            lines.add("LOD " + lod
                + ": samples=" + snapshot.samples()
                + ", total=" + millis(snapshot.averageTotalNanos())
                + ", traffic=" + bytes(snapshot.averageTrafficBytes()));
            lines.add("  queue=" + millis(snapshot.averageQueueNanos())
                + ", I/O/scan=" + millis(snapshot.averageIoNanos())
                + ", compute=" + millis(snapshot.averageComputeNanos())
                + ", encode=" + millis(snapshot.averageEncodeNanos())
                + ", other=" + millis(snapshot.averageOtherNanos()));
        }
        return List.copyOf(lines);
    }

    private static String millis(final long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", Math.max(0L, nanos) / 1_000_000.0d);
    }

    private static String bytes(final long value) {
        final long bytes = Math.max(0L, value);
        if (bytes < 1_024L) {
            return bytes + " B";
        }
        if (bytes < 1_024L * 1_024L) {
            return String.format(Locale.ROOT, "%.2f KiB", bytes / 1_024.0d);
        }
        return String.format(Locale.ROOT, "%.2f MiB", bytes / (1_024.0d * 1_024.0d));
    }
}
