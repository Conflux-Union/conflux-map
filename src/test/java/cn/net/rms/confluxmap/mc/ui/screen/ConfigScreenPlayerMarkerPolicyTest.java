package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class ConfigScreenPlayerMarkerPolicyTest {
    @Test
    void normalFallbackMarkerKeepsTheStyleControlEnabled() {
        final ConfigScreen.PlayerMarkerSettingsAccess access =
            ConfigScreen.PlayerMarkerSettingsAccess.from(false);

        assertTrue(access.controlsActive());
        assertNull(access.tooltipKey());
    }

    @Test
    void resourcePackMarkerDisablesTheStyleControlWithAnExactReason() {
        final ConfigScreen.PlayerMarkerSettingsAccess access =
            ConfigScreen.PlayerMarkerSettingsAccess.from(true);

        assertFalse(access.controlsActive());
        assertTrue(access.resourceOverride());
        assertTrue(access.tooltipKey().contains("resource_pack"));
    }
}
