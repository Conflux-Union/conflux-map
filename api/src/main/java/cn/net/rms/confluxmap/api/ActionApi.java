package cn.net.rms.confluxmap.api;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Screen, layer, and teleport actions. The void methods are fire-and-forget and safe
 * from any thread - the mod marshals them onto the client main thread; conditions that
 * prevent an action (no world session, another screen open) are logged by the mod, not
 * reported back. {@link #activeLayer()} reads the render-thread state and is best called
 * from the main thread.
 */
@ApiStatus.Experimental
public interface ActionApi {
    /** Opens the fullscreen map, honoring the same guards as the map keybind. */
    void openFullscreenMap();

    /** Closes the fullscreen map if it is currently open. */
    void closeFullscreenMap();

    /** Opens the waypoint list screen. */
    void openWaypointList();

    /** Opens the edit screen of one existing waypoint. */
    void openWaypointEditor(UUID waypointId);

    /**
     * Teleports to a stored waypoint through the mod's ground-teleport path, which
     * honors the configured command template and any server-side restrictions.
     */
    CompletableFuture<TeleportResult> requestTeleport(UUID waypointId);

    /** The configured layer override; reflects the config at call time. */
    LayerOverride layerOverride();

    /** Sets the layer override and persists the config. */
    void setLayerOverride(LayerOverride override);

    /** The layer the map is currently showing. */
    MapDataApi.ApiLayer activeLayer();

    enum TeleportResult {
        STARTED,
        NO_WAYPOINT,
        NO_SESSION,
        FORBIDDEN_BY_SERVER
    }

    enum LayerOverride {
        AUTO,
        SURFACE,
        UNDERGROUND
    }
}
