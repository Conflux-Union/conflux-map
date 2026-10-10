package cn.net.rms.confluxmap.mc.ui.screen;

import java.awt.Image;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import javax.imageio.ImageIO;

/** File-backed clipboard payload that never pins the exported image in heap. */
final class MapExportClipboardImage implements Transferable {
    static final DataFlavor PNG_FLAVOR = pngFlavor();
    private static final long HEAP_RESERVE_BYTES = 128L * 1024L * 1024L;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 137, 80, 78, 71, 13, 10, 26, 10
    };

    private final Path path;
    private final long decodedBytes;
    private final boolean offerDecodedImage;

    MapExportClipboardImage(final Path path, final long decodedBytes, final boolean offerDecodedImage) {
        this.path = path;
        this.decodedBytes = decodedBytes;
        this.offerDecodedImage = offerDecodedImage;
    }

    /** Serves the PNG flavor from disk and decodes only when a target asks for an image. */
    static MapExportClipboardImage read(final Path path) throws IOException {
        final Dimensions size = readPngDimensions(path);
        final long decodedBytes = decodedBytes(size.width(), size.height());
        final String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        final boolean nativeImageClipboard = os.contains("win") || os.contains("mac");
        return new MapExportClipboardImage(
            path,
            decodedBytes,
            shouldOfferDecodedImage(nativeImageClipboard, decodedBytes, Runtime.getRuntime().maxMemory())
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
            return Files.newInputStream(path);
        }
        if (!offerDecodedImage || !DataFlavor.imageFlavor.equals(flavor)) {
            throw new UnsupportedFlavorException(flavor);
        }
        final Runtime runtime = Runtime.getRuntime();
        if (!hasDecodeHeadroom(
            decodedBytes, runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory()
        )) {
            throw new IOException("not enough memory to decode the exported image");
        }
        // No cache: apps that insist on a decoded image get one transient per request,
        // so clipboard ownership no longer retains the whole raster.
        final Image image;
        try (InputStream input = Files.newInputStream(path)) {
            image = ImageIO.read(input);
        }
        if (image == null) {
            throw new IOException("export is not a readable image");
        }
        return image;
    }

    record Dimensions(int width, int height) {
    }

    /** Reads the mandatory IHDR so the decoded size is known without loading the file. */
    static Dimensions readPngDimensions(final Path path) throws IOException {
        final byte[] header = new byte[24];
        try (InputStream input = Files.newInputStream(path)) {
            new DataInputStream(input).readFully(header);
        }
        for (int index = 0; index < PNG_SIGNATURE.length; index++) {
            if (header[index] != PNG_SIGNATURE[index]) {
                throw new IOException(path + " is not a PNG file");
            }
        }
        if (header[12] != 'I' || header[13] != 'H' || header[14] != 'D' || header[15] != 'R') {
            throw new IOException(path + " has no PNG header chunk");
        }
        final int width = (header[16] & 0xFF) << 24 | (header[17] & 0xFF) << 16
            | (header[18] & 0xFF) << 8 | (header[19] & 0xFF);
        final int height = (header[20] & 0xFF) << 24 | (header[21] & 0xFF) << 16
            | (header[22] & 0xFF) << 8 | (header[23] & 0xFF);
        if (width <= 0 || height <= 0) {
            throw new IOException(path + " has invalid PNG dimensions");
        }
        return new Dimensions(width, height);
    }

    static long decodedBytes(final int width, final int height) {
        try {
            return Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        } catch (final ArithmeticException e) {
            return Long.MAX_VALUE;
        }
    }

    static boolean shouldOfferDecodedImage(
        final boolean nativeImageClipboard,
        final long decodedBytes,
        final long maxMemory
    ) {
        return nativeImageClipboard && decodedBytes <= maxMemory - HEAP_RESERVE_BYTES;
    }

    static boolean hasDecodeHeadroom(
        final long decodedBytes,
        final long maxMemory,
        final long usedMemory
    ) {
        return decodedBytes <= maxMemory - usedMemory - HEAP_RESERVE_BYTES;
    }

    private static DataFlavor pngFlavor() {
        try {
            return new DataFlavor("image/png;class=java.io.InputStream");
        } catch (final ClassNotFoundException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
}
