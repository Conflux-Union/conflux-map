package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.EnumMap;
import java.util.Map;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
//#else
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
//#endif
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.lwjgl.glfw.GLFW;

/** Selects one input backend while routing every gameplay action through one handler. */
public final class Keybinds {
    //#if MC>=12109
    //$$ public static final KeyBinding.Category CATEGORY = KeyBinding.Category.create(
    //$$     Ids.of("confluxmap", "controls")
    //$$ );
    //#else
    public static final String CATEGORY = "key.categories.confluxmap";
    //#endif

    private final Map<KeybindAction, KeyBinding> vanillaBindings = new EnumMap<>(KeybindAction.class);
    private final KeybindActionHandler actionHandler;
    private final MaliLibKeybindBackend maliLibBackend;
    private final KeyBinding maliLibHint;

    public Keybinds(final ConfluxConfig config, final ConfigIo configIo, final LayerSelector layerSelector) {
        MaliLibKeybindBackend detectedBackend = null;
        KeybindActionHandler detectedHandler = null;
        if (FabricLoader.getInstance().isModLoaded("malilib")) {
            detectedHandler = new KeybindActionHandler(config, configIo, layerSelector, null);
            try {
                detectedBackend = MaliLibKeybindBackend.register(detectedHandler);
            } catch (final LinkageError | RuntimeException e) {
                detectedHandler = null;
                ConfluxMapMod.LOGGER.error(
                    "MaliLib is installed but its keybind integration could not start; using vanilla keybinds",
                    e
                );
            }
        }

        maliLibBackend = detectedBackend;
        if (maliLibBackend != null) {
            actionHandler = detectedHandler;
            maliLibHint = register("configure_hotkeys", GLFW.GLFW_KEY_UNKNOWN);
            maliLibBackend.syncConfigScreenKey(maliLibHint);
            ConfluxMapMod.LOGGER.info("MaliLib detected; Conflux Map hotkeys are registered with MaliLib");
        } else {
            for (final KeybindAction action : KeybindAction.values()) {
                vanillaBindings.put(action, registerTranslation(action.translationKey(), action.vanillaDefaultKey()));
            }
            actionHandler = new KeybindActionHandler(
                config,
                configIo,
                layerSelector,
                vanillaBindings.get(KeybindAction.OPEN_MAP)
            );
            maliLibHint = null;
        }
        ClientTickEvents.END_CLIENT_TICK.register(client -> poll());
    }

    private static KeyBinding register(final String name, final int key) {
        return registerTranslation("key.confluxmap." + name, key);
    }

    private static KeyBinding registerTranslation(final String translationKey, final int key) {
        //#if MC>=260100
        //$$ return KeyMappingHelper.registerKeyMapping(
        //$$     new KeyMapping(translationKey, InputConstants.Type.KEYSYM, key, CATEGORY)
        //$$ );
        //#else
        return KeyBindingHelper.registerKeyBinding(
            new KeyBinding(translationKey, InputUtil.Type.KEYSYM, key, CATEGORY)
        );
        //#endif
    }

    private void poll() {
        if (maliLibBackend != null) {
            maliLibBackend.syncConfigScreenKey(maliLibHint);
            while (maliLibHint.wasPressed()) {
                final MinecraftClient client = MinecraftClient.getInstance();
                if (client.currentScreen == null) {
                    maliLibBackend.openHotkeyScreen();
                }
            }
            return;
        }
        for (final KeybindAction action : KeybindAction.values()) {
            final KeyBinding binding = vanillaBindings.get(action);
            while (binding.wasPressed()) {
                actionHandler.trigger(action);
            }
        }
    }
}
