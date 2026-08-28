package cn.net.rms.confluxmap.mc.input;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class KeybindActionTest {
    @Test
    void sharedRegistryContainsEveryExistingActionWithUniqueBackendKeys() {
        assertEquals(10, KeybindAction.values().length);
        assertEquals(
            Set.of(
                "toggle_minimap",
                "zoom_in",
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
        assertEquals("RIGHT_BRACKET", KeybindAction.CYCLE_ZOOM.maliLibDefaultKeys());
        assertEquals("M", KeybindAction.OPEN_MAP.maliLibDefaultKeys());
        assertEquals("COMMA", KeybindAction.OPEN_CONFIG.maliLibDefaultKeys());
        assertEquals("F9", KeybindAction.RELOAD_PREDICTION.maliLibDefaultKeys());
    }

    @Test
    void maliLibHotkeysAllowNativeGameKeysToRemainHeld() {
        for (final KeybindAction action : KeybindAction.values()) {
            final KeybindSettings settings = MaliLibHotkeyFactory.settingsFor(action);

            assertTrue(settings.getAllowExtraKeys(), action.configName());
            assertSame(KeyAction.PRESS, settings.getActivateOn(), action.configName());
            assertSame(
                action == KeybindAction.OPEN_MAP
                    ? KeybindSettings.Context.ANY
                    : KeybindSettings.Context.INGAME,
                settings.getContext(),
                action.configName()
            );
            assertTrue(settings.isOrderSensitive(), action.configName());
            assertFalse(settings.isExclusive(), action.configName());
            assertTrue(settings.shouldCancel(), action.configName());
        }
    }

    @Test
    void maliLibConfigShortcutAllowsNativeGameKeysToRemainHeld() {
        final KeybindSettings settings = MaliLibHotkeyFactory.configScreenSettings();

        assertTrue(settings.getAllowExtraKeys());
        assertSame(KeyAction.PRESS, settings.getActivateOn());
        assertSame(KeybindSettings.Context.INGAME, settings.getContext());
        assertTrue(settings.isOrderSensitive());
        assertFalse(settings.isExclusive());
        assertTrue(settings.shouldCancel());
    }

    @Test
    void maliLibHotkeysAlwaysYieldToHeldDebugModifierUnlessTheyOwnIt() {
        assertTrue(MaliLibKeybindBackend.shouldDeferToVanillaDebugShortcut(true, false));
        assertFalse(MaliLibKeybindBackend.shouldDeferToVanillaDebugShortcut(true, true));
        assertFalse(MaliLibKeybindBackend.shouldDeferToVanillaDebugShortcut(false, false));
    }

    @Test
    void everyMaliLibHotkeyHasLocalizedDisplayTextAndTooltip() {
        final Map<String, String> english = translations("en_us");
        final Map<String, String> chinese = translations("zh_cn");

        assertTrue(english.containsKey("gui.confluxmap.malilib_hotkeys"));
        assertTrue(chinese.containsKey("gui.confluxmap.malilib_hotkeys"));

        for (final KeybindAction action : KeybindAction.values()) {
            for (final String key : new String[] {action.translationKey(), action.translationKey() + ".comment"}) {
                assertTrue(english.containsKey(key), "missing English translation for " + key);
                assertTrue(chinese.containsKey(key), "missing Chinese translation for " + key);
                assertTrue(!english.get(key).isBlank(), "blank English translation for " + key);
                assertTrue(!chinese.get(key).isBlank(), "blank Chinese translation for " + key);
            }
        }
    }

    @Test
    void vanillaCategoryHasLocalizedDisplayTextForBothCategoryApis() {
        final Map<String, String> english = translations("en_us");
        final Map<String, String> chinese = translations("zh_cn");

        for (final String key : List.of("key.categories.confluxmap", Keybinds.CATEGORY_TRANSLATION_KEY)) {
            assertTrue(english.containsKey(key), "missing English translation for " + key);
            assertTrue(chinese.containsKey(key), "missing Chinese translation for " + key);
            assertTrue(!english.get(key).isBlank(), "blank English translation for " + key);
            assertTrue(!chinese.get(key).isBlank(), "blank Chinese translation for " + key);
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
