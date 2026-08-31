package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class FishPortraitModelSelectionTest {
    private static final List<String> MIXIN_CONFIGS = List.of(
        "src/main/resources/confluxmap.mixins.json",
        "versions/1.18.2/src/main/resources/confluxmap.mixins.json",
        "versions/1.20.1/src/main/resources/confluxmap.mixins.json",
        "versions/1.21.1/src/main/resources/confluxmap.mixins.json",
        "versions/1.21.3/src/main/resources/confluxmap.mixins.json"
    );

    @Test
    void portraitBakeKeepsFishVariantModelsAndTropicalColorLayers() throws IOException {
        final Path root = findProjectRoot();
        final String manager = source(root, "src/main/java/cn/net/rms/confluxmap/mc/radar/EntityIconManager.java");

        assertTrue(manager.contains("TropicalFishEntityRendererAccessor"));
        assertTrue(manager.contains("PufferfishEntityRendererAccessor"));
        assertTrue(manager.contains("getPuffState()"));
        assertTrue(manager.contains("appearance.patternTexture()"));
        assertTrue(manager.contains("appearance.baseTint()"));
        assertTrue(manager.contains("appearance.patternTint()"));
    }

    @Test
    void fishRendererAccessorsLoadOnEverySupportedVersion() throws IOException {
        final Path root = findProjectRoot();
        for (final String config : MIXIN_CONFIGS) {
            final String contents = source(root, config);
            assertTrue(contents.contains("TropicalFishEntityRendererAccessor"), config);
            assertTrue(contents.contains("PufferfishEntityRendererAccessor"), config);
        }
    }

    private static String source(final Path root, final String file) throws IOException {
        return Files.readString(root.resolve(file)).replace("//$$ ", "");
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
