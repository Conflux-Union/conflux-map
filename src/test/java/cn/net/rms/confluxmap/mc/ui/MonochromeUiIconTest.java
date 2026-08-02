package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class MonochromeUiIconTest {
    private static final Set<Integer> PALETTE = Set.of(0x00000000, 0xFFFFFFFF);
    private static final String[] ICONS = {
        "group_view.png",
        "group_waypoints.png",
        "group_actions.png",
        "world_profile.png",
        "map_terrain.png",
        "map_biome.png",
        "chunk_load_state.png",
        "map_export.png",
        "structure_search.png",
        "waypoint_local.png",
        "waypoint_shared.png",
        "waypoint_manage.png",
        "annotation_drawing.png",
        "annotation_collapse.png",
        "annotation_select.png",
        "annotation_line.png",
        "annotation_circle.png",
        "annotation_rectangle.png",
        "annotation_freehand.png",
        "annotation_eraser.png",
        "annotation_persistence.png",
        "annotation_label.png",
        "annotation_undo.png",
        "annotation_redo.png"
    };

    @Test
    void everyToolbarIconIsASharpMonochromePixelMask() throws IOException {
        for (final String fileName : ICONS) {
            final BufferedImage icon = ImageIO.read(iconPath(fileName).toFile());
            assertEquals(32, icon.getWidth(), fileName + " width");
            assertEquals(32, icon.getHeight(), fileName + " height");
            assertTrue(icon.getColorModel().hasAlpha(), fileName + " alpha");
            assertTrue(PALETTE.containsAll(colors(icon)), fileName + " must be monochrome");
            assertLogicalPixelsAreSharp(icon, fileName);
        }
    }

    @Test
    void everyDisplayModeUsesAProjectOwnedMonochromeIcon() {
        assertEquals(
            "confluxmap:textures/gui/map_terrain.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.TERRAIN).toString()
        );
        assertEquals(
            "confluxmap:textures/gui/chunk_load_state.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.CHUNK_LOAD_STATE).toString()
        );
        assertEquals(
            "confluxmap:textures/gui/map_biome.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.BIOME).toString()
        );
    }

    private static Set<Integer> colors(final BufferedImage image) {
        final java.util.HashSet<Integer> colors = new java.util.HashSet<>();
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                colors.add(image.getRGB(x, y));
            }
        }
        return colors;
    }

    private static void assertLogicalPixelsAreSharp(
        final BufferedImage icon,
        final String fileName
    ) {
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                final int expected = icon.getRGB(x * 2, y * 2);
                for (int dy = 0; dy < 2; dy++) {
                    for (int dx = 0; dx < 2; dx++) {
                        assertEquals(
                            expected,
                            icon.getRGB(x * 2 + dx, y * 2 + dy),
                            fileName + " logical pixel " + x + "," + y
                        );
                    }
                }
            }
        }
    }

    private static Path iconPath(final String fileName) {
        return findProjectRoot().resolve(
            "src/main/resources/assets/confluxmap/textures/gui/" + fileName
        );
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
