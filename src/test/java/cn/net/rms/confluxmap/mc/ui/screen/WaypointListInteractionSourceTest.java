package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class WaypointListInteractionSourceTest {
    @Test
    void localWaypointsUseAnExplicitEditButtonInsteadOfANameClickTarget() throws IOException {
        final String source = Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/WaypointListScreen.java"
        ));
        final String rowWidgets = sourceBetween(
            source,
            "private void addRowWidgets(",
            "\n    private ButtonWidget addIconAction("
        );
        final String mouseClicked = sourceBetween(
            source,
            "public boolean mouseClicked(final double mouseX",
            "\n    @Override\n    //#if MC>=12109\n    //$$ public boolean mouseDragged("
        );

        assertTrue(rowWidgets.contains(
            "Texts.translatable(\"confluxmap.screen.waypoints.edit\")"
        ));
        assertTrue(rowWidgets.contains("button -> openEdit(renderedStore, waypoint)"));
        assertFalse(mouseClicked.contains("openEdit("));
    }

    private static String sourceBetween(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        if (startIndex < 0 || endIndex < 0) {
            throw new IllegalArgumentException("Could not locate source range");
        }
        return source.substring(startIndex, endIndex);
    }

    private static Path findProjectRoot() {
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
}
