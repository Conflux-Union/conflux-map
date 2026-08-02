package cn.net.rms.confluxmap.core.config;

import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadDetailMode;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldPolicy;
import cn.net.rms.confluxmap.core.survey.SurveyReminderSchedule;
import cn.net.rms.confluxmap.core.util.TileMath;

/**
 * All client settings, serialized as one JSON document.
 * Add fields with defaults only; never rename without bumping
 * {@link #SCHEMA_VERSION} and adding a migration in {@link ConfigIo}.
 */
public final class ConfluxConfig {
    public static final int SCHEMA_VERSION = 6;
    public static final int DEFAULT_MINIMAP_SIZE = 90;
    public static final int MIN_ANNOTATION_ERASER_SIZE = 4;
    public static final int MAX_ANNOTATION_ERASER_SIZE = 64;
    public static final int DEFAULT_ANNOTATION_ERASER_SIZE = 16;
    public static final int MIN_RADAR_ICON_SIZE = 4;
    public static final int MAX_RADAR_ICON_SIZE = 16;
    public static final int DEFAULT_RADAR_ICON_SIZE = 10;
    public static final int MIN_PLAYER_TRAIL_DURATION_SECONDS = 1;
    public static final int MAX_PLAYER_TRAIL_DURATION_SECONDS = 120;
    public static final int DEFAULT_PLAYER_TRAIL_DURATION_SECONDS = 120;
    public static final int MIN_PLAYER_TRAIL_DOT_SIZE = 1;
    public static final int MAX_PLAYER_TRAIL_DOT_SIZE = 8;
    public static final int DEFAULT_PLAYER_TRAIL_DOT_SIZE = 3;
    /** Always hide structure icons at the furthest fullscreen-map zoom. */
    public static final double MIN_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM = 0.0625;
    /** Largest fullscreen-map zoom multiplier the renderer can display. */
    public static final double MAX_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM = 4.0;
    /** Preserve the old default: hide only at the furthest 0.0625x zoom. */
    public static final double DEFAULT_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM = 0.0625;
    /** Schema-v4 lower bound for the now-retired blocks-per-pixel setting. */
    private static final double LEGACY_MIN_PREDICTION_STRUCTURE_ICON_HIDE_SCALE = 0.25;
    /** Schema-v4 upper bound for the now-retired blocks-per-pixel setting. */
    private static final double LEGACY_MAX_PREDICTION_STRUCTURE_ICON_HIDE_SCALE = 16.0;

    public int schemaVersion = SCHEMA_VERSION;

    /** Serialized only by schema v1; retained so old files can be migrated. */
    @Deprecated
    public enum Corner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

    public enum Shape { SQUARE, CIRCLE }

    /**
     * Manual layer override cycled by {@code key.confluxmap.cycle_layer}; see
     * {@code mc.world.LayerSelector} for how each dimension interprets these
     * (e.g. FORCE_UNDERGROUND means CAVE_AUTO in the Overworld, NETHER_CEILING
     * in the Nether, and is a no-op in the End).
     */
    public enum LayerOverride { AUTO, FORCE_SURFACE, FORCE_UNDERGROUND }

    public boolean minimapEnabled = true;
    /** Legacy schema-v1 field. Null after migration and omitted from newly written JSON. */
    @Deprecated
    public Corner minimapCorner;
    /** Top-left origin as a fraction of the available horizontal HUD travel. */
    public double minimapPositionX = 1.0;
    /** Top-left origin as a fraction of the available vertical HUD travel. */
    public double minimapPositionY = 0.0;
    public Shape minimapShape = Shape.SQUARE;
    public int minimapSize = DEFAULT_MINIMAP_SIZE;
    public boolean minimapRotate = true;
    public int minimapZoomIndex = 1;
    public boolean showCoordinates = true;
    public boolean showBiome = true;
    /** Show recent player movement as fading red dots on both map surfaces. */
    public boolean playerTrailEnabled = true;
    /** Serialized only by schema v2; retained so old files can be migrated. */
    @Deprecated
    public Integer playerTrailDurationMinutes;
    /** Wall-clock retention window for player trail samples. */
    public int playerTrailDurationSeconds = DEFAULT_PLAYER_TRAIL_DURATION_SECONDS;
    /** Player trail dot diameter in screen pixels. */
    public int playerTrailDotSize = DEFAULT_PLAYER_TRAIL_DOT_SIZE;
    /** Fullscreen map only: subtle chunk-border grid with a highlight on the hovered chunk. */
    public boolean fullmapChunkGrid = true;
    /** Copy the private annotation layer onto the minimap HUD. Fullscreen annotations remain visible. */
    public boolean annotationsOnHud = true;
    /** Fullscreen annotation eraser diameter in screen pixels. */
    public int annotationEraserSize = DEFAULT_ANNOTATION_ERASER_SIZE;
    /** Last requested fullscreen base plane; unavailable server-authoritative modes fall back safely. */
    public FullscreenDisplayMode fullscreenDisplayMode = FullscreenDisplayMode.TERRAIN;
    /** Local presentation choice for server chunk levels. */
    public ChunkLoadDetailMode chunkLoadDetailMode = ChunkLoadDetailMode.BANDS;

