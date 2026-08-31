package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class MonochromeUiIconTest {
    private static final int ICON_SIZE = 64;
    /** Design-space margin the generator keeps clear, in texture pixels. */
    private static final int MARGIN = 2;
    private static final String[] ICONS = {
        "group_view.png",
        "group_waypoints.png",
        "group_actions.png",
        "map_settings.png",
        "world_profile.png",
        "map_terrain.png",
        "map_biome.png",
        "map_biome_off.png",
        "chunk_load_state.png",
        "chunk_load_state_off.png",
        "map_export.png",
        "structure_search.png",
        "structure_search_off.png",
        "waypoint_local.png",
        "waypoint_local_off.png",
        "waypoint_shared.png",
        "waypoint_shared_off.png",
        "waypoint_manage.png",
        "waypoint_visible.png",
        "waypoint_hidden.png",
        "waypoint_share.png",
        "annotation_drawing.png",
        "annotation_collapse.png",
        "annotation_select.png",
        "annotation_line.png",
        "annotation_circle.png",
        "annotation_rectangle.png",
        "annotation_freehand.png",
        "annotation_eraser.png",
        "annotation_persistence.png",
        "annotation_persistence_transient.png",
        "annotation_label.png",
        "annotation_undo.png",
        "annotation_redo.png"
    };

    /** Keeps the declared contract and the shipped folder in step, in both directions. */
    @Test
    void theIconFolderHoldsExactlyTheDeclaredIcons() throws IOException {
        try (var entries = Files.list(iconDirectory())) {
            final Set<String> shipped = entries
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".png"))
                .collect(Collectors.toCollection(TreeSet::new));
            assertEquals(new TreeSet<>(Set.of(ICONS)), shipped);
        }
    }

    @Test
    void everyToolbarIconHasAnExplicitXaeroCompatibilityDecision() {
        final Set<String> expected = Arrays.stream(ICONS)
            .map(file -> "confluxmap:textures/gui/" + file)
            .collect(Collectors.toCollection(TreeSet::new));
        final Set<String> audited = UiResourceTheme.auditedIconIds().stream()
            .map(Object::toString)
            .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(expected, audited);
    }

    @Test
    void everyToolbarIconIsAWhiteAlphaMaskAtIconResolution() throws IOException {
        for (final String fileName : ICONS) {
            final BufferedImage icon = ImageIO.read(iconPath(fileName).toFile());
            assertEquals(ICON_SIZE, icon.getWidth(), fileName + " width");
            assertEquals(ICON_SIZE, icon.getHeight(), fileName + " height");
            assertTrue(icon.getColorModel().hasAlpha(), fileName + " alpha");
            assertColourChannelsAreWhite(icon, fileName);
            assertDrawnWithinMargin(icon, fileName);
        }
    }

    /**
     * The icons are drawn at 4x the 16px button slot, which only looks smooth when Minecraft
     * samples them with GL_LINEAR - that filter comes from the texture metadata, not from code.
     */
    @Test
    void everyToolbarIconRequestsBlurredSampling() throws IOException {
        for (final String fileName : ICONS) {
            final Path metadata = iconPath(fileName + ".mcmeta");
            assertTrue(Files.isRegularFile(metadata), fileName + " is missing its .mcmeta");
            final String contents = Files.readString(metadata).replaceAll("\\s+", "");
            assertTrue(contents.contains("\"blur\":true"), fileName + " must request blur");
            assertTrue(contents.contains("\"clamp\":true"), fileName + " must request clamp");
        }
    }

    @Test
    void hiddenWaypointIconUsesADiagonalSlash() throws IOException {
        final BufferedImage icon = ImageIO.read(iconPath("waypoint_hidden.png").toFile());

        assertTrue((icon.getRGB(12, 12) >>> 24) > 200);
        assertTrue((icon.getRGB(51, 51) >>> 24) > 200);
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

    @Test
    void unavailableDisplayModesUseTheSlashedVariant() {
        assertEquals(
            "confluxmap:textures/gui/chunk_load_state_off.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.CHUNK_LOAD_STATE, false).toString()
        );
        assertEquals(
            "confluxmap:textures/gui/map_biome_off.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.BIOME, false).toString()
        );
        assertEquals(
            "confluxmap:textures/gui/map_biome.png",
            DisplayModeIconCatalog.icon(FullscreenDisplayMode.BIOME, true).toString()
        );
    }

    /**
     * Antialiasing has to live in the alpha channel alone. A mask whose colour channels fade
     * toward black would darken every tint the toolbar applies, leaving grey fringes.
     */
    private static void assertColourChannelsAreWhite(
        final BufferedImage icon,
        final String fileName
    ) {
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                assertEquals(
                    0xFFFFFF,
                    icon.getRGB(x, y) & 0xFFFFFF,
                    fileName + " colour at " + x + "," + y
                );
            }
        }
    }

    private static void assertDrawnWithinMargin(final BufferedImage icon, final String fileName) {
        boolean opaqueFound = false;
        for (int y = 0; y < icon.getHeight(); y++) {
            for (int x = 0; x < icon.getWidth(); x++) {
                final int alpha = icon.getRGB(x, y) >>> 24;
                opaqueFound |= alpha == 0xFF;
                final boolean inMargin = x < MARGIN
                    || y < MARGIN
                    || x >= icon.getWidth() - MARGIN
                    || y >= icon.getHeight() - MARGIN;
                if (inMargin) {
                    assertEquals(0, alpha, fileName + " margin at " + x + "," + y);
                }
            }
        }
        assertTrue(opaqueFound, fileName + " must draw something");
    }

    private static Path iconDirectory() {
        return findProjectRoot().resolve("src/main/resources/assets/confluxmap/textures/gui");
    }

    private static Path iconPath(final String fileName) {
        return iconDirectory().resolve(fileName);
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
