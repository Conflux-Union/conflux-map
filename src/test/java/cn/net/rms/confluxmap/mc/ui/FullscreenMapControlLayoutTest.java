package cn.net.rms.confluxmap.mc.ui;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;
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
    private static final Pattern LOCATION_MENU_RESPECTS_MAP_CONTROLS = Pattern.compile(
        "button\\s*==\\s*1\\s*&&\\s*!isOverMapControls\\(mouseX\\s*,\\s*mouseY\\)"
            + "\\s*\\)\\s*\\{\\s*openLocationMenu",
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
