//#if MC<12101
package cn.net.rms.confluxmap.mc.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import fi.dy.masa.malilib.registry.Registry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

final class MaliLibConfigScreenRegistrarTest {
    @BeforeEach
    void clearRegistry() {
        Registry.CONFIG_SCREEN.clear();
    }

    @Test
    void exposesConfluxMapInTheRuntimeConfigScreenRegistry() {
        final boolean registered = MaliLibConfigScreenRegistrar.register(() -> null);

        assertTrue(registered);
        assertEquals("Conflux Map", Registry.CONFIG_SCREEN.getModName("confluxmap"));
    }

    @Test
    void keepsTheCompatibilityShortcutWhenTheRuntimeHasNoRegistry() {
        final ClassLoader emptyClassLoader = new ClassLoader(null) {
        };

        assertFalse(MaliLibConfigScreenRegistrar.registerLegacy(() -> null, emptyClassLoader));
    }
}
//#endif
