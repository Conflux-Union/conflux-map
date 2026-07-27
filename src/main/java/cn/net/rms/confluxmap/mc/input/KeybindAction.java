package cn.net.rms.confluxmap.mc.input;

import org.lwjgl.glfw.GLFW;

/** One user action shared by the vanilla and optional MaliLib keybind backends. */
enum KeybindAction {
    TOGGLE_MINIMAP("toggle_minimap", "toggleMinimap", GLFW.GLFW_KEY_H, "H"),
    ZOOM_IN("zoom_in", "zoomIn", GLFW.GLFW_KEY_RIGHT_BRACKET, "RIGHT_BRACKET"),
    ZOOM_OUT("zoom_out", "zoomOut", GLFW.GLFW_KEY_LEFT_BRACKET, "LEFT_BRACKET"),
    OPEN_MAP("open_map", "openMap", GLFW.GLFW_KEY_M, "M"),
    CYCLE_LAYER("cycle_layer", "cycleLayer", GLFW.GLFW_KEY_Y, "Y"),
    OPEN_WAYPOINTS("waypoints", "openWaypoints", GLFW.GLFW_KEY_U, "U"),
    NEW_WAYPOINT("new_waypoint", "newWaypoint", GLFW.GLFW_KEY_B, "B"),
    TOGGLE_LOCAL_WAYPOINTS("toggle_local_waypoints", "toggleLocalWaypoints", GLFW.GLFW_KEY_J, "J"),
    OPEN_CONFIG("open_config", "openConfig", GLFW.GLFW_KEY_COMMA, "COMMA"),
    CYCLE_PREDICTION("cycle_prediction", "cyclePrediction", GLFW.GLFW_KEY_P, "P"),
    RELOAD_PREDICTION("reload_prediction", "reloadPrediction", GLFW.GLFW_KEY_F9, "F9");

    private final String translationSuffix;
    private final String configName;
    private final int vanillaDefaultKey;
    private final String maliLibDefaultKeys;

    KeybindAction(
        final String translationSuffix,
        final String configName,
        final int vanillaDefaultKey,
        final String maliLibDefaultKeys
    ) {
        this.translationSuffix = translationSuffix;
        this.configName = configName;
        this.vanillaDefaultKey = vanillaDefaultKey;
        this.maliLibDefaultKeys = maliLibDefaultKeys;
    }

    String translationKey() {
        return "key.confluxmap." + translationSuffix;
    }

    String configName() {
        return configName;
    }

    int vanillaDefaultKey() {
        return vanillaDefaultKey;
    }

    String maliLibDefaultKeys() {
        return maliLibDefaultKeys;
    }
}
