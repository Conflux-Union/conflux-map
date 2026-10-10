package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.datatransfer.DataFlavor;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class MapExportClipboardImageTest {
    private static final long MIB = 1024L * 1024L;

    @Test
    void pngFlavorStreamsFileBytesWithoutPinningThemInHeap(@TempDir final Path temp) throws Exception {
        final byte[] png = {1, 2, 3, 4, 5, 6, 7, 8};
        final Path file = temp.resolve("export.png");
        Files.write(file, png);
        final MapExportClipboardImage image = new MapExportClipboardImage(file, png.length, false);

        assertFalse(image.isDataFlavorSupported(DataFlavor.imageFlavor));
        try (InputStream input = (InputStream) image.getTransferData(
            MapExportClipboardImage.PNG_FLAVOR
        )) {
            assertArrayEquals(png, input.readAllBytes());
        }
        try (InputStream again = (InputStream) image.getTransferData(
            MapExportClipboardImage.PNG_FLAVOR
        )) {
            assertArrayEquals(png, again.readAllBytes());
        }
    }

    @Test
    void decodedImageFlavorIsAdvertisedOnlyWhenRequested(@TempDir final Path temp) throws Exception {
        final Path file = temp.resolve("export.png");
        Files.write(file, new byte[] {1, 2, 3, 4});
        final MapExportClipboardImage image = new MapExportClipboardImage(file, 16L, true);

        assertTrue(image.isDataFlavorSupported(DataFlavor.imageFlavor));
        assertEquals(
            2,
            Arrays.stream(image.getTransferDataFlavors())
                .filter(flavor -> flavor.equals(MapExportClipboardImage.PNG_FLAVOR)
                    || flavor.equals(DataFlavor.imageFlavor))
                .count()
        );
    }

    @Test
    void readParsesDimensionsFromThePngHeader(@TempDir final Path temp) throws Exception {
        final Path file = temp.resolve("export.png");
        Files.write(file, pngHeader(300, 200));
        assertEquals(300, MapExportClipboardImage.readPngDimensions(file).width());
        assertEquals(200, MapExportClipboardImage.readPngDimensions(file).height());
    }

    @Test
    void readRejectsFilesWithoutAPngHeader(@TempDir final Path temp) throws Exception {
        final Path file = temp.resolve("export.png");
        Files.write(file, new byte[] {'n', 'o', 't', ' ', 'a', ' ', 'p', 'n', 'g'});
        assertThrows(IOException.class, () -> MapExportClipboardImage.readPngDimensions(file));
    }

    @Test
    void decodeOfferAndHeadroomKeepTheHeapReserve() {
        assertFalse(MapExportClipboardImage.shouldOfferDecodedImage(false, 4 * MIB, 512 * MIB));
        assertTrue(MapExportClipboardImage.shouldOfferDecodedImage(true, 4 * MIB, 512 * MIB));
        assertFalse(MapExportClipboardImage.shouldOfferDecodedImage(true, 512 * MIB, 512 * MIB));

        assertTrue(MapExportClipboardImage.hasDecodeHeadroom(4 * MIB, 512 * MIB, 100 * MIB));
        assertFalse(MapExportClipboardImage.hasDecodeHeadroom(256 * MIB, 512 * MIB, 200 * MIB));
    }

    private static byte[] pngHeader(final int width, final int height) {
        final byte[] header = new byte[33];
        header[0] = (byte) 137;
        header[1] = 'P';
        header[2] = 'N';
        header[3] = 'G';
        header[4] = 13;
        header[5] = 10;
        header[6] = 26;
        header[7] = 10;
        header[12] = 'I';
        header[13] = 'H';
        header[14] = 'D';
        header[15] = 'R';
        header[16] = (byte) (width >>> 24);
        header[17] = (byte) (width >>> 16);
        header[18] = (byte) (width >>> 8);
        header[19] = (byte) width;
        header[20] = (byte) (height >>> 24);
        header[21] = (byte) (height >>> 16);
        header[22] = (byte) (height >>> 8);
        header[23] = (byte) height;
        return header;
    }
}
