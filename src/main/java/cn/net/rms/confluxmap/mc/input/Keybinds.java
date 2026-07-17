package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

public final class Keybinds {
    public static final String CATEGORY = "key.categories.confluxmap";

    private final KeyBinding toggleMinimap;
    private final KeyBinding zoomIn;
    private final KeyBinding zoomOut;
    private final KeyBinding openMap;
    private final ConfluxConfig config;
    private final ConfigIo configIo;

    public Keybinds(final ConfluxConfig config, final ConfigIo configIo) {
        this.config = config;
        this.configIo = configIo;
        toggleMinimap = register("toggle_minimap", GLFW.GLFW_KEY_H);
        zoomIn = register("zoom_in", GLFW.GLFW_KEY_RIGHT_BRACKET);
        zoomOut = register("zoom_out", GLFW.GLFW_KEY_LEFT_BRACKET);
        openMap = register("open_map", GLFW.GLFW_KEY_M);
        ClientTickEvents.END_CLIENT_TICK.register(client -> poll());
    }

    private static KeyBinding register(final String name, final int key) {
        return KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.confluxmap." + name, InputUtil.Type.KEYSYM, key, CATEGORY)
        );
    }

    private void poll() {
        boolean changed = false;
        while (toggleMinimap.wasPressed()) {
            config.minimapEnabled = !config.minimapEnabled;
            changed = true;
        }
        while (zoomIn.wasPressed()) {
            if (config.minimapZoomIndex > 0) {
                config.minimapZoomIndex--;
                changed = true;
            }
        }
        while (zoomOut.wasPressed()) {
            if (config.minimapZoomIndex < 3) {
                config.minimapZoomIndex++;
                changed = true;
            }
        }
        // KeyBinding state stops updating while any screen with passEvents=false is open (vanilla
        // Keyboard#onKey behavior), so this never re-fires while FullscreenMapScreen is showing;
        // closing on a second M press is handled directly in the screen's own keyPressed instead.
        while (openMap.wasPressed()) {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                client.setScreen(new FullscreenMapScreen(openMap));
            }
        }
        if (changed) {
            configIo.save(config);
        }
    }
}
