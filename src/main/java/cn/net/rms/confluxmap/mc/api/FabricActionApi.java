package cn.net.rms.confluxmap.mc.api;

import cn.net.rms.confluxmap.api.ActionApi;
import cn.net.rms.confluxmap.api.MapDataApi.ApiLayer;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.core.api.ApiLayers;
import cn.net.rms.confluxmap.core.api.ConfluxMapApiImpl;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.input.KeybindAction;
import cn.net.rms.confluxmap.mc.input.Keybinds;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.teleport.ClientGroundTeleportService;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.ui.screen.WaypointEditScreen;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.MinecraftClient;

/**
 * {@link ActionApi} backed by the Fabric adapters. Every entry point marshals itself onto
 * the client main thread through the bridge, so callers may use any thread; guards that
 * stop an action (no session, screen busy) match the keybind behavior exactly because the
 * screen actions reuse the keybind dispatch.
 */
public final class FabricActionApi implements ActionApi {
    private final MinecraftClient client;
    private final GameBridge bridge;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final Keybinds keybinds;
    private final WaypointService waypoints;
    private final ClientGroundTeleportService groundTeleport;
    private final LayerSelector layerSelector;
    private final SessionGuard guard;
    private final CompanionSession companion;

    public FabricActionApi(
        final MinecraftClient client,
        final GameBridge bridge,
        final ConfluxConfig config,
        final ConfigIo configIo,
        final Keybinds keybinds,
        final WaypointService waypoints,
        final ClientGroundTeleportService groundTeleport,
        final LayerSelector layerSelector,
        final SessionGuard guard,
        final CompanionSession companion
    ) {
        this.client = client;
        this.bridge = bridge;
        this.config = config;
        this.configIo = configIo;
        this.keybinds = keybinds;
        this.waypoints = waypoints;
        this.groundTeleport = groundTeleport;
        this.layerSelector = layerSelector;
        this.guard = guard;
        this.companion = companion;
    }

    @Override
    public void openFullscreenMap() {
        onMain(() -> {
            if (!(MinecraftAccess.screen(client) instanceof FullscreenMapScreen)) {
                keybinds.trigger(KeybindAction.OPEN_MAP);
            }
        });
    }

    @Override
    public void closeFullscreenMap() {
        onMain(() -> {
            if (MinecraftAccess.screen(client) instanceof FullscreenMapScreen) {
                MinecraftAccess.screen(client).onClose();
            }
        });
    }

    @Override
    public void openWaypointList() {
        onMain(() -> keybinds.trigger(KeybindAction.OPEN_WAYPOINTS));
    }

    @Override
    public void openWaypointEditor(final UUID waypointId) {
        if (waypointId == null) {
            return;
        }
        onMain(() -> {
            if (!companion.waypointsAllowed()) {
                return;
            }
            final Waypoint waypoint = findWaypoint(waypointId);
            if (waypoint != null && client.player != null && MinecraftAccess.screen(client) == null) {
                MinecraftAccess.setScreen(client, WaypointEditScreen.forEdit(null, waypoint));
            }
        });
    }

    @Override
    public CompletableFuture<TeleportResult> requestTeleport(final UUID waypointId) {
        if (waypointId == null) {
            return CompletableFuture.completedFuture(TeleportResult.NO_WAYPOINT);
        }
        return ConfluxMapApiImpl.callOnMain(bridge, () -> {
            final SessionGuard.Session session = guard.current();
            if (!session.active()) {
                return TeleportResult.NO_SESSION;
            }
            if (!companion.waypointsAllowed()) {
                return TeleportResult.FORBIDDEN_BY_SERVER;
            }
            final Waypoint waypoint = findWaypoint(waypointId);
            if (waypoint == null) {
                return TeleportResult.NO_WAYPOINT;
            }
            groundTeleport.teleportExact(
                waypoint.x, waypoint.y, waypoint.z, waypoint.dimensionId, session.world()
            );
            return TeleportResult.STARTED;
        });
    }

    @Override
    public LayerOverride layerOverride() {
        return fromConfig(config.layerOverride);
    }

    @Override
    public void setLayerOverride(final LayerOverride override) {
        onMain(() -> {
            config.layerOverride = toConfig(override);
            configIo.save(config);
        });
    }

    @Override
    public ApiLayer activeLayer() {
        return ApiLayers.fromCore(layerSelector.current().layer());
    }

    private void onMain(final Runnable action) {
        if (bridge.isOnRenderThread()) {
            action.run();
        } else {
            bridge.runOnRenderThread(action);
        }
    }

    private Waypoint findWaypoint(final UUID id) {
        for (final Waypoint waypoint : waypoints.list()) {
            if (waypoint.id.equals(id)) {
                return waypoint;
            }
        }
        return null;
    }

    private static LayerOverride fromConfig(final ConfluxConfig.LayerOverride value) {
        if (value == ConfluxConfig.LayerOverride.FORCE_SURFACE) {
            return LayerOverride.SURFACE;
        }
        if (value == ConfluxConfig.LayerOverride.FORCE_UNDERGROUND) {
            return LayerOverride.UNDERGROUND;
        }
        return LayerOverride.AUTO;
    }

    private static ConfluxConfig.LayerOverride toConfig(final LayerOverride value) {
        if (value == LayerOverride.SURFACE) {
            return ConfluxConfig.LayerOverride.FORCE_SURFACE;
        }
        if (value == LayerOverride.UNDERGROUND) {
            return ConfluxConfig.LayerOverride.FORCE_UNDERGROUND;
        }
        return ConfluxConfig.LayerOverride.AUTO;
    }
}
