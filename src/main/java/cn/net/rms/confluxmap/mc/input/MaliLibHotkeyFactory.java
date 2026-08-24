package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.compat.Texts;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

/** Builds MaliLib hotkeys with the matching policy shared by every Conflux Map action. */
final class MaliLibHotkeyFactory {
    private MaliLibHotkeyFactory() {
    }

    static ConfigHotkey create(final KeybindAction action) {
        final KeybindSettings settings = settingsFor(action);
        final String displayName = Texts.translatable(action.translationKey()).getString();
        return new LocalizedMaliLibHotkey(
            action.configName(),
            action.maliLibDefaultKeys(),
            settings,
            Texts.translatable(action.translationKey() + ".comment").getString(),
            displayName
        );
    }

    static KeybindSettings settingsFor(final KeybindAction action) {
        if (action == KeybindAction.OPEN_MAP) {
            return KeybindSettings.create(
                KeybindSettings.Context.ANY,
                KeyAction.PRESS,
                true,
                true,
                false,
                true
            );
        }
        return KeybindSettings.PRESS_ALLOWEXTRA;
    }

    static KeybindSettings configScreenSettings() {
        return KeybindSettings.PRESS_ALLOWEXTRA;
    }
}
