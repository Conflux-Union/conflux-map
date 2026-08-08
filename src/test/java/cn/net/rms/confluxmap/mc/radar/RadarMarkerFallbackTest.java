package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RadarMarkerFallbackTest {
    @Test
    void doesNotTreatAnUnboundDynamicIconAsDrawn() throws IOException {
        final Path root = findProjectRoot();
        final String renderer = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar/RadarMarkerRenderer.java"
        ));

        assertTrue(renderer.contains("icon != null && drawIcon("));
        assertTrue(renderer.contains("private static boolean drawIcon("));
        assertTrue(renderer.contains("if (!iconManager.bindDynamicColor()) {\n                return false;"));
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
