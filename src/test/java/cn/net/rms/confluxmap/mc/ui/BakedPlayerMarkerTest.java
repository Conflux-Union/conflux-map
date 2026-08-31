package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class BakedPlayerMarkerTest {
    private static final String[] MARKERS = {
        "player_marker_modern.png",
        "player_marker_traditional.png"
    };

    @Test
    void bakedMarkersPreserveFillOutlineAndAntialiasing() throws IOException {
        for (final String marker : MARKERS) {
            final BufferedImage image = marker(marker);
            assertEquals(64, image.getWidth(), marker + " width");
            assertEquals(64, image.getHeight(), marker + " height");
            assertTrue(image.getColorModel().hasAlpha(), marker + " alpha");
            assertTrue(count(image, 0x00FFFFFF, 0x00FFFFFF, 255, 255) > 0, marker + " white fill");
            assertTrue(count(image, 0x00FFFFFF, 0x00101010, 255, 255) > 0, marker + " dark outline");
            assertTrue(count(image, 0, 0, 1, 254) > 0, marker + " antialiased edge");
        }
    }

    private static BufferedImage marker(final String fileName) throws IOException {
        final String path = "/assets/confluxmap/textures/gui/markers/" + fileName;
        try (InputStream stream = BakedPlayerMarkerTest.class.getResourceAsStream(path)) {
            assertTrue(stream != null, "missing " + path);
            return ImageIO.read(stream);
        }
    }

    private static long count(
        final BufferedImage image,
        final int rgbMask,
        final int expectedRgb,
        final int minAlpha,
        final int maxAlpha
    ) {
        long count = 0;
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                final int argb = image.getRGB(x, y);
                final int alpha = argb >>> 24;
                if ((argb & rgbMask) == expectedRgb && alpha >= minAlpha && alpha <= maxAlpha) {
                    count++;
                }
            }
        }
        return count;
    }
}
