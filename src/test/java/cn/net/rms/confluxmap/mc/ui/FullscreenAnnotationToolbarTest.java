package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FullscreenAnnotationToolbarTest {
    private static final Pattern DEFAULT_COLLAPSED_LAYOUT = Pattern.compile(
        "if\\s*\\(\\s*!annotationToolbarExpanded\\s*\\)\\s*\\{"
            + ".*?new MapIconButton\\(\\s*toggleX\\s*,\\s*toggleY\\s*,"
            + "\\s*CONTROL_SIZE\\s*,\\s*ANNOTATION_DRAWING_ICON"
            + "\\s*,\\s*Texts\\.literal\\(\"\"\\)\\s*,\\s*0\\s*,"
            + ".*?refreshAnnotationControls\\(\\);\\s*return;",
        Pattern.DOTALL
    );
    private static final Pattern EXPANDED_TOGGLE_USES_MAIN_CONTROL_SIZE = Pattern.compile(
        "new MapIconButton\\(\\s*toggleX\\s*,\\s*toggleY\\s*,"
            + "\\s*CONTROL_SIZE\\s*,\\s*ANNOTATION_COLLAPSE_ICON"
            + "\\s*,\\s*Texts\\.literal\\(\"\"\\)\\s*,\\s*0\\s*,"
            + "\\s*ignored\\s*->\\s*toggleAnnotationToolbar\\(\\)",
        Pattern.DOTALL
    );
    private static final Pattern COLLAPSE_RESETS_TOOL = Pattern.compile(
        "toggleAnnotationToolbar\\(\\)\\s*\\{"
            + "\\s*annotationToolbarExpanded\\s*=\\s*!annotationToolbarExpanded;"
            + "\\s*annotationColorMenuOpen\\s*=\\s*false;"
            + "\\s*if\\s*\\(\\s*!annotationToolbarExpanded\\s*\\)\\s*\\{"
            + "\\s*selectAnnotationTool\\(AnnotationTool\\.SELECT\\);"
            + "\\s*return;\\s*}"
            + "\\s*rebuildWaypointControls\\(\\);",
        Pattern.DOTALL
    );
    private static final Set<Integer> ICON_PALETTE = Set.of(
        0x00000000,
        0xFF101010,
        0xFFFFF0A0,
        0xFFFFD83D,
        0xFFC79A1E,
        0xFFE4E4E4
    );
    private static final Set<Integer> COLLAPSE_ICON_PALETTE = Set.of(
        0x00000000,
        0xFF101010,
        0xFFFFF0A0,
        0xFFFFD83D,
        0xFFC79A1E
    );

    @Test
    void drawingToolbarStartsCollapsedAndExpandsThroughTheSameToggle() throws IOException {
        final String screen = Files.readString(screenPath());

        assertTrue(
            screen.contains("private boolean annotationToolbarExpanded;"),
            "the per-screen toolbar state must use Java's false default"
        );
        assertFalse(
            screen.contains("annotationToolbarExpanded = true"),
            "opening a new fullscreen map must not expand drawing controls"
        );
        assertTrue(
            DEFAULT_COLLAPSED_LAYOUT.matcher(screen).find(),
            "the collapsed toolbar must render only a primary-sized brush toggle"
        );
    }

    @Test
    void toolbarToggleMatchesPrimaryMapControlSizeInBothStates() throws IOException {
        final String screen = Files.readString(screenPath());
        assertTrue(
            DEFAULT_COLLAPSED_LAYOUT.matcher(screen).find(),
            "the collapsed brush must use the 22px primary map-control size"
        );
        assertTrue(
            EXPANDED_TOGGLE_USES_MAIN_CONTROL_SIZE.matcher(screen).find(),
            "the expanded chevron must remain the same 22px size"
        );
    }

    @Test
    void collapsingToolbarReturnsToSelectMoveTool() throws IOException {
        final String screen = Files.readString(screenPath());
        assertTrue(
            COLLAPSE_RESETS_TOOL.matcher(screen).find(),
            "collapsing must select the default move tool and cancel active drawing state"
        );
    }

    @Test
    void collapsedToggleUsesShadedBrushIcon() throws IOException {
        final String screen = Files.readString(screenPath());
        assertTrue(screen.contains("ANNOTATION_DRAWING_ICON"));
        assertTrue(screen.contains("ANNOTATION_COLLAPSE_ICON"));
        assertTrue(screen.contains("confluxmap.map.annotation.toolbar.open.tooltip"));

        final BufferedImage icon = ImageIO.read(findProjectRoot().resolve(
            "src/main/resources/assets/confluxmap/textures/gui/annotation_drawing.png"
        ).toFile());
        assertEquals(32, icon.getWidth());
        assertEquals(32, icon.getHeight());
        assertTrue(icon.getColorModel().hasAlpha());
        assertEquals(ICON_PALETTE, colors(icon));
        assertLogicalPixelsAreSharp(icon);

        assertEquals(0xFFFFF0A0, logicalPixel(icon, 6, 2), "handle highlight");
        assertEquals(0xFFFFD83D, logicalPixel(icon, 7, 2), "handle body");
        assertEquals(0xFFC79A1E, logicalPixel(icon, 9, 2), "handle shadow");
        assertEquals(0xFFE4E4E4, logicalPixel(icon, 6, 8), "metal ferrule");
        assertEquals(0xFFFFD83D, logicalPixel(icon, 7, 11), "brush body");
        assertEquals(0xFFC79A1E, logicalPixel(icon, 7, 14), "brush-tip shadow");

        final BufferedImage collapseIcon = ImageIO.read(findProjectRoot().resolve(
            "src/main/resources/assets/confluxmap/textures/gui/annotation_collapse.png"
        ).toFile());
        assertEquals(32, collapseIcon.getWidth());
        assertEquals(32, collapseIcon.getHeight());
        assertTrue(collapseIcon.getColorModel().hasAlpha());
        assertEquals(COLLAPSE_ICON_PALETTE, colors(collapseIcon));
        assertLogicalPixelsAreSharp(collapseIcon);
        assertEquals(0xFFFFF0A0, logicalPixel(collapseIcon, 4, 3), "upper highlight");
        assertEquals(0xFFFFD83D, logicalPixel(collapseIcon, 8, 7), "chevron point");
        assertEquals(0xFFC79A1E, logicalPixel(collapseIcon, 4, 12), "lower shadow");
    }

    private static void assertLogicalPixelsAreSharp(final BufferedImage icon) {
        for (int logicalY = 0; logicalY < 16; logicalY++) {
            for (int logicalX = 0; logicalX < 16; logicalX++) {
                final int expected = logicalPixel(icon, logicalX, logicalY);
                for (int offsetY = 0; offsetY < 2; offsetY++) {
                    for (int offsetX = 0; offsetX < 2; offsetX++) {
                        assertEquals(
                            expected,
                            icon.getRGB(logicalX * 2 + offsetX, logicalY * 2 + offsetY),
                            "each logical pixel must stay a hard-edged 2x2 block"
                        );
                    }
                }
            }
        }
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

    private static int logicalPixel(
        final BufferedImage icon,
        final int logicalX,
        final int logicalY
    ) {
        return icon.getRGB(logicalX * 2, logicalY * 2);
    }

    private static Path screenPath() {
        return findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
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
