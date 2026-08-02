package cn.net.rms.confluxmap.mc.ui.screen;

import java.awt.Desktop;
import java.awt.Image;
import java.awt.Toolkit;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import javax.imageio.ImageIO;

/** Non-fatal desktop integration used after a PNG export completes. */
final class MapExportDesktopActions {
    private static final long HEAP_RESERVE_BYTES = 128L * 1024L * 1024L;

    enum CopyState { IDLE, COPYING, COPIED, SKIPPED, FAILED }
    enum OpenState { IDLE, OPENED, FAILED }

    record MemorySnapshot(long maxMemory, long usedMemory) {
    }

    interface DesktopBridge {
        void copyImage(Path path) throws Exception;
        void openDirectory(Path path) throws Exception;
    }

    private final DesktopBridge bridge;
    private final Executor executor;
    private final Supplier<MemorySnapshot> memory;
    private final AtomicLong copyGeneration = new AtomicLong();
    private final AtomicLong openGeneration = new AtomicLong();
    private volatile CopyState copyState = CopyState.IDLE;
    private volatile OpenState openState = OpenState.IDLE;
    private volatile String copyError;
    private volatile String openError;

    MapExportDesktopActions(
        final DesktopBridge bridge,
        final Executor executor,
        final Supplier<MemorySnapshot> memory
    ) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.memory = Objects.requireNonNull(memory, "memory");
    }

    static MapExportDesktopActions system() {
        return new MapExportDesktopActions(
            new SystemDesktopBridge(),
            ForkJoinPool.commonPool(),
            () -> {
                final Runtime runtime = Runtime.getRuntime();
                return new MemorySnapshot(
                    runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory()
                );
            }
        );
    }

    void copyImage(final Path output, final int width, final int height) {
        final long generation = copyGeneration.incrementAndGet();
        final MemorySnapshot snapshot;
        try {
            snapshot = memory.get();
        } catch (final RuntimeException e) {
            copyError = message(e);
            copyState = CopyState.FAILED;
            return;
        }
        if (!hasClipboardHeadroom(
            width, height, snapshot.maxMemory(), snapshot.usedMemory()
        )) {
            copyState = CopyState.SKIPPED;
            copyError = "insufficient memory";
            return;
        }
        copyState = CopyState.COPYING;
        copyError = null;
        try {
            executor.execute(() -> {
                try {
                    bridge.copyImage(output);
                    if (copyGeneration.get() == generation) {
                        copyState = CopyState.COPIED;
                    }
                } catch (final Exception | LinkageError e) {
                    if (copyGeneration.get() == generation) {
                        copyError = message(e);
                        copyState = CopyState.FAILED;
                    }
                }
            });
        } catch (final RuntimeException e) {
            copyError = message(e);
            copyState = CopyState.FAILED;
        }
    }

    void openDirectory(final Path output) {
        final long generation = openGeneration.incrementAndGet();
        final Path parent = output == null ? null : output.toAbsolutePath().getParent();
        if (parent == null) {
            openError = "export directory is unavailable";
            openState = OpenState.FAILED;
            return;
        }
        openState = OpenState.IDLE;
        openError = null;
        try {
            executor.execute(() -> {
                try {
                    bridge.openDirectory(parent);
                    if (openGeneration.get() == generation) {
                        openState = OpenState.OPENED;
                    }
                } catch (final Exception | LinkageError e) {
                    if (openGeneration.get() == generation) {
                        openError = message(e);
                        openState = OpenState.FAILED;
                    }
                }
            });
        } catch (final RuntimeException e) {
            openError = message(e);
            openState = OpenState.FAILED;
        }
    }

    void resetForExport() {
        copyGeneration.incrementAndGet();
        openGeneration.incrementAndGet();
        copyState = CopyState.IDLE;
        copyError = null;
        openState = OpenState.IDLE;
        openError = null;
    }

    CopyState copyState() {
        return copyState;
    }

    OpenState openState() {
        return openState;
    }

    String copyError() {
        return copyError;
    }

    String openError() {
        return openError;
    }

    static boolean hasClipboardHeadroom(
        final int width,
        final int height,
        final long maxMemory,
        final long usedMemory
    ) {
        try {
            final long decodedBytes = Math.multiplyExact(
                Math.multiplyExact((long) width, height), 4L
            );
            return width > 0 && height > 0
                && maxMemory - usedMemory >= Math.addExact(decodedBytes, HEAP_RESERVE_BYTES);
        } catch (final ArithmeticException e) {
            return false;
        }
    }

    private static String message(final Throwable fault) {
        return fault.getMessage() == null ? fault.getClass().getSimpleName() : fault.getMessage();
    }

    private static final class SystemDesktopBridge implements DesktopBridge {
        @Override
        public void copyImage(final Path path) throws IOException {
            final Image image = ImageIO.read(path.toFile());
            if (image == null) {
                throw new IOException("export is not a readable image");
            }
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                new ImageTransferable(image), null
            );
        }

        @Override
        public void openDirectory(final Path path) throws IOException {
            if (!Desktop.isDesktopSupported()
                || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("opening folders is not supported");
            }
            Desktop.getDesktop().open(path.toFile());
        }
    }

    private record ImageTransferable(Image image) implements Transferable {
        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[] {DataFlavor.imageFlavor};
        }

        @Override
        public boolean isDataFlavorSupported(final DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }

        @Override
        public Object getTransferData(final DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return image;
        }
    }
}
