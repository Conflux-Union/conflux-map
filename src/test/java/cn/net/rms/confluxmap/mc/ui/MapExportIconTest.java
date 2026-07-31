package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

final class MapExportIconTest {
    private static final Pattern EXPORT_ICON_BUTTON = Pattern.compile(
        "mapExportButton\\s*=\\s*addDrawableChild\\(new MapIconButton\\("
            + "\\s*x\\s*,\\s*y\\s*,\\s*MAP_EXPORT_ICON\\s*,"
            + "\\s*Texts\\.translatable\\(\"confluxmap\\.screen\\.map_export\\.tooltip\"\\)\\s*,"
            + "\\s*0\\s*,\\s*ignored\\s*->\\s*openMapExport\\(\\)",
        Pattern.DOTALL
    );
    private static final Set<Integer> PALETTE = Set.of(
        0x00000000,
        0xFF101010,
        0xFFFFF0A0,
        0xFFFFD83D,
        0xFFC79A1E,
        0xFF55DDE0,
        0xFF3AA5AC
    );

    @Test
    void exportControlUsesCrispShadedPaperAndRightArrowIcon() throws IOException {
        final Path root = findProjectRoot();
        final String screen = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        ));
        assertTrue(
            EXPORT_ICON_BUTTON.matcher(screen).find(),
            "the export control must use the project icon button instead of a text label"
        );

        final BufferedImage icon = ImageIO.read(root.resolve(
            "src/main/resources/assets/confluxmap/textures/gui/map_export.png"
        ).toFile());
        assertEquals(32, icon.getWidth());
        assertEquals(32, icon.getHeight());
        assertTrue(icon.getColorModel().hasAlpha());
        assertEquals(PALETTE, colors(icon), "the icon must reuse the established toolbar palette");
        assertLogicalPixelsAreSharp(icon);

        assertEquals(0xFFFFF0A0, logicalPixel(icon, 6, 2), "paper top highlight");
        assertEquals(0xFFFFD83D, logicalPixel(icon, 2, 9), "paper left body");
        assertEquals(0xFFC79A1E, logicalPixel(icon, 7, 13), "paper bottom shadow");
        assertEquals(0xFFFFD83D, logicalPixel(icon, 10, 3), "upper inward paper hook");
        assertEquals(0xFFC79A1E, logicalPixel(icon, 10, 12), "lower inward paper hook");
        assertEquals(0xFF55DDE0, logicalPixel(icon, 8, 7), "arrow upper highlight");
        assertEquals(0xFF3AA5AC, logicalPixel(icon, 8, 8), "arrow lower shadow");
        assertEquals(0xFF55DDE0, logicalPixel(icon, 10, 4), "large arrowhead upper extent");
        assertEquals(0xFF3AA5AC, logicalPixel(icon, 10, 11), "large arrowhead lower extent");
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
                            "each logical pixel must remain a hard-edged 2x2 block"
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
