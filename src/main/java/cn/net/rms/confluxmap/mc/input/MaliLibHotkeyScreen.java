package cn.net.rms.confluxmap.mc.input;

import fi.dy.masa.malilib.config.gui.ConfigPanelAllHotkeys;

final class MaliLibHotkeyScreen extends ConfigPanelAllHotkeys {
    private static final int BOTTOM_MARGIN = 10;

    @Override
    protected int getBrowserHeight() {
        // The stock all-hotkeys panel reserves 70 GUI units for a footer it does not render.
        return height - BOTTOM_MARGIN;
    }
}
