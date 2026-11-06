//#if MC<12002
package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

/**
 * Pixel-level vanilla-button detection for the pre-1.20.2 widgets.png atlas. The reference
 * digest below was produced by an independent implementation (Python, big-endian ARGB
 * row-major over the exact same formula), so it anchors the Java canonicalization rather
 * than restating it.
 */
final class VanillaWidgetsButtonArtTest {
    /**
     * sha-256 of the synthetic pattern over the region x in [0,200), y in [46,106),
     * each pixel encoded as big-endian ARGB. Region geometry mirrors vanilla button
     * rendering: three 200x20 strips (disabled/normal/hover) at v = 46/66/86.
     */
    private static final String SYNTHETIC_REGION_DIGEST =
        "bbe3d0d9d970367a7b58f7759e3db44c2de26265c6744a5ad96efac3ec8fccef";

    @Test
    void regionDigestMatchesTheIndependentReference() throws IOException {
        assertTrue(matchesPng(syntheticWidgets(), SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void pixelChangeInsideTheButtonRegionBreaksTheMatch() throws IOException {
        final BufferedImage modified = syntheticWidgets();
        modified.setRGB(5, 50, 0xFF00FF00);

        assertFalse(matchesPng(modified, SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void pixelChangeOutsideTheButtonRegionKeepsTheMatch() throws IOException {
        final BufferedImage modified = syntheticWidgets();
        modified.setRGB(250, 250, 0xFF00FF00);

        assertTrue(matchesPng(modified, SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void alphaParticipatesInTheDigest() throws IOException {
        final BufferedImage modified = syntheticWidgets();
        final int regionPixel = modified.getRGB(100, 70);
        modified.setRGB(100, 70, regionPixel & 0x00FFFFFF);

        assertFalse(matchesPng(modified, SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void undecodableStreamIsNotVanillaButtonArt() throws IOException {
        final InputStream garbage = new ByteArrayInputStream(
            "not a png at all".getBytes(StandardCharsets.US_ASCII)
        );

        assertFalse(VanillaWidgetsButtonArt.matchesVanillaButtons(garbage, SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void imageSmallerThanTheButtonRegionIsNotVanillaButtonArt() throws IOException {
        final BufferedImage tiny = new BufferedImage(100, 100, BufferedImage.TYPE_INT_ARGB);

        assertFalse(matchesPng(tiny, SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void atlasLargerThanVanillaStillHashesOnlyTheRegion() throws IOException {
        final BufferedImage hd = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                hd.setRGB(x, y, syntheticPixel(x, y));
            }
        }

        assertTrue(matchesPng(hd, SYNTHETIC_REGION_DIGEST));
    }

    @Test
    void upscaledButtonArtCountsAsModified() throws IOException {
        final BufferedImage hd = new BufferedImage(512, 512, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 512; y++) {
            for (int x = 0; x < 512; x++) {
                hd.setRGB(x, y, syntheticPixel(x / 2, y / 2));
            }
        }

        assertFalse(matchesPng(hd, SYNTHETIC_REGION_DIGEST));
    }

    private static boolean matchesPng(final BufferedImage image, final String digest)
        throws IOException {
        final ByteArrayOutputStream png = new ByteArrayOutputStream();
        ImageIO.write(image, "png", png);
        return VanillaWidgetsButtonArt.matchesVanillaButtons(
            new ByteArrayInputStream(png.toByteArray()), digest
        );
    }

    private static BufferedImage syntheticWidgets() {
        final BufferedImage image = new BufferedImage(256, 256, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < 256; y++) {
            for (int x = 0; x < 256; x++) {
                image.setRGB(x, y, syntheticPixel(x, y));
            }
        }
        return image;
    }

    private static int syntheticPixel(final int x, final int y) {
        return 0xFF000000
            | (((x * 7 + y * 13) & 0xFF) << 16)
            | (((x ^ y) & 0xFF) << 8)
            | ((x * 3 + y * 5) & 0xFF);
    }
}
//#endif
