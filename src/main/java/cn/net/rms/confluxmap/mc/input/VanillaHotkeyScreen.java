package cn.net.rms.confluxmap.mc.input;

//#if MC>=11800
//$$ import net.minecraft.client.gui.screen.option.KeybindsScreen;
//#else
import net.minecraft.client.gui.screen.option.ControlsOptionsScreen;
//#endif
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

/** Version seam for Minecraft's controls/keybind screen split in 1.18. */
final class VanillaHotkeyScreen {
    private VanillaHotkeyScreen() {
    }

    static Screen create(final Screen parent, final MinecraftClient client) {
        //#if MC>=11800
        //$$ return new KeybindsScreen(parent, client.options);
        //#else
        return new ControlsOptionsScreen(parent, client.options);
        //#endif
    }
}
