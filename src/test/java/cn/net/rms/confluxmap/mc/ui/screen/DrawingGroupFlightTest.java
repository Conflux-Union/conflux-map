package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class DrawingGroupFlightTest {
    @Test
    void drawingGroupFliesOutOfItsOwnGroupButton() throws IOException {
        final String source = read("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java");
        final String origin = between(
            source,
            "private MapIconButton expandedGroupButton()",
            "/**\n     * Drives the expanded tool group's staggered fly-out"
        );

        assertTrue(
            origin.contains("Group.DRAWING) {\n            return drawingGroupButton;"),
            "the drawing grid must fly out of its group button like every other group"
        );
    }

    @Test
    void drawingGridButtonsAreTrackedIntoTheFlight() throws IOException {
        final String source = read(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/MapAnnotationController.java"
        );
        final String rebuild = between(
            source,
            "void rebuildAnnotationControls(final int desiredTop) {",
            "private void rebuildAnnotationColorMenu()"
        );

        // Every toolbar control: the tool loop plus the seven extras after it.
        final int tracked = count(rebuild, "screen.trackExpansionButton(");
        assertTrue(
            tracked >= 8,
            "all toolbar controls must be tracked, found " + tracked + " call sites"
        );
        assertTrue(
            rebuild.contains("screen.trackExpansionButton(addColorButton("),
            "the anonymous color swatch must ride the flight too"
        );
    }

    @Test
    void colorSwatchFadesWithTheFlightThroughWidgetAlpha() throws IOException {
        final String source = read(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/MapAnnotationController.java"
        );
        final String render = between(
            source,
            "private void renderAnnotationColorButton(",
            "private void refreshAnnotationControls()"
        );

        assertTrue(
            render.contains("Argb.scaleAlpha(displayedColor | 0xFF000000, alpha)"),
            "the swatch fill must scale by the widget alpha the flight sets"
        );
    }

    private static int count(final String haystack, final String needle) {
        int count = 0;
        for (int index = haystack.indexOf(needle); index >= 0; index = haystack.indexOf(needle, index + 1)) {
            count++;
        }
        return count;
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "expected source section must exist");
        return source.substring(startIndex, endIndex);
    }

    private static String read(final String path) throws IOException {
        return Files.readString(projectRoot().resolve(path)).replace("\r\n", "\n");
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
        throw new IllegalStateException("project root not found");
    }
}
