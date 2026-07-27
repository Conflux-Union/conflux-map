package cn.net.rms.confluxmap.mc.input;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class KeybindActionTest {
    @Test
    void sharedRegistryContainsEveryExistingActionWithUniqueBackendKeys() {
        assertEquals(11, KeybindAction.values().length);
        assertEquals(
            Set.of(
                "toggle_minimap",
                "zoom_in",
                "zoom_out",
                "open_map",
                "cycle_layer",
                "waypoints",
                "new_waypoint",
                "toggle_local_waypoints",
                "open_config",
                "cycle_prediction",
                "reload_prediction"
            ),
            Arrays.stream(KeybindAction.values())
                .map(action -> action.translationKey().substring("key.confluxmap.".length()))
                .collect(Collectors.toSet())
        );
        assertEquals(
            KeybindAction.values().length,
            Arrays.stream(KeybindAction.values()).map(KeybindAction::configName).collect(Collectors.toSet()).size()
        );
    }

    @Test
    void maliLibDefaultsPreserveVanillaDefaults() {
        assertEquals("H", KeybindAction.TOGGLE_MINIMAP.maliLibDefaultKeys());
        assertEquals("RIGHT_BRACKET", KeybindAction.ZOOM_IN.maliLibDefaultKeys());
        assertEquals("LEFT_BRACKET", KeybindAction.ZOOM_OUT.maliLibDefaultKeys());
        assertEquals("M", KeybindAction.OPEN_MAP.maliLibDefaultKeys());
        assertEquals("COMMA", KeybindAction.OPEN_CONFIG.maliLibDefaultKeys());
        assertEquals("F9", KeybindAction.RELOAD_PREDICTION.maliLibDefaultKeys());
    }
}
