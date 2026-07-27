package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.compat.Texts;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import fi.dy.masa.malilib.config.ConfigManager;
import fi.dy.masa.malilib.config.ConfigUtils;
import fi.dy.masa.malilib.config.IConfigHandler;
import fi.dy.masa.malilib.config.gui.ConfigPanelAllHotkeys;
import fi.dy.masa.malilib.config.options.ConfigHotkey;
import fi.dy.masa.malilib.event.InputEventHandler;
import fi.dy.masa.malilib.gui.GuiBase;
import fi.dy.masa.malilib.hotkeys.IKeybindManager;
import fi.dy.masa.malilib.hotkeys.IKeybindProvider;
import fi.dy.masa.malilib.hotkeys.KeyAction;
import fi.dy.masa.malilib.hotkeys.KeybindSettings;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import net.fabricmc.loader.api.FabricLoader;

/** Loaded only after Fabric Loader confirms that MaliLib is present. */
final class MaliLibKeybindBackend implements IKeybindProvider, IConfigHandler {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String HOTKEYS_KEY = "hotkeys";

    private final Path configFile;
    private final List<ConfigHotkey> hotkeys;

    static MaliLibKeybindBackend register(final KeybindActionHandler actionHandler) {
        final MaliLibKeybindBackend backend = new MaliLibKeybindBackend(actionHandler);
        ConfigManager.getInstance().registerConfigHandler(ConfluxMapMod.ID, backend);
        backend.load();
        InputEventHandler.getKeybindManager().registerKeybindProvider(backend);
        return backend;
    }

    private MaliLibKeybindBackend(final KeybindActionHandler actionHandler) {
        configFile = FabricLoader.getInstance().getConfigDir()
            .resolve(ConfluxMapMod.ID)
            .resolve("malilib-hotkeys.json");
        final ArrayList<ConfigHotkey> orderedHotkeys = new ArrayList<>();
        for (final KeybindAction action : KeybindAction.values()) {
            final ConfigHotkey hotkey = createHotkey(action);
            hotkey.getKeybind().setCallback((keyAction, keybind) -> actionHandler.trigger(action));
            orderedHotkeys.add(hotkey);
        }
        hotkeys = Collections.unmodifiableList(orderedHotkeys);
    }

    private static ConfigHotkey createHotkey(final KeybindAction action) {
        final KeybindSettings settings = action == KeybindAction.OPEN_MAP
            ? KeybindSettings.create(
                KeybindSettings.Context.ANY,
                KeyAction.PRESS,
                false,
                true,
                false,
                true
            )
            : KeybindSettings.DEFAULT;
        return new ConfigHotkey(
            action.configName(),
            action.maliLibDefaultKeys(),
            settings,
            Texts.translatable("key.confluxmap.malilib.comment").getString(),
            Texts.translatable(action.translationKey()).getString()
        );
    }

    void openHotkeyScreen() {
        GuiBase.openGui(new ConfigPanelAllHotkeys());
    }

    @Override
    public void addKeysToMap(final IKeybindManager manager) {
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
