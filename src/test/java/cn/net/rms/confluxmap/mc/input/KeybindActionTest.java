package cn.net.rms.confluxmap.mc.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Map;
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

    @Test
    void everyMaliLibHotkeyHasLocalizedDisplayTextAndTooltip() {
        final Map<String, String> english = translations("en_us");
        final Map<String, String> chinese = translations("zh_cn");

        for (final KeybindAction action : KeybindAction.values()) {
            for (final String key : new String[] {action.translationKey(), action.translationKey() + ".comment"}) {
                assertTrue(english.containsKey(key), "missing English translation for " + key);
                assertTrue(chinese.containsKey(key), "missing Chinese translation for " + key);
                assertTrue(!english.get(key).isBlank(), "blank English translation for " + key);
                assertTrue(!chinese.get(key).isBlank(), "blank Chinese translation for " + key);
            }
        }
    }

    private static Map<String, String> translations(final String locale) {
        final String resource = "/assets/confluxmap/lang/" + locale + ".json";
        final InputStream stream = KeybindActionTest.class.getResourceAsStream(resource);
        assertNotNull(stream, "missing language resource " + resource);
        return new Gson().fromJson(
            new InputStreamReader(stream, StandardCharsets.UTF_8),
            new TypeToken<Map<String, String>>() { }.getType()
        );
    }
}
