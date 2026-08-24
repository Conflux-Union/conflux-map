package cn.net.rms.confluxmap.compat;

import cn.net.rms.confluxmap.mc.ui.screen.ConfigScreen;
//#if MC>=11802
//$$ import com.terraformersmc.modmenu.api.ConfigScreenFactory;
//$$ import com.terraformersmc.modmenu.api.ModMenuApi;
//#else
import io.github.prospector.modmenu.api.ConfigScreenFactory;
import io.github.prospector.modmenu.api.ModMenuApi;
//#endif

/** Optional Mod Menu adapter for opening Conflux Map's settings screen. */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return ConfigScreen::new;
    }
}
