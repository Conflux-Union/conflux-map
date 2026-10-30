package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/**
 * Guards the categorization of the layer-label toggle: it controls the minimap
 * information line, so it belongs to the MINIMAP category beside Show
 * Coordinates / Show Biome, not to the MAP layer settings.
 */
class ConfigRowsCategoryTest {
    private static final String LAYER_LABEL_KEY = "confluxmap.config.minimap.show_layer_label";
    private static final String RETIRED_LAYER_LABEL_KEY = "confluxmap.config.layers.show_indicator";

    @Test
    void layerLabelToggleIsCategorizedWithTheMinimapRows() throws IOException {
        final String screen = readConfigRows();

        final int minimapCase = screen.indexOf("case MINIMAP");
        final int mapCase = screen.indexOf("case MAP:");
        final int layerLabelRow = screen.indexOf("\"" + LAYER_LABEL_KEY + "\"");

        assertTrue(minimapCase >= 0);
        assertTrue(mapCase > minimapCase);
        assertTrue(layerLabelRow > minimapCase, "the layer label toggle should live in the MINIMAP category");
        assertTrue(layerLabelRow < mapCase, "the layer label toggle should not live in the MAP category");
    }

    @Test
    void layerLabelToggleFollowsTheBiomeToggle() throws IOException {
        final String screen = readConfigRows();

        final int showBiomeRow = screen.indexOf("\"confluxmap.config.minimap.show_biome\"");
        final int layerLabelRow = screen.indexOf("\"" + LAYER_LABEL_KEY + "\"");

        assertTrue(showBiomeRow >= 0);
        assertTrue(layerLabelRow > showBiomeRow, "the layer label toggle should come after the biome toggle");
    }

    @Test
    void retiredLayerCategoryKeyDoesNotComeBack() throws IOException {
        assertFalse(readConfigRows().contains(RETIRED_LAYER_LABEL_KEY));
    }

    private static String readConfigRows() throws IOException {
        return Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ConfigRows.java"
        ));
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