    /** cave-nether-layers.md §1/§6: manual pin, or AUTO for the per-dimension automatic detection. */
    public LayerOverride layerOverride = LayerOverride.AUTO;
    /** Minimap/fullscreen-map info line: a small text label naming the currently active layer. */
    public boolean showLayerIndicator = true;
    /** Fixed-band Y for {@code MapLayer.CAVE_SLICE}; not yet reachable via the cycle keybind (UI deferred). */
    public int caveSliceY = 32;
    /** Fixed-band Y for {@code MapLayer.NETHER_SLICE}; not yet reachable via the cycle keybind (UI deferred). */
    public int netherSliceY = 64;
    /**
     * VoxelMap-style day/night + block-light darkening on the SURFACE layer only (cave/nether/end
     * layers always keep their baked light). Off = exactly today's fixed-brightness rendering.
     */
    public boolean dynamicLighting = true;

    public int snapshotBudgetPerTick = 8;
    public int gpuTileCacheLimit = 256;
    /** Advanced client-only proxy world recognition limits; all values are safety-clamped. */
    public int clientWorldMaxProfilesPerServer = ClientWorldPolicy.DEFAULT_MAX_PROFILES_PER_SERVER;
    public int clientWorldMaxBindingsPerProfile = ClientWorldPolicy.DEFAULT_MAX_BINDINGS_PER_PROFILE;
    public int clientWorldCommandConfirmationSeconds = ClientWorldPolicy.DEFAULT_COMMAND_CONFIRMATION_SECONDS;
    public int clientWorldVisitRefreshSeconds = ClientWorldPolicy.DEFAULT_VISIT_REFRESH_SECONDS;
    public int clientWorldVisitRefreshDistance = ClientWorldPolicy.DEFAULT_VISIT_REFRESH_DISTANCE;

    /** Master toggle for the whole entity-radar overlay (docs/reference-specs/radar-icons.md sec 4). */
    public boolean radarEnabled = true;
    public boolean radarShowPlayers = true;
    public boolean radarShowHostile = true;
    /** Spec default for the "neutral" category is off; M1's PASSIVE bucket is that same category. */
    public boolean radarShowPassive = false;
    /** Dropped items, vehicles, projectiles, and defensive fallback targets; off by default to limit clutter. */
    public boolean radarShowOther = false;
    public boolean radarShowPlayerNames = true;
    public int radarMaxEntities = 100;
    /** Entity head and item-form icons instead of plain shaped dots when an in-game icon is available. */
    public boolean radarIconsEnabled = true;
    /** Screen-pixel size shared by entity faces and item-form radar icons. */
    public int radarIconSize = DEFAULT_RADAR_ICON_SIZE;
    /** 3-D straight-line blocks; 0 means "no cutoff" (see waypoint-ux.md S7). */
    public int waypointRenderDistance = 0;
    /** Show private, client-owned waypoints on every map/world rendering surface. */
    public boolean localWaypointsVisible = true;
    /** Show server-synchronized public waypoints on every map/world rendering surface. */
    public boolean sharedWaypointsVisible = true;
    /**
     * Show Overworld/Nether waypoints from the portal-linked dimension with the 8:1 coordinate
     * conversion applied on display (waypoint-ux.md S3). Off keeps exact-dimension display.
     */
    public boolean waypointCrossDimensionEnabled = false;
    public boolean waypointEdgeIndicatorsEnabled = true;
    /** Death points kept per dimension, oldest auto-pruned; 0 disables creating new ones. */
    public int deathPointsKept = 5;
    /** In-world vertical beam at each visible waypoint's column (see {@code mc.ui.world.WaypointWorldRenderer}). */
    public boolean waypointBeamsEnabled = true;
    /** In-world floating name/distance label above each visible waypoint. */
    public boolean waypointLabelsEnabled = true;

