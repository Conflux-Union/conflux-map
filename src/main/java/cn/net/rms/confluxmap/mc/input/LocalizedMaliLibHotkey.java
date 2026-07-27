package cn.net.rms.confluxmap.mc.input;

import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;

final class LocalizedMaliLibHotkey extends ConfigHotkey {
    private final String displayName;

    LocalizedMaliLibHotkey(
        final String name,
        final String defaultKeys,
        final KeybindSettings settings,
        final String comment,
        final String displayName
    ) {
        super(name, defaultKeys, settings, comment, displayName);
        this.displayName = displayName;
    }

    @Override
    public String getConfigGuiDisplayName() {
        return displayName;
    }
}
