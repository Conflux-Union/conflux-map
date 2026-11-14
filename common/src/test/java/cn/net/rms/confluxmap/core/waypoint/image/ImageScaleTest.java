package cn.net.rms.confluxmap.core.waypoint.image;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class ImageScaleTest {
    private static ImageScale.Raster raster(final int width, final int height) {
        final int[] argb = new int[width * height];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                argb[y * width + x] = 0xFF000000 | (x * 4) << 16 | (y * 4) << 8;
            }
        }
        return new ImageScale.Raster(argb, width, height);
    }

    @Test
    void rasterRejectsMismatchedArrays() {
        assertThrows(IllegalArgumentException.class, () -> new ImageScale.Raster(new int[3], 2, 2));
        assertThrows(IllegalArgumentException.class, () -> new ImageScale.Raster(new int[4], 0, 2));
    }

    @Test
    void downscaleAveragesEachSourceBoxPerChannel() {
        // 2x2 → 1x1 averages all four pixels.
        final ImageScale.Raster source = new ImageScale.Raster(new int[] {
            0xFF102030, 0xFF103040,
            0xFF203040, 0xFF204050,
        }, 2, 2);
        final ImageScale.Raster downscaled = ImageScale.downscale(source, 1, 1);
        assertEquals(1, downscaled.width());
        assertEquals(1, downscaled.height());
        // R: (0x10+0x10+0x20+0x20)/4 = 0x18, G: (0x20+0x30+0x30+0x40)/4 = 0x30, B: (0x30+0x40+0x40+0x50)/4 = 0x40
        assertEquals(0xFF183040, downscaled.argb()[0]);
    }

    @Test
    void downscaleKeepsEveryDestinationPixelInsideItsSourceBox() {
        final ImageScale.Raster source = raster(8, 8);
        final ImageScale.Raster downscaled = ImageScale.downscale(source, 4, 4);
        assertEquals(4, downscaled.width());
        assertEquals(4, downscaled.height());
        for (final int argb : downscaled.argb()) {
            assertEquals(0xFF000000, argb & 0xFF000000);
        }
    }

    @Test
    void fitMaxEdgeCapsTheLongerEdgeAndPreservesAspectRatio() {
        assertEquals(320, ImageScale.fitMaxEdge(1920, 1080, 320)[0]);
        assertEquals(180, ImageScale.fitMaxEdge(1920, 1080, 320)[1]);
        assertEquals(320, ImageScale.fitMaxEdge(1000, 1600, 320)[1]);
        assertEquals(200, ImageScale.fitMaxEdge(1000, 1600, 320)[0]);
        assertEquals(64, ImageScale.fitMaxEdge(64, 32, 320)[0]);
        assertEquals(32, ImageScale.fitMaxEdge(64, 32, 320)[1]);
        assertEquals(1, ImageScale.fitMaxEdge(1, 9, 320)[0]);
        assertEquals(9, ImageScale.fitMaxEdge(1, 9, 320)[1]);
    }
}