    /** Master toggle for the M2 seed-predicted fullscreen-map underlay (singleplayer only this slice). */
    public boolean predictionEnabled = true;
    /**
     * Client-side opt-out for the companion handshake / correction sync. When false the client
     * skips HELLO_C2S and never sends MAP_VIEW_REQ; the predicted underlay still works in
     * singleplayer. S5's request planner reads this flag; S3 only adds it so the config schema
     * is stable before the sync loop lands.
     */
    public boolean predictionNetworkSync = true;
    /** View filter for the predicted plane; EVERYWHERE is the honest default. */
    public PredictionViewMode predictionViewMode = PredictionViewMode.EVERYWHERE;
    public boolean predictionShowStructures = true;
    /** Hide fullscreen-map structure markers at or below this displayed zoom multiplier. */
    public double predictionStructureIconHideZoom = DEFAULT_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM;
    /** Schema-v4 setting retained solely to migrate the incorrectly exposed blocks-per-pixel value. */
    @Deprecated
    public Double predictionStructureIconHideScale;
    /** Schema-v3 setting retained solely to migrate old discrete LOD values. */
    @Deprecated
    public Integer predictionStructureMaxLod;
    /** Per-version and per-dimension structure-type visibility profiles. */
    public StructureVisibilityConfig predictionStructureVisibility = new StructureVisibilityConfig();
    /** Pan-settle debounce, clamped to 100..2000 ms. */
    public int predictionDebounceMs = 300;

    /** Startup GitHub release probe; drives the chat notice and the fullscreen-map badge. */
    public boolean updateCheckEnabled = true;

    /** Cumulative client-open time used only to schedule the optional survey chat reminder. */
    public long surveyReminderGameOpenMillis;
    /** Cumulative client-open time at which the next survey reminder becomes due. */
    public long surveyReminderNextPromptAtMillis = SurveyReminderSchedule.FIRST_DELAY_MILLIS;
    /** Permanent local opt-out selected through the survey reminder's chat action. */
    public boolean surveyReminderDismissed;

