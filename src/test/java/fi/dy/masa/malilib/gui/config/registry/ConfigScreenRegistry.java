//#if MC<12101
package fi.dy.masa.malilib.gui.config.registry;

import fi.dy.masa.malilib.util.data.ModInfo;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ConfigScreenRegistry {
    private final Map<String, ModInfo> mods = new LinkedHashMap<>();

    public void registerConfigScreenFactory(final ModInfo modInfo) {
        mods.put(modInfo.getModId(), modInfo);
    }

    public String getModName(final String modId) {
        final ModInfo modInfo = mods.get(modId);
        return modInfo == null ? null : modInfo.getModName();
    }

    public void clear() {
        mods.clear();
    }
}
//#endif
