package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class MapExportWorkflowTest {
    @Test
    void exportStartsMapSelectionBeforeOpeningTheSettingsForm() throws IOException {
        final String source = source("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java");
        final int start = source.indexOf("private void openMapExport()");
        final int end = source.indexOf("    void beginExportSelection", start);

        assertTrue(start >= 0 && end > start, "map export entry point must be present");
        assertTrue(
            source.substring(start, end).contains("beginExportSelection(screen, false);"),
            "opening export must enter map-selection mode before showing the settings form"
        );
    }

    @Test
    void completedExportCopiesItsImageAndOffersItsContainingFolder() throws IOException {
        final String source = source("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/MapExportScreen.java");

        assertTrue(source.contains("desktopActions.copyImage("));
        assertTrue(source.contains("submittedRequest.pixelWidth()"));
        assertTrue(source.contains("confluxmap.screen.map_export.open_folder"));
        assertTrue(source.contains("desktopActions.openDirectory(status.output())"));
    }

    @Test
    void exportSelectionControlsAvoidMapStatusLabels() throws IOException {
        final String source = source("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java");
        final int controlsStart = source.indexOf("private void rebuildExportSelectionControls()");
        final int controlsEnd = source.indexOf("    private void rebuildWaypointControls", controlsStart);

        assertTrue(controlsStart >= 0 && controlsEnd > controlsStart, "selection controls must be present");
        final String controls = source.substring(controlsStart, controlsEnd);
        assertTrue(controls.contains("width - MARGIN - 80,\n            height - 32,"));
        assertFalse(
            controls.contains("confluxmap.screen.map_export.open_directory"),
            "the selection overlay must not cover the map's status labels with file actions"
        );
    }

    @Test
    void cancellingExportSelectionReturnsToItsCaller() throws IOException {
        final String source = source("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java");
        final int cancelStart = source.indexOf("private void cancelExportSelection()");
        final int cancelEnd = source.indexOf("    private void finishExportSelection", cancelStart);

        assertTrue(cancelStart >= 0 && cancelEnd > cancelStart, "selection cancellation must be present");
        final String cancellation = source.substring(cancelStart, cancelEnd);
        assertTrue(cancellation.contains("rebuildWaypointControls();"));
        assertTrue(cancellation.contains("if (screen != null && returnsToForm)"));
        assertTrue(cancellation.contains("MinecraftAccess.setScreen"));
    }

    @Test
    void rightClickCancelsTheEntireExportSelection() throws IOException {
        final String source = source("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java");
        final int mouseClickedStart = source.indexOf("public boolean mouseClicked");
        final int selectionStart = source.indexOf("if (exportSelectionScreen != null) {", mouseClickedStart);
        final int selectionEnd = source.indexOf("            if (button == 0)", selectionStart);

        assertTrue(mouseClickedStart >= 0 && selectionStart >= 0 && selectionEnd > selectionStart,
            "selection click handling must be present");
        final String selectionClicks = source.substring(selectionStart, selectionEnd);
        assertTrue(selectionClicks.contains("if (button == 1) {\n                cancelExportSelection();"));
        assertFalse(
            selectionClicks.contains("exportSelection.first().isEmpty()"),
            "right-click must not leave a partially completed export selection active"
        );
    }

    private static Path projectRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isRegularFile(current.resolve("common.gradle"))
                && Files.isDirectory(current.resolve("src/main/java"))) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Could not locate the Conflux Map project root");
    }

    private static String source(final String relativePath) throws IOException {
        return Files.readString(projectRoot().resolve(relativePath)).replace("\r\n", "\n");
    }
}