    public ConfluxConfig copy() {
        final ConfluxConfig c = new ConfluxConfig();
        c.schemaVersion = schemaVersion;
        c.minimapEnabled = minimapEnabled;
        c.minimapCorner = minimapCorner;
        c.minimapPositionX = minimapPositionX;
        c.minimapPositionY = minimapPositionY;
        c.minimapShape = minimapShape;
        c.minimapSize = minimapSize;
        c.minimapRotate = minimapRotate;
        c.minimapZoomIndex = minimapZoomIndex;
        c.showCoordinates = showCoordinates;
        c.showBiome = showBiome;
        c.playerTrailEnabled = playerTrailEnabled;
        c.playerTrailDurationSeconds = playerTrailDurationSeconds;
        c.playerTrailDotSize = playerTrailDotSize;
        c.fullmapChunkGrid = fullmapChunkGrid;
        c.annotationsOnHud = annotationsOnHud;
        c.annotationEraserSize = annotationEraserSize;
        c.fullscreenDisplayMode = fullscreenDisplayMode;
        c.chunkLoadDetailMode = chunkLoadDetailMode;
        c.layerOverride = layerOverride;
        c.showLayerIndicator = showLayerIndicator;
        c.caveSliceY = caveSliceY;
        c.netherSliceY = netherSliceY;
        c.dynamicLighting = dynamicLighting;
        c.snapshotBudgetPerTick = snapshotBudgetPerTick;
        c.gpuTileCacheLimit = gpuTileCacheLimit;
        c.clientWorldMaxProfilesPerServer = clientWorldMaxProfilesPerServer;
        c.clientWorldMaxBindingsPerProfile = clientWorldMaxBindingsPerProfile;
        c.clientWorldCommandConfirmationSeconds = clientWorldCommandConfirmationSeconds;
        c.clientWorldVisitRefreshSeconds = clientWorldVisitRefreshSeconds;
        c.clientWorldVisitRefreshDistance = clientWorldVisitRefreshDistance;
        c.radarEnabled = radarEnabled;
        c.radarShowPlayers = radarShowPlayers;
        c.radarShowHostile = radarShowHostile;
        c.radarShowPassive = radarShowPassive;
        c.radarShowOther = radarShowOther;
        c.radarShowPlayerNames = radarShowPlayerNames;
        c.radarMaxEntities = radarMaxEntities;
        c.radarIconsEnabled = radarIconsEnabled;
        c.radarIconSize = radarIconSize;
        c.waypointRenderDistance = waypointRenderDistance;
        c.localWaypointsVisible = localWaypointsVisible;
        c.sharedWaypointsVisible = sharedWaypointsVisible;
        c.waypointCrossDimensionEnabled = waypointCrossDimensionEnabled;
        c.waypointEdgeIndicatorsEnabled = waypointEdgeIndicatorsEnabled;
        c.deathPointsKept = deathPointsKept;
        c.waypointBeamsEnabled = waypointBeamsEnabled;
        c.waypointLabelsEnabled = waypointLabelsEnabled;
        c.predictionEnabled = predictionEnabled;
        c.predictionNetworkSync = predictionNetworkSync;
        c.predictionViewMode = predictionViewMode;
        c.predictionShowStructures = predictionShowStructures;
        c.predictionStructureIconHideZoom = predictionStructureIconHideZoom;
        c.predictionStructureVisibility = predictionStructureVisibility == null
            ? new StructureVisibilityConfig()
            : predictionStructureVisibility.copy();
        c.predictionDebounceMs = predictionDebounceMs;
        c.updateCheckEnabled = updateCheckEnabled;
        c.surveyReminderGameOpenMillis = surveyReminderGameOpenMillis;
        c.surveyReminderNextPromptAtMillis = surveyReminderNextPromptAtMillis;
        c.surveyReminderDismissed = surveyReminderDismissed;
        return c;
    }

