package cn.net.rms.confluxmap.core.waypoint.library;

import cn.net.rms.confluxmap.core.model.WorldIdentity;
import com.google.gson.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executor;
import org.apache.logging.log4j.Logger;

/** Persists browsing preferences only. A corrupt/future document is never overwritten. */
public final class WaypointListMemory {
    public record State(String tab, String dimensionMode, String dimension, String set,
                        String search, int nativeScroll, int compactScroll) {
        public State {
            tab = "PUBLIC".equals(tab) ? "PUBLIC" : "LOCAL";
            dimensionMode = "ALL".equals(dimensionMode) || "ONLY".equals(dimensionMode) ? dimensionMode : "CURRENT";
            search = search == null ? "" : search.substring(0, Math.min(64, search.length()));
            nativeScroll = Math.max(0, nativeScroll); compactScroll = Math.max(0, compactScroll);
        }
    }
    public enum MapPhotoMode { OFF, ICON, THUMBNAIL }

    public static final class Options {
        public boolean favorites = true;
        public boolean details = true;
        public boolean images = true;
        public MapPhotoMode screenshotMode = MapPhotoMode.OFF;
        private MapPhotoMode lastScreenshotMode = MapPhotoMode.ICON;

        public void setScreenshotMode(MapPhotoMode mode) {
            if (screenshotMode != null && screenshotMode != MapPhotoMode.OFF) lastScreenshotMode = screenshotMode;
            screenshotMode = mode;
            if (mode != MapPhotoMode.OFF) lastScreenshotMode = mode;
        }

        public void toggleScreenshots() {
            setScreenshotMode(screenshotMode != MapPhotoMode.OFF ? MapPhotoMode.OFF
                : lastScreenshotMode == null || lastScreenshotMode == MapPhotoMode.OFF ? MapPhotoMode.ICON : lastScreenshotMode);
        }
        public boolean listPhotoPreview = true;
        public MapPhotoMode waypointPhotoMode = MapPhotoMode.ICON;
        public boolean photoGestures = true;
        public boolean hoverActions = true;
        public boolean sourcePriority = true;
        public boolean temporaryWaypoint = true;
        public boolean unknownHeightFallback;
        public boolean compact, both, localFirst, dimension, distance, quickPublish;
        public boolean remember = true;
        public boolean defaultPublic, allDimensions, closeAfterTeleport;
        public int unknownHeight = 128;
        public String temporaryKeys = "";
        private int compactWidthPercent = 100;
        private int compactHeightPercent = 100;

        public int compactWidthPercent() { return WaypointGridLayout.clampPercent(compactWidthPercent); }
        public int compactHeightPercent() { return WaypointGridLayout.clampPercent(compactHeightPercent); }

        public void setCompactWidthPercent(int value) { compactWidthPercent = WaypointGridLayout.clampPercent(value); }
        public void setCompactHeightPercent(int value) { compactHeightPercent = WaypointGridLayout.clampPercent(value); }
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting()
        .registerTypeAdapter(State.class, WaypointJson.readUsing(StateData.class, StateData::toState))
        .create();
    private static final class StateData {
        String tab, dimensionMode, dimension, set, search;
        int nativeScroll, compactScroll;

