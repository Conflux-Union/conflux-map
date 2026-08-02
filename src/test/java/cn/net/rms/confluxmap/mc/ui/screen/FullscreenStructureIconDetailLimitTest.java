package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FullscreenStructureIconDetailLimitTest {
    @Test
    void fullscreenMapUsesTheConfiguredStructureIconDetailLimit() throws IOException {
        final String source = Files.readString(projectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        ));
        final int start = source.indexOf("private void drawStructures");
        final int end = source.indexOf("    private static List<StructureIndex.Marker> limitStructureMarkers", start);

        assertTrue(start >= 0 && end > start, "structure renderer must be present");
        final String renderer = source.substring(start, end);
        assertTrue(renderer.contains("currentLod() > config.predictionStructureMaxLod"));
        assertFalse(renderer.contains("currentLod() > 3"));
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
}
