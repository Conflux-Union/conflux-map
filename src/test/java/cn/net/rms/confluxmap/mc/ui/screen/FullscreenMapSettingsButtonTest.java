package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class FullscreenMapSettingsButtonTest {
    @Test
    void rightToolbarSettingsButtonOpensSettingsThatReturnToTheMap() throws IOException {
        final String mapSource = source(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/FullscreenMapScreen.java"
        );
        final String controls = between(
            mapSource,
            "private void rebuildWaypointControls()",
            "    private MapIconButton addToolGroupButton"
        );

        final int actionsButton = controls.indexOf("actionsGroupButton = addToolGroupButton(");
        final int settingsButton = controls.indexOf(
            "settingsButton = addDrawableChild(new MapIconButton("
        );
        assertTrue(actionsButton >= 0, "the actions group must remain in the right toolbar");
        assertTrue(
            settingsButton > actionsButton,
            "the settings button must appear after the existing right-toolbar groups"
        );
        assertTrue(controls.contains("MAP_SETTINGS_ICON"), "the button must use the gear icon");
        assertTrue(
            controls.contains("new ConfigScreen(this)"),
            "the button must pass the fullscreen map as the settings parent"
        );

        final String configSource = source(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ConfigScreen.java"
        );
        assertTrue(
            configSource.contains("public ConfigScreen(final Screen parent)"),
            "settings must accept the screen to return to"
        );
        final String close = between(
            configSource,
            "public void onClose()",
            "    private void rebuild()"
        );
        assertTrue(
            close.contains("MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);"),
            "closing map settings must restore the fullscreen map"
        );
    }

    private static String between(final String source, final String start, final String end) {
        final int startIndex = source.indexOf(start);
        final int endIndex = source.indexOf(end, startIndex);
        assertTrue(startIndex >= 0 && endIndex > startIndex, "expected source section must exist");
        return source.substring(startIndex, endIndex);
    }

    private static String source(final String relativePath) throws IOException {
        return Files.readString(projectRoot().resolve(relativePath)).replace("\r\n", "\n");
    }

    private static Path projectRoot() {
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
