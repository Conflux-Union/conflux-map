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
        return pressSettings(action == KeybindAction.OPEN_MAP
            ? KeybindSettings.Context.ANY
            : KeybindSettings.Context.INGAME
        );
    }

    static KeybindSettings configScreenSettings() {
        return pressSettings(KeybindSettings.Context.INGAME);
    }

    /** Cancelling the key dispatch would swallow the event before sibling mods bound to the same key see it. */
    private static KeybindSettings pressSettings(final KeybindSettings.Context context) {
        return KeybindSettings.create(context, KeyAction.PRESS, true, true, false, false);
    }
}
