package cn.net.rms.confluxmap.mc.input;

import cn.net.rms.confluxmap.compat.Keys;

/** One user action shared by the vanilla and optional MaliLib keybind backends. */
public enum KeybindAction {
    TOGGLE_MINIMAP("toggle_minimap", "toggleMinimap", Keys.H, "H"),
    CYCLE_ZOOM("zoom_in", "zoomIn", Keys.RIGHT_BRACKET, "RIGHT_BRACKET"),
    OPEN_MAP("open_map", "openMap", Keys.M, "M"),
    CYCLE_LAYER("cycle_layer", "cycleLayer", Keys.Y, "Y"),
    TOGGLE_LOADED_RANGE("toggle_loaded_range", "toggleLoadedRange", -1, ""),
    TOGGLE_CHUNK_GRID("toggle_chunk_grid", "toggleChunkGrid", -1, ""),
    OPEN_WAYPOINTS("waypoints", "openWaypoints", Keys.U, "U"),
    TEMPORARY_WAYPOINT("temporary_waypoint", "temporaryWaypoint", -1, ""),
    NEW_WAYPOINT("new_waypoint", "newWaypoint", Keys.B, "B"),
    TOGGLE_LOCAL_WAYPOINTS("toggle_local_waypoints", "toggleLocalWaypoints", Keys.J, "J"),
    OPEN_CONFIG("open_config", "openConfig", Keys.COMMA, "COMMA"),
    CYCLE_PREDICTION("cycle_prediction", "cyclePrediction", Keys.P, "P"),
    RELOAD_PREDICTION("reload_prediction", "reloadPrediction", Keys.F9, "F9");

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

    boolean mapViewAction() {
        return this == TOGGLE_LOADED_RANGE || this == TOGGLE_CHUNK_GRID;
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
