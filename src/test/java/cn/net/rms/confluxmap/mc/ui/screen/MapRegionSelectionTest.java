package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class MapRegionSelectionTest {
    @Test
    void selectionDrawsBetweenMarkersAndMeasureOverlay() throws IOException {
        final String source = source();
        final int start = source.indexOf("protected void renderContents(");
        final int end = source.indexOf("    private void drawExportSelection", start);

        assertTrue(start >= 0 && end > start, "renderContents must be present");
        final String render = source.substring(start, end);
        final int markers = render.indexOf("drawPlayerMarker(matrices, tickDelta);");
        final int selection = render.indexOf("drawRegionSelection(draw, mouseX, mouseY);");
        final int measure = render.indexOf("drawMeasureOverlay(draw, mouseX, mouseY);");
        assertTrue(selection > markers && measure > selection,
            "the region selection must draw above markers and below the measure overlay"
        );
    }

    @Test
    void rightPressArmsSelectionInsteadOfOpeningTheMenu() throws IOException {
        final String source = source();
        final int start = source.indexOf("public boolean mouseClicked");
        final int end = source.indexOf("    private boolean beginAnnotationPointer", start);

        assertTrue(start >= 0 && end > start, "mouseClicked must be present");
        final String clicks = source.substring(start, end);
        assertTrue(clicks.indexOf("regionSelection = null;") < clicks.indexOf("selectTargetDropdownOption"),
            "any press must clear the committed selection first"
        );
        final int defaultBranch = clicks.indexOf("if (button == MouseButtons.LEFT && beginAnnotationPointer");
        final String defaultClicks = clicks.substring(defaultBranch);
        assertTrue(defaultClicks.contains("regionSelectPress = true;"),
            "the plain-map right press must arm the region selection"
        );
        assertFalse(defaultClicks.contains("openLocationMenu("),
            "the location menu must wait for a non-dragging release, not open on press"
        );
    }

    @Test
    void rightReleaseCommitsASnappedSelectionOrOpensTheMenu() throws IOException {
        final String source = source();
        final int start = source.indexOf("public boolean mouseReleased");
        final int end = source.indexOf("    private void commitAnnotationPointer", start);

        assertTrue(start >= 0 && end > start, "mouseReleased must be present");
        final String releases = source.substring(start, end);
        assertTrue(releases.contains("button == MouseButtons.RIGHT && regionSelectPress"));
        assertTrue(releases.contains("ChunkSelectionMath.betweenBlocks("),
            "a dragging release must commit the chunk-snapped selection"
        );
        assertTrue(releases.contains("openLocationMenu(mouseX, mouseY);"),
            "a non-dragging release must still open the location menu"
        );
    }

    @Test
    void selectionLabelShowsBothChunkAndBlockSizes() throws IOException {
        final String source = source();
        final int start = source.indexOf("private void drawRegionSelectionLabel(");
        final int end = source.indexOf("    private void drawPlayerTrail", start);

        assertTrue(start >= 0 && end > start, "the selection label must be present");
        final String label = source.substring(start, end);
        assertTrue(label.contains("confluxmap.map.selection.chunks"));
        assertTrue(label.contains("confluxmap.map.selection.blocks"));
        assertTrue(label.contains("mapOverlayIntersectsUi("),
            "the label must not cover the map's control widgets"
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

    private static String source() throws IOException {
        return Files.readString(projectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        )).replace("\r\n", "\n");
    }
}
