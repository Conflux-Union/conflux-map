package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.mc.ui.screen.ConfigScreen;
import cn.net.rms.confluxmap.mc.ui.screen.ClientWorldSelectScreen;
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
                if (!minimapAllowed()) {
                    return false;
                }
                config.minimapEnabled = !config.minimapEnabled;
                configChanged = true;
                handled = true;
                break;
            case CYCLE_ZOOM:
                if (!minimapAllowed()) {
                    return false;
                }
                config.cycleMinimapZoom();
                configChanged = true;
                handled = true;
                break;
            case OPEN_MAP:
                handled = toggleMapScreen();
                break;
            case CYCLE_LAYER:
                if (!minimapAllowed()) {
                    return false;
                }
                layerSelector.cycleOverride();
                configChanged = true;
                handled = true;
                break;
            case OPEN_WAYPOINTS:
                if (!waypointsAllowed()) {
                    return false;
                }
                handled = openScreen(WaypointListScreen.openedFromHotkey());
                break;
            case NEW_WAYPOINT:
                if (!waypointsAllowed()) {
                    return false;
                }
                handled = openNewWaypointAtViewpoint();
                break;
            case TOGGLE_LOCAL_WAYPOINTS:
                if (!waypointsAllowed()) {
                    return false;
                }
                config.localWaypointsVisible = !config.localWaypointsVisible;
                configChanged = true;
                handled = true;
                break;
            case OPEN_CONFIG:
                handled = openScreen(new ConfigScreen());
                break;
            case CYCLE_PREDICTION:
                if (!predictionAllowed()) {
                    return false;
                }
                config.predictionViewMode = config.predictionViewMode.next();
                ConfluxMapClient.get().predictionTileService().setViewMode(config.predictionViewMode);
                configChanged = true;
                handled = true;
                break;
            case RELOAD_PREDICTION:
                if (!predictionAllowed()) {
                    return false;
                }
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

    private static boolean minimapAllowed() {
        return ConfluxMapClient.get().companionSession().minimapAllowed();
    }

    private static boolean waypointsAllowed() {
        return ConfluxMapClient.get().companionSession().waypointsAllowed();
    }

    private static boolean predictionAllowed() {
        return ConfluxMapClient.get().companionSession().predictionAllowed();
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
        if (!ConfluxMapClient.get().companionSession().worldMapAllowed()) {
            return false;
        }
        if (ConfluxMapClient.get().clientMultiworldService().needsSelection()) {
            MinecraftAccess.setScreen(client, new ClientWorldSelectScreen(null, vanillaOpenMapKey, true));
            return true;
        }
        if (!ConfluxMapClient.get().sessionGuard().current().active()) {
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

    private static boolean openNewWaypointAtViewpoint() {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || MinecraftAccess.screen(client) != null) {
            return false;
        }
        final Optional<PlayerView> viewpoint = ConfluxMapClient.get().gameBridge().viewpoint();
        if (viewpoint.isEmpty()) {
            return false;
        }
        final PlayerView camera = viewpoint.get();
        MinecraftAccess.setScreen(client, WaypointEditScreen.forHotkeyCreate(
            camera.dimension(),
            camera.blockX(),
            camera.blockY(),
            camera.blockZ()
        ));
        return true;
    }
}
