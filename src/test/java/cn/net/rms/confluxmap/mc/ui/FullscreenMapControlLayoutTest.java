package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;

final class FullscreenMapControlLayoutTest {
    private static final Pattern STRUCTURE_SEARCH_ICON_BUTTON = Pattern.compile(
        "structureSearchButton\\s*=\\s*addDrawableChild\\(new MapIconButton\\(\\s*x\\s*,\\s*y\\s*,"
            + "\\s*STRUCTURE_SEARCH_ICON\\s*,"
            + "\\s*Texts\\.translatable\\(\"confluxmap\\.map\\.structure_search\"\\)",
        Pattern.DOTALL
    );
    private static final String SEARCH_ICON_TEXTURE =
        "src/main/resources/assets/confluxmap/textures/gui/structure_search.png";
    private static final String LOAD_STATE_ICON_TEXTURE =
        "src/main/resources/assets/confluxmap/textures/gui/chunk_load_state.png";
    private static final Pattern LOCATION_MENU_RESPECTS_MAP_CONTROLS = Pattern.compile(
        "button\\s*==\\s*1\\s*&&\\s*!isOverMapControls\\(mouseX\\s*,\\s*mouseY\\)"
            + "\\s*\\)\\s*\\{\\s*openLocationMenu",
        Pattern.DOTALL
    );
    private static final Pattern CAPABILITY_GATED_LOAD_STATE_ICON_BUTTON = Pattern.compile(
        "if\\s*\\(chunkLoadStates\\.available\\(\\)\\)\\s*\\{"
            + ".*?loadStateToggleButton\\s*=\\s*addDrawableChild\\(new MapIconButton\\("
            + "\\s*x\\s*,\\s*y\\s*,\\s*LOAD_STATE_ICON\\s*,"
            + "\\s*loadStateToggleTooltip\\(\\)\\s*,"
            + "\\s*LOAD_STATE_CONTROL_ACCENT\\s*,"
            + "\\s*ignored\\s*->\\s*toggleLoadStateOverlay\\(\\)",
        Pattern.DOTALL
    );
    private static final Pattern LOAD_STATE_TOOLTIP = Pattern.compile(
        "loadStateToggleButton\\s*!=\\s*null\\s*&&\\s*loadStateToggleButton\\.isHovered\\(\\)"
            + "\\s*\\)\\s*\\{\\s*tooltip\\s*=\\s*loadStateToggleTooltip\\(\\)",
        Pattern.DOTALL
    );

    @Test
    void structureSearchUsesTheSquareIconButtonInTheWaypointControlColumn() throws IOException {
        final Path root = findProjectRoot();
        final String text = Files.readString(
            root.resolve("src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java")
        );

        assertTrue(
            STRUCTURE_SEARCH_ICON_BUTTON.matcher(text).find(),
            "structure search must use MapIconButton at the same x/y control-column position"
        );
        assertTrue(
            Files.isRegularFile(root.resolve(SEARCH_ICON_TEXTURE)),
            "the icon button must have a magnifying-glass texture to render"
        );
        assertTrue(
            LOCATION_MENU_RESPECTS_MAP_CONTROLS.matcher(text).find(),
            "right-click location actions must not replace any map control, including search"
        );
    }

    @Test
    void loadStateToggleIsHiddenWhenUnsupportedAndUsesSquareControlWithTooltip() throws IOException {
        final Path root = findProjectRoot();
        final String text = Files.readString(
            root.resolve(
                "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
            )
        );

        assertTrue(
            CAPABILITY_GATED_LOAD_STATE_ICON_BUTTON.matcher(text).find(),
            "load-state control must be created in the right-hand x/y column only when supported"
        );
        assertTrue(
            LOAD_STATE_TOOLTIP.matcher(text).find(),
            "the icon-only load-state control must expose a hover tooltip"
        );
        final Path iconPath = root.resolve(LOAD_STATE_ICON_TEXTURE);
        assertTrue(
            Files.isRegularFile(iconPath),
            "the load-state control must use a project PNG matching the existing icon set"
        );
        final BufferedImage icon = ImageIO.read(iconPath.toFile());
        assertEquals(32, icon.getWidth(), "load-state icon must match the existing texture width");
        assertEquals(32, icon.getHeight(), "load-state icon must match the existing texture height");
        assertTrue(icon.getColorModel().hasAlpha(), "load-state icon must preserve transparency");
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                final int sampleX = (1 + column * 5 + 2) * 2;
                final int sampleY = (1 + row * 5 + 1) * 2;
                final int expected = row == 1 && column == 1 ? 0xFF55DDE0 : 0xFFFFD83D;
                assertEquals(
                    expected,
                    icon.getRGB(sampleX, sampleY),
                    "only the center chunk may use the load-state accent"
                );
            }
        }
        assertFalse(
            text.contains("displayModeButton"),
            "the old centered display-mode button must not remain"
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
