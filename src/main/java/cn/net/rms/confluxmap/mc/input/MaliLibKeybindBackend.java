package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Texts;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybind;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeybindMulti;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.option.KeyBinding;

/** Loaded only after Fabric Loader confirms that MaliLib is present. */
final class MaliLibKeybindBackend implements IKeybindProvider, IConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HOTKEYS_KEY = "hotkeys";

    private final Path configFile;
    private final IKeybind configScreenKeybind;
    private final IKeybind openMapKeybind;
    private final List<ConfigHotkey> hotkeys;
    private boolean configScreenRegistered;
    private String configScreenStorageKey = "";

    static MaliLibKeybindBackend register(final KeybindActionHandler actionHandler) {
        final MaliLibKeybindBackend backend = new MaliLibKeybindBackend(actionHandler);
        ConfigManager.getInstance().registerConfigHandler(ConfluxMapMod.ID, backend);
        backend.load();
        InputEventHandler.getKeybindManager().registerKeybindProvider(backend);
        backend.configScreenRegistered = MaliLibConfigScreenRegistrar.register(backend::createHotkeyScreen);
        return backend;
    }

    private MaliLibKeybindBackend(final KeybindActionHandler actionHandler) {
        configFile = FabricLoader.getInstance().getConfigDir()
            .resolve(ConfluxMapMod.ID)
            .resolve("malilib-hotkeys.json");
        configScreenKeybind = KeybindMulti.fromStorageString(
            "", MaliLibHotkeyFactory.configScreenSettings()
        );
        configScreenKeybind.setCallback((keyAction, keybind) -> {
            openHotkeyScreen();
            return true;
        });
        final ArrayList<ConfigHotkey> orderedHotkeys = new ArrayList<>();
        IKeybind detectedOpenMapKeybind = null;
        for (final KeybindAction action : KeybindAction.values()) {
            final ConfigHotkey hotkey = MaliLibHotkeyFactory.create(action);
            hotkey.getKeybind().setCallback((keyAction, keybind) -> actionHandler.trigger(action));
            orderedHotkeys.add(hotkey);
            if (action == KeybindAction.OPEN_MAP) {
                detectedOpenMapKeybind = hotkey.getKeybind();
            }
        }
        openMapKeybind = Objects.requireNonNull(detectedOpenMapKeybind, "open map hotkey");
        hotkeys = Collections.unmodifiableList(orderedHotkeys);
    }

    void openHotkeyScreen() {
        GuiBase.openGui(createHotkeyScreen());
    }

    private MaliLibHotkeyScreen createHotkeyScreen() {
        return new MaliLibHotkeyScreen(hotkeys);
    }

    boolean requiresVanillaConfigShortcut() {
        return !configScreenRegistered;
    }

    String openMapKeyDisplayName() {
        return openMapKeybind.getKeysDisplayString();
    }

    void syncConfigScreenKey(final KeyBinding vanillaHint) {
        if (!requiresVanillaConfigShortcut()) {
            return;
        }
        final String storageKey = MaliLibShortcutKey.storageKey(
            KeybindMulti.getKeyCode(vanillaHint),
            KeybindMulti::getStorageStringForKeyCode
        );
        if (storageKey.equals(configScreenStorageKey)) {
            return;
        }
        configScreenStorageKey = storageKey;
        configScreenKeybind.setValueFromString(storageKey);
        InputEventHandler.getKeybindManager().updateUsedKeys();
    }

    @Override
    public void addKeysToMap(final IKeybindManager manager) {
        if (requiresVanillaConfigShortcut()) {
            // Old MaliLib versions have no config-screen registry, so this compatibility key
            // must precede gameplay hotkeys when both use the same input.
            manager.addKeybindToMap(configScreenKeybind);
        }
        for (final ConfigHotkey hotkey : hotkeys) {
            manager.addKeybindToMap(hotkey.getKeybind());
        }
    }

    @Override
    public void addHotkeys(final IKeybindManager manager) {
        manager.addHotkeysForCategory(
            "Conflux Map",
            Texts.translatable("key.confluxmap.malilib.category").getString(),
            hotkeys
        );
    }

    @Override
    public void onConfigsChanged() {
        save();
    }

    @Override
    public void load() {
        if (!Files.exists(configFile)) {
            save();
            return;
        }
        try {
            final JsonObject root = GSON.fromJson(
                Files.readString(configFile, StandardCharsets.UTF_8),
                JsonObject.class
            );
            if (root == null) {
                throw new IOException("empty config");
            }
            ConfigUtils.readHotkeys(root, HOTKEYS_KEY, hotkeys);
        } catch (final IOException | RuntimeException e) {
            ConfluxMapMod.LOGGER.warn(
                "MaliLib hotkey config {} is unreadable ({}); keeping defaults",
                configFile,
                e.toString()
            );
        }
    }

    @Override
    public void save() {
        final JsonObject root = new JsonObject();
        ConfigUtils.writeHotkeys(root, HOTKEYS_KEY, hotkeys);
        try {
            Files.createDirectories(configFile.getParent());
            final Path tmp = configFile.resolveSibling(configFile.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(root), StandardCharsets.UTF_8);
            move(tmp, configFile);
        } catch (final IOException e) {
            ConfluxMapMod.LOGGER.error("Failed to save MaliLib hotkeys to {}", configFile, e);
        }
    }

    private static void move(final Path source, final Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (final AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
