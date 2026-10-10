package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class MapExportDesktopActionsTest {
    @Test
    void copyAndOpenReportDesktopResultsWithoutThrowing() {
        final AtomicReference<Path> copied = new AtomicReference<>();
        final AtomicReference<Path> opened = new AtomicReference<>();
        final MapExportDesktopActions actions = new MapExportDesktopActions(
            new MapExportDesktopActions.DesktopBridge() {
                @Override
                public void copyImage(final Path path) {
                    copied.set(path);
                }

                @Override
                public void openDirectory(final Path path) {
                    opened.set(path);
                }
            },
            Runnable::run
        );
        final Path output = Path.of("/tmp/export.png");

        actions.copyImage(output);
        actions.openDirectory(output);

        assertEquals(output, copied.get());
        assertEquals(output.getParent(), opened.get());
        assertEquals(MapExportDesktopActions.CopyState.COPIED, actions.copyState());
        assertEquals(MapExportDesktopActions.OpenState.OPENED, actions.openState());
    }

    @Test
    void desktopBridgeFallsBackWhenAwtIsUnavailable() throws Exception {
        final AtomicReference<Path> copied = new AtomicReference<>();
        final AtomicReference<Path> opened = new AtomicReference<>();
        final MapExportDesktopActions.DesktopBridge bridge = MapExportDesktopActions.withFallback(
            new MapExportDesktopActions.DesktopBridge() {
                @Override
                public void copyImage(final Path path) throws IOException {
                    throw new IOException("AWT clipboard is unavailable");
                }

                @Override
                public void openDirectory(final Path path) throws IOException {
                    throw new IOException("AWT desktop is unavailable");
                }
            },
            new MapExportDesktopActions.DesktopBridge() {
                @Override
                public void copyImage(final Path path) {
                    copied.set(path);
                }

                @Override
                public void openDirectory(final Path path) {
                    opened.set(path);
                }
            }
        );
        final Path image = Path.of("/tmp/export.png");
        final Path directory = Path.of("/tmp");

        bridge.copyImage(image);
        bridge.openDirectory(directory);

        assertEquals(image, copied.get());
        assertEquals(directory, opened.get());
    }
}
