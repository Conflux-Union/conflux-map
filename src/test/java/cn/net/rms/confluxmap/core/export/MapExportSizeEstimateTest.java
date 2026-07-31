package cn.net.rms.confluxmap.core.export;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MapExportSizeEstimateTest {
    @Test
    void estimateGrowsWithResolutionAndIncludesPngOverhead() {
        final long onePixel = MapExportSizeEstimate.estimatedMaximumPngBytes(1, 1);
        final long fullTile = MapExportSizeEstimate.estimatedMaximumPngBytes(256, 256);

        assertTrue(onePixel > 4L);
        assertTrue(fullTile > onePixel);
        assertTrue(fullTile > 256L * 256L * 4L);
    }

    @Test
    void formatsBinaryUnitsForTheSelectionScreen() {
        assertEquals("0 B", MapExportSizeEstimate.formatBytes(0L));
        assertEquals("1023 B", MapExportSizeEstimate.formatBytes(1023L));
        assertEquals("1.0 KiB", MapExportSizeEstimate.formatBytes(1024L));
        assertEquals("5.0 MiB", MapExportSizeEstimate.formatBytes(5L * 1024L * 1024L));
    }
}
