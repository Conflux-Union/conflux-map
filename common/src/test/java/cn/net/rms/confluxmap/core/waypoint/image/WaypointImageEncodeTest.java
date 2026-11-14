package cn.net.rms.confluxmap.core.waypoint.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WaypointImageEncodeTest {
    private static ImageScale.Raster gradient(final int width, final int height) {
        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                // High-frequency checker noise maximizes PNG size so the
                // downscale-retry loop is exercised on every step.
                final int noise = ((x + y) & 1) == 0 ? 0xFF : 0x00;
                argb[y * width + x] = (noise << 24) | ((x * 255 / width) << 16) | ((y * 255 / height) << 8);
            }
        }
        return new ImageScale.Raster(argb, width, height);
    }

    @Test
    void fullHdGradientCompressesUnderTheBudgetAtMost320px(@TempDir final Path tempDir)
        throws Exception {
        final WaypointImageEncode.Encoded encoded = WaypointImageEncode.encodeWithin(
            gradient(1920, 1080), tempDir, 320, 48 * 1024
        ).orElseThrow();

        assertTrue(encoded.width() <= 320);
        assertTrue(
            encoded.png().length <= 48 * 1024,
            "encoded " + encoded.png().length + " bytes exceeds the 48 KiB budget"
        );
        // The PNG must be well-formed at its edges: signature + IEND chunk.
        assertEquals(0x89, encoded.png()[0] & 0xFF);
        assertEquals('I', encoded.png()[encoded.png().length - 8]);
        assertEquals('E', encoded.png()[encoded.png().length - 7]);
        assertEquals('N', encoded.png()[encoded.png().length - 6]);
        assertEquals('D', encoded.png()[encoded.png().length - 5]);
    }

    @Test
    void smallImagesEncodeOnceWithoutDownscaling(@TempDir final Path tempDir) throws Exception {
        final WaypointImageEncode.Encoded encoded = WaypointImageEncode.encodeWithin(
            gradient(64, 48), tempDir, 320, 48 * 1024
        ).orElseThrow();
        assertEquals(64, encoded.width());
        assertEquals(48, encoded.height());
    }

    @Test
    void tinyBudgetReturnsEmptyOrTheSmallestAllowedEdge(@TempDir final Path tempDir) {
        // A 96px checker gradient can still exceed 4 KiB; either outcome is
        // legitimate — the caller treats empty as TOO_LARGE and keeps the old image.
        final java.util.Optional<WaypointImageEncode.Encoded> encoded =
            WaypointImageEncode.encodeWithin(gradient(1920, 1080), tempDir, 320, 4 * 1024);
        assertTrue(encoded.isEmpty() || encoded.get().width() <= 96);
    }
}
