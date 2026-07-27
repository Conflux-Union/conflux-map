package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class DisplayModeIconCatalogTest {
    @Test
    void eachDisplayModeHasItsOwnPixelArtIcon() throws IOException {
        assertProjectIcon(FullscreenDisplayMode.TERRAIN, "map_terrain.png");
        assertProjectIcon(FullscreenDisplayMode.CHUNK_LOAD_STATE, "chunk_load_state.png");
        assertEquals(
            "minecraft:textures/block/oak_sapling.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.BIOME).toString()
        );
        assertFalse(
            Files.exists(findProjectRoot().resolve(
                "src/main/resources/assets/confluxmap/textures/gui/map_biome.png"
            )),
            "biome mode must use the vanilla item-model sprite instead of a bundled imitation"
        );

        assertNotEquals(
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.TERRAIN),
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.CHUNK_LOAD_STATE)
        );
        assertNotEquals(
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.CHUNK_LOAD_STATE),
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.BIOME)
        );
        assertNotEquals(
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.BIOME),
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.TERRAIN)
        );
    }

    @Test
    void terrainIconDropsTheFormerRiverPalette() throws IOException {
        final BufferedImage icon = ImageIO.read(projectIconPath("map_terrain.png").toFile());
        final Set<Integer> colors = colors(icon);

        assertFalse(colors.contains(0xFF55DDE0), "terrain icon must not retain the cyan river plane");
        assertFalse(colors.contains(0xFF3AA5AC), "terrain icon must not retain the cyan river shadow");
    }

    @Test
    void terrainIconKeepsAllThreeFoldFacesClearOfOverlaySymbols() throws IOException {
        final BufferedImage icon = ImageIO.read(projectIconPath("map_terrain.png").toFile());

        assertLogicalRun(icon, 2, 4, 8, 0xFFFFF0A0, "left highlight face");
        assertLogicalRun(icon, 6, 9, 8, 0xFFFFD83D, "center primary face");
        assertLogicalRun(icon, 11, 13, 8, 0xFFC79A1E, "right shadow face");
    }

    private static void assertProjectIcon(
        final FullscreenDisplayMode mode,
        final String fileName
    ) throws IOException {
        assertEquals(
            "confluxmap:textures/gui/" + fileName,
            DisplayModeIconCatalog.icon(mode).toString()
        );
        final Path iconPath = projectIconPath(fileName);
        assertTrue(Files.isRegularFile(iconPath), mode + " icon must exist");
        final BufferedImage icon = ImageIO.read(iconPath.toFile());
        assertEquals(32, icon.getWidth(), mode + " icon width");
        assertEquals(32, icon.getHeight(), mode + " icon height");
        assertTrue(icon.getColorModel().hasAlpha(), mode + " icon must preserve transparency");
        assertShadedLikeExistingToolbarIcons(icon, mode);
    }

    private static void assertShadedLikeExistingToolbarIcons(
        final BufferedImage icon,
        final FullscreenDisplayMode mode
    ) {
        final Set<Integer> colors = colors(icon);
        assertTrue(colors.contains(0xFF101010), mode + " icon must have the existing dark outline");
        assertTrue(colors.contains(0xFFFFF0A0), mode + " icon must have a highlight plane");
        assertTrue(colors.contains(0xFFFFD83D), mode + " icon must have the primary yellow plane");
        assertTrue(colors.contains(0xFFC79A1E), mode + " icon must have a shaded yellow plane");
    }

    private static Set<Integer> colors(final BufferedImage icon) {
        final Set<Integer> colors = new HashSet<>();
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                colors.add(icon.getRGB(x, y));
            }
        }
        return colors;
    }

    private static void assertLogicalRun(
        final BufferedImage icon,
        final int fromX,
        final int toX,
        final int y,
        final int expected,
        final String face
    ) {
        for (int x = fromX; x <= toX; x++) {
            assertEquals(expected, icon.getRGB(x * 2, y * 2), face + " must stay clear");
        }
    }

    private static Path projectIconPath(final String fileName) {
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