        State toState() {
            return new State(tab, dimensionMode, dimension, set, search, nativeScroll, compactScroll);
        }
    }
    private static final class Document {
        int version = 1;
        int uiRevision;
        Options options = new Options();
        Map<String, State> worlds = new LinkedHashMap<>();
        java.util.Set<String> pendingLegacy;
    }
    private final Path file;
    private final Executor executor;
    private final Logger logger;
    private Document document = new Document();
    private boolean readOnly;
    public WaypointListMemory(Path file, Path legacyDirectory, Executor executor, Logger logger) {
        this.file = file; this.executor = executor; this.logger = logger;
        if (Files.exists(file)) {
            try {
                JsonObject json = GSON.fromJson(Files.readString(file), JsonObject.class);
                if (!json.has("version") || !json.get("version").isJsonPrimitive() || !json.getAsJsonPrimitive("version").isNumber() || json.get("version").getAsBigDecimal().compareTo(java.math.BigDecimal.ONE) != 0) throw new IOException("Unsupported browsing state schema");
                document = GSON.fromJson(json, Document.class);
                if (document.worlds == null || document.options == null) throw new IOException("Invalid browsing state");
                final JsonObject options = json.getAsJsonObject("options");
                if (options != null) {
                    document.options.screenshotMode = migratePhotoMode(options, "screenshotMode", "screenshotsOnMap",
                        document.options.screenshotMode, MapPhotoMode.OFF);
                    document.options.waypointPhotoMode = migratePhotoMode(options, "waypointPhotoMode", "mapPhotoPreview",
                        document.options.waypointPhotoMode, MapPhotoMode.ICON);
                }
            } catch (IOException | RuntimeException error) { readOnly = true; document = new Document(); logger.error("Browsing state preserved read-only: {}", file, error); }
        } else {
            document.pendingLegacy = new java.util.LinkedHashSet<>(LEGACY_OPTIONS.keySet());
            document.pendingLegacy.add(LEGACY_WORLDS);
        }
        if (!readOnly && document.pendingLegacy != null && !document.pendingLegacy.isEmpty()) {
            migrate(legacyDirectory);
            save();
        }
        // The revised UI defaults to the native list once, retaining every feature's data.
        if (!readOnly && document.uiRevision < 2) {
            options().compact = false;
            document.uiRevision = 2;
            save();
        }
    }
    private static MapPhotoMode migratePhotoMode(JsonObject options, String currentKey, String previousKey,
                                                MapPhotoMode current, MapPhotoMode fallback) {
        if (options.has(currentKey)) return current == null ? fallback : current;
        final JsonElement previous = options.get(previousKey);
        if (previous != null && previous.isJsonPrimitive() && previous.getAsJsonPrimitive().isBoolean()) {
            return previous.getAsBoolean() ? MapPhotoMode.ICON : MapPhotoMode.OFF;
        }
        return fallback;
    }

