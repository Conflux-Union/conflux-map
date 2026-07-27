package cn.net.rms.confluxmap.mc.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

final class MaliLibHotkeyLayoutTest {
    @Test
    void separatesTheListFromTheSwitcherAndKeepsTheLastButtonInsideTheScreen() {
        final int screenHeight = 300;
        final int listY = MaliLibHotkeyLayout.listY(0);
        final int browserHeight = MaliLibHotkeyLayout.browserHeight(screenHeight);

        assertEquals(28, listY);
        assertEquals(248, browserHeight);
        assertEquals(24, screenHeight - listY - browserHeight);
    }

    @Test
    void narrowsTheConfigControlsFromMaliLibsDefaultWidth() {
        assertEquals(144, MaliLibHotkeyLayout.CONFIG_WIDTH);
        assertTrue(MaliLibHotkeyLayout.CONFIG_WIDTH < 204);
    }
}
