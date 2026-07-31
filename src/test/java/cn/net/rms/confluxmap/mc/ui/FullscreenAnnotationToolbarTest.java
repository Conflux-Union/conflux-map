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
        "controlCount\\s*=\\s*annotationToolbarExpanded"
            + "\\s*\\?\\s*AnnotationTool\\.values\\(\\)\\.length\\s*\\+\\s*6"
            + "\\s*:\\s*1",
        Pattern.DOTALL
    );
    private static final Pattern TOGGLE_ACTION = Pattern.compile(
        "toggleAnnotationToolbar\\(\\)\\s*\\{"
            + "\\s*annotationToolbarExpanded\\s*=\\s*!annotationToolbarExpanded;"
            + "\\s*annotationColorMenuOpen\\s*=\\s*false;"
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
            "the collapsed toolbar must reserve only its toggle button"
        );
        assertTrue(
            TOGGLE_ACTION.matcher(screen).find(),
            "the toggle must flip the state, close the color menu, and rebuild controls"
        );
        assertTrue(
            screen.contains(
                "final int toggleIndex = annotationToolbarExpanded ? (columns - 1) * rows : 0;"
            ),
            "the expanded toggle must occupy the top-right slot without moving existing tools"
        );
        assertTrue(
            screen.contains("return index >= toggleIndex ? index + 1 : index;"),
            "existing drawing controls must retain their order around the inserted toggle"
        );
    }

    @Test
    void collapsedToggleUsesShadedBrushWhileExpandedToggleShowsChevron() throws IOException {
        final String screen = Files.readString(screenPath());
        assertTrue(screen.contains("ANNOTATION_DRAWING_ICON"));
        assertTrue(screen.contains("Texts.literal(\">\")"));
        assertTrue(screen.contains("confluxmap.map.annotation.toolbar.open.tooltip"));
        assertTrue(screen.contains("confluxmap.map.annotation.toolbar.close.tooltip"));

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
