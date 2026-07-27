package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.ConfluxMapMod;
import fi.dy.masa.malilib.config.gui.GuiModConfigs;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.gui.widgets.WidgetListConfigOptions;
import java.util.List;

final class MaliLibHotkeyScreen extends GuiModConfigs {
    MaliLibHotkeyScreen(final List<? extends ConfigHotkey> hotkeys) {
        super(ConfluxMapMod.ID, hotkeys, "gui.confluxmap.malilib_hotkeys");
        setConfigWidth(MaliLibHotkeyLayout.CONFIG_WIDTH);
    }

    @Override
    protected WidgetListConfigOptions createListWidget(final int listX, final int listY) {
        return new WidgetListConfigOptions(
            listX,
            MaliLibHotkeyLayout.listY(listY),
            getBrowserWidth(),
            getBrowserHeight(),
            getConfigWidth(),
            0.0F,
            useKeybindSearch(),
            this
        );
    }

    @Override
    protected int getBrowserHeight() {
        // Leave separate top and bottom safe areas without restoring the stock 70-unit footer gap.
        return MaliLibHotkeyLayout.browserHeight(height);
    }
}
