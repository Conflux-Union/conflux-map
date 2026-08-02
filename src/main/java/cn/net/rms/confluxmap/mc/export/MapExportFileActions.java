package cn.net.rms.confluxmap.mc.export;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import com.sun.jna.Native;
import com.sun.jna.NativeLong;
import com.sun.jna.Pointer;
import com.sun.jna.win32.StdCallLibrary;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Util;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

/** Local desktop actions for a PNG created by the map-export service. */
public final class MapExportFileActions {
    private static final Logger LOGGER = LogManager.getLogger("ConfluxMap/MapExportFileActions");
    private static final String WINDOWS = "Windows";

    private MapExportFileActions() {
    }

    /** Outcome of attempting to copy a completed export to the operating-system clipboard. */
    public enum ClipboardCopyResult {
        IMAGE,
        PATH,
        FAILED
    }

    /**
     * Copies an exported PNG as image data on Windows. Minecraft deliberately starts its JVM in
     * headless mode, so AWT's system clipboard is unavailable even though the game has a window.
     * When a native image copy is unavailable, preserve a useful result by copying the PNG path
     * through Minecraft's own GLFW-backed text clipboard.
     */
    public static ClipboardCopyResult copyPngToClipboard(final Path output) {
        if (output == null || !Files.isRegularFile(output)) {
            return ClipboardCopyResult.FAILED;
        }
        try {
            final BufferedImage image = ImageIO.read(output.toFile());
            if (image == null) {
                LOGGER.warn("Map export is not a readable image: {}", output);
                return copyPathToClipboard(output);
            }
            if (isWindows() && WindowsClipboard.copyImage(image)) {
                return ClipboardCopyResult.IMAGE;
            }
        } catch (final IOException | RuntimeException | LinkageError e) {
            LOGGER.warn("Could not copy exported map image to the clipboard: {}", output, e);
        }
        return copyPathToClipboard(output);
    }

    /** Opens only the export's direct parent directory after an explicit player action. */
    public static boolean openContainingDirectory(final Path output) {
        final Path directory = output == null ? null : output.getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return false;
        }
        try {
            //#if MC>=260100
            //$$ Util.getPlatform().openPath(directory);
            //#else
            Util.getOperatingSystem().open(directory.toFile());
            //#endif
            return true;
        } catch (final RuntimeException e) {
            LOGGER.warn("Could not open map export directory: {}", directory, e);
            return false;
        }
    }

    static byte[] toWindowsDib(final BufferedImage image) {
        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("Image dimensions must be positive");
        }
        final long pixelBytes = Math.multiplyExact(Math.multiplyExact((long) width, height), 4L);
        final int totalBytes = Math.toIntExact(Math.addExact(40L, pixelBytes));
        final ByteBuffer dib = ByteBuffer.allocate(totalBytes).order(ByteOrder.LITTLE_ENDIAN);
        dib.putInt(40); // BITMAPINFOHEADER size
        dib.putInt(width);
        dib.putInt(-height); // Top-down rows preserve the BufferedImage coordinate order.
        dib.putShort((short) 1);
        dib.putShort((short) 32);
        dib.putInt(0); // BI_RGB
        dib.putInt(Math.toIntExact(pixelBytes));
        dib.putInt(0);
        dib.putInt(0);
        dib.putInt(0);
        dib.putInt(0);
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                final int argb = image.getRGB(x, y);
                dib.put((byte) argb);
                dib.put((byte) (argb >>> 8));
                dib.put((byte) (argb >>> 16));
                dib.put((byte) (argb >>> 24));
            }
        }
        return dib.array();
    }

    private static ClipboardCopyResult copyPathToClipboard(final Path output) {
        try {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client == null) {
                return ClipboardCopyResult.FAILED;
            }
            final String path = output.toAbsolutePath().toString();
            //#if MC>=260100
            //$$ client.keyboardHandler.setClipboard(path);
            //#else
            client.keyboard.setClipboard(path);
            //#endif
            return ClipboardCopyResult.PATH;
        } catch (final RuntimeException | LinkageError e) {
            LOGGER.warn("Could not copy map export path to the clipboard: {}", output, e);
            return ClipboardCopyResult.FAILED;
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").startsWith(WINDOWS);
    }

    /** Minimal JNA binding so no desktop toolkit or shell process is required. */
    private static final class WindowsClipboard {
        private static final int CF_DIB = 8;
        private static final int GMEM_MOVEABLE = 0x0002;

        private WindowsClipboard() {
        }

        private static boolean copyImage(final BufferedImage image) {
            Pointer memory = null;
            boolean clipboardOpen = false;
            try {
                final byte[] dib = toWindowsDib(image);
                memory = Kernel32.INSTANCE.GlobalAlloc(GMEM_MOVEABLE, new NativeLong(dib.length));
                if (memory == null) {
                    return false;
                }
                final Pointer locked = Kernel32.INSTANCE.GlobalLock(memory);
                if (locked == null) {
                    return false;
                }
                try {
                    locked.write(0L, dib, 0, dib.length);
                } finally {
                    Kernel32.INSTANCE.GlobalUnlock(memory);
                }
                if (!User32.INSTANCE.OpenClipboard(null)) {
                    return false;
                }
                clipboardOpen = true;
                if (!User32.INSTANCE.EmptyClipboard()
                    || User32.INSTANCE.SetClipboardData(CF_DIB, memory) == null) {
                    return false;
                }
                memory = null; // Clipboard owns this allocation after SetClipboardData succeeds.
                return true;
            } catch (final RuntimeException | LinkageError e) {
                LOGGER.warn("Could not copy map export image using the Windows clipboard", e);
                return false;
            } finally {
                if (clipboardOpen) {
                    User32.INSTANCE.CloseClipboard();
                }
                if (memory != null) {
                    Kernel32.INSTANCE.GlobalFree(memory);
                }
            }
        }

        private interface User32 extends StdCallLibrary {
            User32 INSTANCE = Native.load("user32", User32.class);

            boolean OpenClipboard(Pointer owner);

            boolean CloseClipboard();

            boolean EmptyClipboard();

            Pointer SetClipboardData(int format, Pointer memory);
        }

        private interface Kernel32 extends StdCallLibrary {
            Kernel32 INSTANCE = Native.load("kernel32", Kernel32.class);

            Pointer GlobalAlloc(int flags, NativeLong bytes);

            Pointer GlobalLock(Pointer memory);

            boolean GlobalUnlock(Pointer memory);

            Pointer GlobalFree(Pointer memory);
        }
    }
}
