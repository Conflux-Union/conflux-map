package cn.net.rms.confluxmap.mc.ui.screen;

import java.awt.Desktop;
import java.awt.Toolkit;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

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
            withFallback(new AwtDesktopBridge(), new SystemCommandDesktopBridge()),
            ForkJoinPool.commonPool(),
            () -> {
                final Runtime runtime = Runtime.getRuntime();
                return new MemorySnapshot(
                    runtime.maxMemory(), runtime.totalMemory() - runtime.freeMemory()
                );
            }
        );
    }

    static DesktopBridge withFallback(
        final DesktopBridge primary,
        final DesktopBridge fallback
    ) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(fallback, "fallback");
        return new DesktopBridge() {
            @Override
            public void copyImage(final Path path) throws Exception {
                try {
                    primary.copyImage(path);
                } catch (final Exception | LinkageError | java.awt.AWTError primaryFault) {
                    try {
                        fallback.copyImage(path);
                    } catch (final Exception | LinkageError | java.awt.AWTError fallbackFault) {
                        fallbackFault.addSuppressed(primaryFault);
                        throw fallbackFault;
                    }
                }
            }

            @Override
            public void openDirectory(final Path path) throws Exception {
                try {
                    primary.openDirectory(path);
                } catch (final Exception | LinkageError | java.awt.AWTError primaryFault) {
                    try {
                        fallback.openDirectory(path);
                    } catch (final Exception | LinkageError | java.awt.AWTError fallbackFault) {
                        fallbackFault.addSuppressed(primaryFault);
                        throw fallbackFault;
                    }
                }
            }
        };
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
                } catch (final Exception | LinkageError | java.awt.AWTError e) {
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
                } catch (final Exception | LinkageError | java.awt.AWTError e) {
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

    private static final class AwtDesktopBridge implements DesktopBridge {
        @Override
        public void copyImage(final Path path) throws IOException {
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(
                MapExportClipboardImage.read(path), null
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

    private static final class SystemCommandDesktopBridge implements DesktopBridge {
        @Override
        public void copyImage(final Path path) throws Exception {
            MapExportClipboardProcess.copy(path);
        }

        @Override
        public void openDirectory(final Path path) throws IOException, InterruptedException {
            final String os = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT);
            if (os.contains("win")) {
                runCommand("explorer.exe", path.toString());
                return;
            }
            if (os.contains("mac")) {
                runCommand("open", path.toString());
                return;
            }
            IOException firstFault = null;
            try {
                runCommand("xdg-open", path.toString());
                return;
            } catch (final IOException e) {
                firstFault = e;
            }
            try {
                runCommand("gio", "open", path.toString());
            } catch (final IOException e) {
                e.addSuppressed(firstFault);
                throw e;
            }
        }

        private static void runCommand(final String... command)
            throws IOException, InterruptedException {
            final Process process = new ProcessBuilder(command)
                .redirectErrorStream(true)
                .start();
            if (!process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
                return;
            }
            if (process.exitValue() != 0) {
                final byte[] error = process.getInputStream().readNBytes(1024);
                final String detail = new String(error, java.nio.charset.StandardCharsets.UTF_8).trim();
                throw new IOException(detail.isEmpty()
                    ? command[0] + " exited with " + process.exitValue()
                    : detail);
            }
        }
    }

}