    private static String key(WorldIdentity world) { return world.serverId() + "\n" + world.worldId(); }
    public Options options() { return document.options; }
    public State get(WorldIdentity world) { return world == null || !options().remember ? null : document.worlds.get(key(world)); }
    public void put(WorldIdentity world, State state) {
        if (world != null && options().remember && !readOnly && !state.equals(document.worlds.put(key(world), state))) save();
    }
    public void save() {
        if (readOnly) return;
        final String snapshot = GSON.toJson(document);
        executor.execute(() -> {
            Path temp = null;
            try {
                Files.createDirectories(file.getParent()); temp = Files.createTempFile(file.getParent(), "list-", ".tmp");
                Files.writeString(temp, snapshot);
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException error) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING); }
            } catch (IOException error) { logger.error("Cannot save waypoint browsing state {}", file, error); }
            finally { if (temp != null) try { Files.deleteIfExists(temp); } catch (IOException error) { logger.warn("Cannot remove browsing temporary file {}", temp, error); } }
        });
    }
    private static final String LEGACY_WORLDS = "conflux-waypoint-list.json";
    private static final String LEGACY_ORDER = "confluxMapWaypointOrder";
    private static final String LEGACY_HEIGHT = "confluxMapUnknownHeight";
    private static final String LEGACY_HOTKEY = "confluxMapTemporaryWaypoint";
    private static final Map<String, String> LEGACY_OPTIONS = Map.ofEntries(
        Map.entry("confluxMapShowBothWaypoints", "both"),
        Map.entry("confluxMapWaypointDimension", "dimension"),
        Map.entry("confluxMapWaypointDistance", "distance"),
        Map.entry("confluxMapQuickPublish", "quickPublish"),
        Map.entry("confluxMapRememberList", "remember"),
        Map.entry("confluxMapPublicWaypoints", "defaultPublic"),
        Map.entry("confluxMapAllDimensions", "allDimensions"),
        Map.entry("confluxMapCloseAfterTeleport", "closeAfterTeleport"),
        Map.entry(LEGACY_ORDER, "localFirst"),
        Map.entry(LEGACY_HEIGHT, "unknownHeight"),
        Map.entry(LEGACY_HOTKEY, "temporaryKeys")
    );

    private void migrate(Path directory) {
        if (document.pendingLegacy.contains(LEGACY_WORLDS)) {
            try {
                if (importLegacyWorlds(directory.resolve(LEGACY_WORLDS))) document.pendingLegacy.remove(LEGACY_WORLDS);
            } catch (IOException | RuntimeException error) {
                logger.warn("Could not read legacy waypoint browsing state; will retry", error);
            }
        }
        if (document.pendingLegacy.isEmpty()) return;
        try {
            final Path config = directory.resolve("halfmasa.json");
            final JsonObject merged = new JsonObject();
            if (!Files.notExists(config)) {
                final var root = GSON.fromJson(Files.readString(config), JsonObject.class);
                for (String category : new String[]{"Ported", "Extensions"}) {
                    if (root.has(category)) root.getAsJsonObject(category).entrySet()
                        .forEach(entry -> merged.add(entry.getKey(), entry.getValue()));
                }
            }
            final var defaults = GSON.toJsonTree(new Options()).getAsJsonObject();
            final var options = GSON.toJsonTree(document.options).getAsJsonObject();
            for (var entry : LEGACY_OPTIONS.entrySet()) {
                final String legacy = entry.getKey();
                final String target = entry.getValue();
                if (!document.pendingLegacy.contains(legacy)) continue;
                try {
                    // A newer native value takes precedence if the user changed it while import was pending.
                    if (merged.has(legacy) && java.util.Objects.equals(options.get(target), defaults.get(target))) {
                        options.add(target, legacyValue(legacy, merged.get(legacy)));
                    }
                    document.pendingLegacy.remove(legacy);
                } catch (RuntimeException error) {
                    logger.warn("Invalid legacy waypoint option {}; will retry", legacy, error);
                }
            }
            document.options = GSON.fromJson(options, Options.class);
        } catch (IOException | RuntimeException error) {
            logger.warn("Could not read legacy waypoint options; will retry", error);
        }
    }

    private boolean importLegacyWorlds(Path file) throws IOException {
        if (Files.notExists(file)) return true;
        final var json = GSON.fromJson(Files.readString(file), JsonObject.class);
        if (!json.has("version") || json.get("version").getAsInt() != 1 || !json.has("worlds")) {
            throw new IOException("Unsupported legacy browsing state schema");
        }
        boolean complete = true;
        for (var entry : json.getAsJsonObject("worlds").entrySet()) {
            if (document.worlds.containsKey(entry.getKey())) continue;
            try {
                final State state = GSON.fromJson(entry.getValue(), State.class);
                if (state == null) throw new JsonParseException("Missing legacy browsing state");
                document.worlds.put(entry.getKey(), state);
            } catch (RuntimeException error) {
                complete = false;
                logger.warn("Invalid legacy waypoint browsing state for {}; will retry", entry.getKey(), error);
            }
        }
        return complete;
    }

    private static JsonElement legacyValue(String key, JsonElement value) {
        if (LEGACY_ORDER.equals(key)) return new JsonPrimitive("personal_first".equalsIgnoreCase(value.getAsString()));
        if (LEGACY_HEIGHT.equals(key)) return new JsonPrimitive(Math.max(-2048, Math.min(4096, value.getAsInt())));
        if (LEGACY_HOTKEY.equals(key)) {
            final var bindings = value.getAsJsonObject();
            return new JsonPrimitive(bindings.has("keys") ? bindings.get("keys").getAsString() : "");
        }
        // MaliLib boolean-hotkeys store {enabled, hotkey}; plain booleans store a primitive.
        if (value.isJsonObject()) value = value.getAsJsonObject().get("enabled");
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw new JsonParseException("Invalid legacy boolean");
        }
        return value;
    }

    /** Inject only a missing native key, preserving even an explicitly unbound user choice. */
    public static boolean importTemporaryHotkey(JsonObject root, String legacyKeys) {
        if (legacyKeys == null || legacyKeys.isBlank()) return false;
        if (!root.has("hotkeys")) root.add("hotkeys", new JsonObject());
        JsonObject hotkeys = root.getAsJsonObject("hotkeys");
        if (hotkeys.has("temporaryWaypoint")) return false;
        JsonObject value = new JsonObject(); value.addProperty("keys", legacyKeys);
        hotkeys.add("temporaryWaypoint", value); return true;
    }
}
