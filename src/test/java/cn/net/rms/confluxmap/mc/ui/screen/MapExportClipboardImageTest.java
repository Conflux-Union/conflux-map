package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.awt.datatransfer.DataFlavor;
import java.io.InputStream;
import org.junit.jupiter.api.Test;

final class MapExportClipboardImageTest {
    @Test
    void pngOnlyTransferPreservesBytesWithoutAdvertisingJpegConversion() throws Exception {
        final byte[] png = {1, 2, 3, 4};
        final MapExportClipboardImage image = new MapExportClipboardImage(png, false);

        assertFalse(image.isDataFlavorSupported(DataFlavor.imageFlavor));
        try (InputStream input = (InputStream) image.getTransferData(
            MapExportClipboardImage.PNG_FLAVOR
        )) {
            assertArrayEquals(png, input.readAllBytes());
        }
    }
}
