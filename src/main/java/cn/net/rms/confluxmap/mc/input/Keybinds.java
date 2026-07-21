package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.mc.ui.screen.ConfigScreen;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointEditScreen;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointListScreen;
import java.util.Optional;
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
    private final KeyBinding cycleLayer;
    private final KeyBinding openWaypoints;
    private final KeyBinding newWaypoint;
    private final KeyBinding toggleLocalWaypoints;
    private final KeyBinding openConfig;
    private final KeyBinding cyclePrediction;
    private final KeyBinding reloadPrediction;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final LayerSelector layerSelector;

    public Keybinds(final ConfluxConfig config, final ConfigIo configIo, final LayerSelector layerSelector) {
        this.config = config;
        this.configIo = configIo;
        this.layerSelector = layerSelector;
        toggleMinimap = register("toggle_minimap", GLFW.GLFW_KEY_H);
        zoomIn = register("zoom_in", GLFW.GLFW_KEY_RIGHT_BRACKET);
        zoomOut = register("zoom_out", GLFW.GLFW_KEY_LEFT_BRACKET);
        openMap = register("open_map", GLFW.GLFW_KEY_M);
        cycleLayer = register("cycle_layer", GLFW.GLFW_KEY_Y);
        openWaypoints = register("waypoints", GLFW.GLFW_KEY_U);
        newWaypoint = register("new_waypoint", GLFW.GLFW_KEY_B);
        toggleLocalWaypoints = register("toggle_local_waypoints", GLFW.GLFW_KEY_J);
        openConfig = register("open_config", GLFW.GLFW_KEY_COMMA);
        cyclePrediction = register("cycle_prediction", GLFW.GLFW_KEY_P);
        reloadPrediction = register("reload_prediction", GLFW.GLFW_KEY_F9);
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
        while (cycleLayer.wasPressed()) {
            layerSelector.cycleOverride();
            changed = true;
        }
        while (cyclePrediction.wasPressed()) {
            config.predictionViewMode = config.predictionViewMode.next();
            ConfluxMapClient.get().predictionTileService().setViewMode(config.predictionViewMode);
            changed = true;
        }
        while (reloadPrediction.wasPressed()) {
            ConfluxMapClient.get().reloadPredictionTiles();
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
        while (openWaypoints.wasPressed()) {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                client.setScreen(new WaypointListScreen());
            }
        }
        while (newWaypoint.wasPressed()) {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                openNewWaypointAtPlayer(client);
            }
        }
        while (toggleLocalWaypoints.wasPressed()) {
            config.localWaypointsVisible = !config.localWaypointsVisible;
            changed = true;
        }
        while (openConfig.wasPressed()) {
            final MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null && client.currentScreen == null) {
                client.setScreen(new ConfigScreen());
            }
        }
        if (changed) {
            configIo.save(config);
        }
    }

    private static void openNewWaypointAtPlayer(final MinecraftClient client) {
        final Optional<PlayerView> playerView = ConfluxMapClient.get().gameBridge().player();
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        client.setScreen(WaypointEditScreen.forCreate(null, player.dimension(), player.blockX(), player.blockY(), player.blockZ()));
    }
}
