package cn.net.rms.confluxmap.mc.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

import org.junit.jupiter.api.Test;

final class MaliLibShortcutKeyTest {
    @Test
    void mirrorsKeyboardBindingIntoMaliLibStorage() {
        assertEquals("J", MaliLibShortcutKey.storageKey(74, keyCode -> "J"));
    }

    @Test
    void preservesNegativeMouseCodesButClearsTheUnboundSentinel() {
        assertEquals("MOUSE_1", MaliLibShortcutKey.storageKey(-100, keyCode -> "MOUSE_1"));
        assertEquals("", MaliLibShortcutKey.storageKey(-1, keyCode -> {
            fail("The unbound sentinel must not be resolved as a MaliLib key");
            return "UNKNOWN";
        }));
    }
}
