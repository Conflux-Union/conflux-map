package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ConfigScreenRadarPolicyTest {
    @Test
    void allowedPolicyKeepsControlsInteractiveWithoutANotice() {
        final ConfigScreen.RadarSettingsAccess access = ConfigScreen.RadarSettingsAccess.from(true);

        assertEquals(ConfigScreen.RadarSettingsAccess.ALLOWED, access);
        assertTrue(access.controlsActive());
        assertNull(access.noticeKey());
        assertNull(access.tooltipKey());
    }

    @Test
    void forbiddenPolicyDisablesControlsAndExplainsWhy() {
        final ConfigScreen.RadarSettingsAccess access = ConfigScreen.RadarSettingsAccess.from(false);

        assertEquals(ConfigScreen.RadarSettingsAccess.FORBIDDEN_BY_SERVER, access);
        assertFalse(access.controlsActive());
        assertEquals("confluxmap.screen.config.radar.disabled_by_server", access.noticeKey());
        assertEquals("confluxmap.screen.config.radar.disabled_by_server", access.tooltipKey());
    }

    @Test
    void radarSettingsDoNotExposeRetiredIconAndMergeToggles() throws IOException {
        final String screen = Files.readString(findProjectRoot().resolve(
            "src/main/java/cn/net/rms/confluxmap/mc/ui/screen/ConfigScreen.java"
        ));

        assertFalse(screen.contains("confluxmap.config.radar.icons_enabled"));
        assertFalse(screen.contains("confluxmap.config.radar.merge_enabled"));
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
