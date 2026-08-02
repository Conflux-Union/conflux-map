package cn.net.rms.confluxmap.mc.ui.screen;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.ClipboardOwner;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Runs image clipboard ownership outside a game JVM whose AWT desktop may be unavailable. */
final class MapExportClipboardProcess {
    private static final String READY = "CONFLUXMAP_CLIPBOARD_READY";

    private MapExportClipboardProcess() {
    }

    static void copy(final Path image) throws IOException, InterruptedException {
        final Process process = new ProcessBuilder(helperCommand(image))
            .redirectErrorStream(true)
            .start();
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        final BufferedReader output = new BufferedReader(new InputStreamReader(
            process.getInputStream(), StandardCharsets.UTF_8
        ));
        String detail = null;
        while (System.nanoTime() < deadline) {
            while (output.ready()) {
                final String line = output.readLine();
                if (READY.equals(line)) {
                    return;
                }
                if (line != null && !line.isBlank()) {
                    detail = line;
                }
            }
            if (!process.isAlive()) {
                String line;
                while ((line = output.readLine()) != null) {
                    if (!line.isBlank()) {
                        detail = line;
                    }
                }
                throw new IOException(detail == null || detail.isBlank()
                    ? "clipboard helper exited with " + process.exitValue()
                    : detail);
            }
            TimeUnit.MILLISECONDS.sleep(25);
        }
        process.destroy();
        throw new IOException("clipboard helper timed out");
    }

    private static List<String> helperCommand(final Path image) throws IOException {
        final String executable = Path.of(
            System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"
        ).toString();
        final List<String> command = new ArrayList<>();
        command.add(executable);
        command.add("-Djava.awt.headless=false");
        command.add("-cp");
        command.add(helperClasspath());
        command.add(MapExportClipboardProcess.class.getName());
        command.add(image.toAbsolutePath().toString());
        return command;
    }

    private static String helperClasspath() throws IOException {
        final String runtimeClasspath = System.getProperty("java.class.path", "");
        final URL source = MapExportClipboardProcess.class.getProtectionDomain()
            .getCodeSource().getLocation();
        if (source == null) {
            return runtimeClasspath;
        }
        try {
            final String ownLocation = Path.of(source.toURI()).toString();
            return ownLocation;
        } catch (final URISyntaxException e) {
            throw new IOException("clipboard helper classpath is unavailable", e);
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT)
            .contains("win");
    }

    public static void main(final String[] args) {
        if (args.length != 1) {
            System.err.println("missing exported image path");
            System.exit(2);
        }
        try {
            ownClipboard(Path.of(args[0]));
        } catch (final Throwable e) {
            System.err.println(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
            System.exit(1);
        }
    }

    private static void ownClipboard(final Path path) throws Exception {
        final MapExportClipboardImage image = MapExportClipboardImage.read(path);
        final CountDownLatch ownershipLost = new CountDownLatch(1);
        final ClipboardOwner owner = (clipboard, contents) -> ownershipLost.countDown();
        final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Exception lastFault = null;
        for (int attempt = 0; attempt < 4; attempt++) {
            try {
                clipboard.setContents(image, owner);
                System.out.println(READY);
                System.out.flush();
                ownershipLost.await(5, TimeUnit.MINUTES);
                return;
            } catch (final IllegalStateException e) {
                lastFault = e;
                TimeUnit.MILLISECONDS.sleep(50L * (attempt + 1));
            }
        }
        throw lastFault == null ? new IOException("clipboard is unavailable") : lastFault;
    }
}