    /** Clamp out-of-range values loaded from a hand-edited file. */
    public void normalize() {
        if (schemaVersion < 2) {
            final MinimapPlacement.Position migrated = MinimapPlacement.fromLegacyCorner(minimapCorner);
            minimapPositionX = migrated.x();
            minimapPositionY = migrated.y();
        }
        minimapCorner = null;
        final MinimapPlacement.Position position = MinimapPlacement.normalize(minimapPositionX, minimapPositionY);
        minimapPositionX = position.x();
        minimapPositionY = position.y();
        if (minimapShape == null) {
            minimapShape = Shape.SQUARE;
        }
        if (layerOverride == null) {
            layerOverride = LayerOverride.AUTO;
        }
        if (fullscreenDisplayMode == null) {
            fullscreenDisplayMode = FullscreenDisplayMode.TERRAIN;
        }
        if (chunkLoadDetailMode == null) {
            chunkLoadDetailMode = ChunkLoadDetailMode.BANDS;
        }
        if (schemaVersion < 3 && playerTrailDurationMinutes != null) {
            final long legacyDurationSeconds = playerTrailDurationMinutes.longValue() * 60L;
            playerTrailDurationSeconds = (int) Math.max(
                MIN_PLAYER_TRAIL_DURATION_SECONDS,
                Math.min(MAX_PLAYER_TRAIL_DURATION_SECONDS, legacyDurationSeconds)
            );
        }
        playerTrailDurationMinutes = null;
        minimapSize = clamp(minimapSize, 64, 256);
        minimapZoomIndex = clamp(minimapZoomIndex, 0, 3);
        playerTrailDurationSeconds = clamp(
            playerTrailDurationSeconds,
            MIN_PLAYER_TRAIL_DURATION_SECONDS,
            MAX_PLAYER_TRAIL_DURATION_SECONDS
        );
        playerTrailDotSize = clamp(
            playerTrailDotSize,
            MIN_PLAYER_TRAIL_DOT_SIZE,
            MAX_PLAYER_TRAIL_DOT_SIZE
        );
        annotationEraserSize = clamp(
            annotationEraserSize, MIN_ANNOTATION_ERASER_SIZE, MAX_ANNOTATION_ERASER_SIZE
        );
        caveSliceY = clamp(caveSliceY, 0, 255);
        netherSliceY = clamp(netherSliceY, 0, 127);
        snapshotBudgetPerTick = clamp(snapshotBudgetPerTick, 1, 64);
        gpuTileCacheLimit = clamp(gpuTileCacheLimit, 16, 2048);
        final ClientWorldPolicy clientWorldPolicy = clientWorldPolicy();
        clientWorldMaxProfilesPerServer = clientWorldPolicy.maxProfilesPerServer();
        clientWorldMaxBindingsPerProfile = clientWorldPolicy.maxBindingsPerProfile();
        clientWorldCommandConfirmationSeconds = clientWorldPolicy.commandConfirmationSeconds();
        clientWorldVisitRefreshSeconds = clientWorldPolicy.visitRefreshSeconds();
        clientWorldVisitRefreshDistance = clientWorldPolicy.visitRefreshDistance();
        radarMaxEntities = clamp(radarMaxEntities, 1, 500);
        radarIconSize = clamp(radarIconSize, MIN_RADAR_ICON_SIZE, MAX_RADAR_ICON_SIZE);
        waypointRenderDistance = clamp(waypointRenderDistance, 0, 100_000);
        deathPointsKept = clamp(deathPointsKept, 0, 50);
        if (predictionViewMode == null) {
            predictionViewMode = PredictionViewMode.EVERYWHERE;
        }
        if (schemaVersion < 4 && predictionStructureMaxLod != null) {
            final int legacyLod = clamp(predictionStructureMaxLod, 0, TileMath.MAX_LOD);
            predictionStructureIconHideZoom = legacyLod == TileMath.MAX_LOD
                ? 0.0
                : 1.0 / TileMath.blocksPerPixel(legacyLod + 1);
        } else if (schemaVersion < 5 && predictionStructureIconHideScale != null) {
            // Schema v4 stored blocks per pixel and normalized this value before use.
            // Clamp first so hand-edited v4 files retain their old effective behavior.
            final double legacyScale = clamp(
                predictionStructureIconHideScale,
                LEGACY_MIN_PREDICTION_STRUCTURE_ICON_HIDE_SCALE,
                LEGACY_MAX_PREDICTION_STRUCTURE_ICON_HIDE_SCALE
            );
            predictionStructureIconHideZoom = 1.0 / legacyScale;
        }
        predictionStructureMaxLod = null;
        predictionStructureIconHideScale = null;
        predictionStructureIconHideZoom = clamp(
            predictionStructureIconHideZoom,
            MIN_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM,
            MAX_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM
        );
        if (predictionStructureVisibility == null) {
            predictionStructureVisibility = new StructureVisibilityConfig();
        } else {
            predictionStructureVisibility.normalize();
        }
        predictionDebounceMs = clamp(predictionDebounceMs, 100, 2000);
        surveyReminderGameOpenMillis = Math.max(0L, surveyReminderGameOpenMillis);
        if (surveyReminderNextPromptAtMillis <= 0L) {
            surveyReminderNextPromptAtMillis = SurveyReminderSchedule.FIRST_DELAY_MILLIS;
        }
    }

    private static int clamp(final int v, final int min, final int max) {
        return Math.max(min, Math.min(max, v));
    }

    private static double clamp(final double value, final double min, final double max) {
        return Double.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
    }

    /** Returns a normalized snapshot so live config edits cannot bypass recognition safety caps. */
    public ClientWorldPolicy clientWorldPolicy() {
        return new ClientWorldPolicy(
            clientWorldMaxProfilesPerServer,
            clientWorldMaxBindingsPerProfile,
            clientWorldCommandConfirmationSeconds,
            clientWorldVisitRefreshSeconds,
            clientWorldVisitRefreshDistance
        );
    }
}
