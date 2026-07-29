package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.screen.ConfigScreen;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointEditScreen;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointListScreen;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;

/** Executes every keybind action, independent of which input backend triggered it. */
final class KeybindActionHandler {
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final LayerSelector layerSelector;
    private final KeyBinding vanillaOpenMapKey;

    KeybindActionHandler(
        final ConfluxConfig config,
        final ConfigIo configIo,
        final LayerSelector layerSelector,
        final KeyBinding vanillaOpenMapKey
    ) {
        this.config = config;
        this.configIo = configIo;
        this.layerSelector = layerSelector;
        this.vanillaOpenMapKey = vanillaOpenMapKey;
    }

    boolean trigger(final KeybindAction action) {
        boolean configChanged = false;
        final boolean handled;
        switch (action) {
            case TOGGLE_MINIMAP:
                config.minimapEnabled = !config.minimapEnabled;
                configChanged = true;
                handled = true;
                break;
            case ZOOM_IN:
                if (config.minimapZoomIndex > 0) {
                    config.minimapZoomIndex--;
                    configChanged = true;
                }
                handled = true;
                break;
            case ZOOM_OUT:
                if (config.minimapZoomIndex < 3) {
                    config.minimapZoomIndex++;
                    configChanged = true;
                }
                handled = true;
                break;
            case OPEN_MAP:
                handled = toggleMapScreen();
                break;
            case CYCLE_LAYER:
                layerSelector.cycleOverride();
                configChanged = true;
                handled = true;
                break;
            case OPEN_WAYPOINTS:
                handled = openScreen(new WaypointListScreen());
                break;
            case NEW_WAYPOINT:
                handled = openNewWaypointAtPlayer();
                break;
            case TOGGLE_LOCAL_WAYPOINTS:
                config.localWaypointsVisible = !config.localWaypointsVisible;
                configChanged = true;
                handled = true;
                break;
            case OPEN_CONFIG:
                handled = openScreen(new ConfigScreen());
                break;
            case CYCLE_PREDICTION:
                config.predictionViewMode = config.predictionViewMode.next();
                ConfluxMapClient.get().predictionTileService().setViewMode(config.predictionViewMode);
                configChanged = true;
                handled = true;
                break;
            case RELOAD_PREDICTION:
                ConfluxMapClient.get().reloadPredictionTiles();
                handled = true;
                break;
            default:
                throw new IllegalArgumentException("Unhandled keybind action: " + action);
        }
        if (configChanged) {
            configIo.save(config);
        }
        return handled;
    }

    private boolean toggleMapScreen() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (MinecraftAccess.screen(client) instanceof FullscreenMapScreen) {
            MinecraftAccess.screen(client).onClose();
            return true;
        }
        if (client.player == null || MinecraftAccess.screen(client) != null) {
            return false;
        }
        MinecraftAccess.setScreen(client, new FullscreenMapScreen(vanillaOpenMapKey));
        return true;
    }

    private static boolean openScreen(final Screen screen) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || MinecraftAccess.screen(client) != null) {
            return false;
        }
        MinecraftAccess.setScreen(client, screen);
        return true;
    }

    private static boolean openNewWaypointAtPlayer() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || MinecraftAccess.screen(client) != null) {
            return false;
        }
        final Optional<PlayerView> playerView = ConfluxMapClient.get().gameBridge().player();
        if (playerView.isEmpty()) {
            return false;
        }
        final PlayerView player = playerView.get();
        MinecraftAccess.setScreen(client, WaypointEditScreen.forCreate(
            null,
            player.dimension(),
            player.blockX(),
            player.blockY(),
            player.blockZ()
        ));
        return true;
    }
}
