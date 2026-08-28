package cn.net.rms.confluxmap.mc.radar;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class RadarPlayerVisibilityTest {
    @Test
    void minimapAlwaysShowsPlayersButOnlyRevealsNamesWhilePlayerListKeyIsHeld() throws IOException {
        final Path root = findProjectRoot();
        final String minimap = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/hud/MinimapHudRenderer.java"
        ));
        final String fullscreen = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        ));
        final String renderer = Files.readString(root.resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/radar/RadarMarkerRenderer.java"
        ));
        final String playerFilter =
            "if (!showPlayers && entry.category() == RadarCategory.PLAYER)";

        assertTrue(minimap.contains(
            "final boolean playerListPressed = MinecraftAccess.isPlayerListKeyPressed(client);"
        ));
        assertTrue(minimap.contains(
            "final RadarMarkerRenderer.Presentation presentation = playerListPressed\n"
                + "            ? RadarMarkerRenderer.Presentation.detailed(config.radarShowPlayerNames)\n"
                + "            : RadarMarkerRenderer.Presentation.compact();"
        ));
        assertFalse(minimap.contains(playerFilter));
        assertTrue(minimap.contains(
            "RadarMarkerRenderer.drawAll(\n"
                + "            draw, client, config, iconManager, markers, presentation\n"
                + "        );"
        ));
        assertTrue(renderer.contains(
            "return category == RadarCategory.PLAYER || presentation.detailedIcons();"
        ));
        assertFalse(fullscreen.contains("MinecraftAccess.isPlayerListKeyPressed(client)"));
        assertFalse(renderer.contains("MinecraftAccess.isPlayerListKeyPressed(client)"));
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
