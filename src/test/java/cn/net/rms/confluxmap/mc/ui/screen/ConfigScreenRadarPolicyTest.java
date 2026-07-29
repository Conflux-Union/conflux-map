package cn.net.rms.confluxmap.mc.ui.screen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
}
