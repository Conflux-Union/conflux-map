package cn.net.rms.confluxmap.core.export;

import java.util.Locale;

/** Conservative PNG-size estimate used while choosing an export resolution. */
public final class MapExportSizeEstimate {
    private static final long PNG_FIXED_BYTES = 45L;
    private static final long PNG_CHUNK_BYTES = 12L;
    private static final long IDAT_PAYLOAD_BYTES = 64L * 1024L;
    private static final String[] UNITS = {"B", "KiB", "MiB", "GiB", "TiB", "PiB", "EiB"};

    private MapExportSizeEstimate() {
    }

    public static long estimatedMaximumPngBytes(final int width, final int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("PNG dimensions must be positive");
        }
        final long pixels = Math.multiplyExact((long) width, height);
        final long raw = Math.multiplyExact(pixels, 4L);
        final long scanlines = saturatingAdd(raw, height);
        final long compressed = deflateBound(scanlines);
        final long rowFeeds = saturatingAdd(1L, ceilDiv((long) width * 4L, IDAT_PAYLOAD_BYTES));
        final long feedCalls = saturatingMultiply(height, rowFeeds);
        final long chunks = saturatingAdd(saturatingMultiply(feedCalls, 2L), 2L);
        return saturatingAdd(
            PNG_FIXED_BYTES,
            saturatingAdd(compressed, saturatingMultiply(chunks, PNG_CHUNK_BYTES))
        );
    }

    public static String formatBytes(final long bytes) {
        if (bytes < 0L) {
            throw new IllegalArgumentException("Byte count must not be negative");
        }
        if (bytes < 1024L) {
            return bytes + " B";
        }
        double value = bytes;
        int unit = 0;
        while (value >= 1024.0 && unit < UNITS.length - 1) {
            value /= 1024.0;
            unit++;
        }
        return String.format(Locale.ROOT, "%.1f %s", value, UNITS[unit]);
    }

    private static long deflateBound(final long bytes) {
        long result = bytes;
        result = saturatingAdd(result, bytes >> 12);
        result = saturatingAdd(result, bytes >> 14);
        result = saturatingAdd(result, bytes >> 25);
        return saturatingAdd(result, 13L);
    }

    private static long ceilDiv(final long value, final long divisor) {
        return value / divisor + (value % divisor == 0L ? 0L : 1L);
    }

    private static long saturatingMultiply(final long left, final long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatingAdd(final long left, final long right) {
        try {
            return Math.addExact(left, right);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }
}
