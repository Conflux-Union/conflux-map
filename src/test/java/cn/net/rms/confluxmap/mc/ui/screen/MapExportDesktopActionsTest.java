package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class MapExportDesktopActionsTest {
    @Test
    void clipboardPreflightPreservesTheHeapReserve() {
        final long mib = 1024L * 1024L;

        assertTrue(MapExportDesktopActions.hasClipboardHeadroom(
            1024, 1024, 512 * mib, 100 * mib
        ));
        assertFalse(MapExportDesktopActions.hasClipboardHeadroom(
            8192, 8192, 512 * mib, 200 * mib
        ));
    }

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
            Runnable::run,
            () -> new MapExportDesktopActions.MemorySnapshot(1024L << 20, 0L)
        );
        final Path output = Path.of("/tmp/export.png");

        actions.copyImage(output, 32, 32);
        actions.openDirectory(output);

        assertEquals(output, copied.get());
        assertEquals(output.getParent(), opened.get());
        assertEquals(MapExportDesktopActions.CopyState.COPIED, actions.copyState());
        assertEquals(MapExportDesktopActions.OpenState.OPENED, actions.openState());
    }
}
