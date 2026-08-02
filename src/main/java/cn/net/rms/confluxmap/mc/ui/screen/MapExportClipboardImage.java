package cn.net.rms.confluxmap.mc.ui.screen;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/** PNG-first clipboard payload that avoids lossy X11 JPEG conversion for alpha images. */
final class MapExportClipboardImage implements Transferable {
    static final DataFlavor PNG_FLAVOR = pngFlavor();

    private final byte[] png;
    private final boolean offerDecodedImage;
    private volatile Image decodedImage;

    MapExportClipboardImage(final byte[] png, final boolean offerDecodedImage) {
        this(png, offerDecodedImage, true);
    }

    private MapExportClipboardImage(
        final byte[] png,
        final boolean offerDecodedImage,
        final boolean copyBytes
    ) {
        this.png = copyBytes ? png.clone() : png;
        this.offerDecodedImage = offerDecodedImage;
    }

    static MapExportClipboardImage read(final Path path) throws IOException {
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final boolean nativeImageClipboard = os.contains("win") || os.contains("mac");
        return new MapExportClipboardImage(
            Files.readAllBytes(path), nativeImageClipboard, false
        );
    }

    @Override
    public DataFlavor[] getTransferDataFlavors() {
        return offerDecodedImage
            ? new DataFlavor[] {PNG_FLAVOR, DataFlavor.imageFlavor}
            : new DataFlavor[] {PNG_FLAVOR};
    }

    @Override
    public boolean isDataFlavorSupported(final DataFlavor flavor) {
        return PNG_FLAVOR.equals(flavor)
            || offerDecodedImage && DataFlavor.imageFlavor.equals(flavor);
    }

    @Override
    public Object getTransferData(final DataFlavor flavor)
        throws UnsupportedFlavorException, IOException {
        if (PNG_FLAVOR.equals(flavor)) {
            return new ByteArrayInputStream(png);
        }
        if (!offerDecodedImage || !DataFlavor.imageFlavor.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        Image image = decodedImage;
        if (image == null) {
            try (InputStream input = new ByteArrayInputStream(png)) {
                image = ImageIO.read(input);
            }
            if (image == null) {
                throw new IOException("export is not a readable image");
            }
            decodedImage = image;
        }
        return image;
    }

    private static DataFlavor pngFlavor() {
        try {
            return new DataFlavor("image/png;class=java.io.InputStream");
        } catch (final ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
