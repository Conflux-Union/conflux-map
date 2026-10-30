package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.MouseButtons;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationDraft;
import cn.net.rms.confluxmap.api.ConfluxMapEvents;
import cn.net.rms.confluxmap.core.annotation.AnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.AnnotationPersistence;
import cn.net.rms.confluxmap.core.annotation.AnnotationPoint;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.api.ConfluxMapApiImpl;
import cn.net.rms.confluxmap.core.api.CustomMarkerService;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.annotation.AnnotationStore;
import cn.net.rms.confluxmap.core.annotation.AnnotationStyle;
import cn.net.rms.confluxmap.core.annotation.AnnotationTool;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.export.MapExportBounds;
import cn.net.rms.confluxmap.core.export.MapExportLoadState;
import cn.net.rms.confluxmap.core.export.MapExportRequest;
import cn.net.rms.confluxmap.core.export.MapExportResolution;
import cn.net.rms.confluxmap.core.export.MapExportSelection;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadDetailMode;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadOverlayStyle;
import cn.net.rms.confluxmap.core.loadstate.ChunkScreenRect;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.measure.MeasureFormat;
import cn.net.rms.confluxmap.core.measure.MeasurePath;
import cn.net.rms.confluxmap.core.measure.MeasureState;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.model.WorldIdentity;
import cn.net.rms.confluxmap.core.multiworld.ClientWorldProfile;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.net.HelloPolicyS2C;
import cn.net.rms.confluxmap.core.portal.PortalMarker;
import cn.net.rms.confluxmap.core.portal.PortalService;
import cn.net.rms.confluxmap.core.predict.BaselineSampler;
import cn.net.rms.confluxmap.core.predict.BiomeCandidateSearch;
import cn.net.rms.confluxmap.core.predict.NativeBaselineSampler;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarEntries;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.core.selection.ChunkSelectionMath;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.store.ColumnStore;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.trail.PlayerTrailProjection;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.util.TileViewport;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.WaypointStore;
import cn.net.rms.confluxmap.mc.net.ChunkLoadStateClient;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.teleport.ClientGroundTeleportService;
import cn.net.rms.confluxmap.mc.teleport.TeleportCommandAccess;
import cn.net.rms.confluxmap.mc.ui.BiomeLabels;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.MapLayerText;
import cn.net.rms.confluxmap.mc.ui.UiIcon;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.UiTextureRegion;
import cn.net.rms.confluxmap.mc.ui.AnnotationRenderer;
import cn.net.rms.confluxmap.mc.ui.DisplayModeIconCatalog;
import cn.net.rms.confluxmap.mc.ui.PlayerMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.PlayerTrailRenderer;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.world.WaypointHighlightState;
import cn.net.rms.confluxmap.mc.ui.PortalMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.StructureMarkerRenderer;
import cn.net.rms.confluxmap.mc.world.ClientChunkLookup;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import cn.net.rms.confluxmap.mc.world.ClientMultiworldService;
import cn.net.rms.confluxmap.mc.world.FullscreenMapBrowseService;
import cn.net.rms.confluxmap.mc.world.FullscreenMapLayerPolicy;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
//#if MC>=12109
//$$ import net.minecraft.client.gui.Click;
//$$ import net.minecraft.client.input.KeyInput;
//#endif
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.render.GameRenderer;
//#if MC>=12108
//$$ import net.minecraft.client.gl.RenderPipelines;
//#elseif MC>=12103
//$$ import net.minecraft.client.render.RenderLayer;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Fullscreen, panning/zooming explorable map. Opened and closed by the
 * {@code open_map} keybind (M by default); always north-locked (no rotation).
 * View state is continuous: {@link #centerX}/{@link #centerZ} is the world
 * point at screen center, {@link #scale} is blocks-per-screen-pixel, clamped
 * to {@link #MIN_SCALE}-{@link #MAX_SCALE}. The displayed LOD is derived from
 * scale ({@link #currentLod()}) so zooming out smoothly walks up the tile
 * pyramid {@link TileService} composes in core/.
 *
 * <p>Does not call {@link TileTextureManager#beginFrame()} itself - see the
 * javadoc on {@code MinimapTilePainter.beginFrame} for why that would double it up.
 */
public final class FullscreenMapScreen extends ConfluxScreen {

    private static final double MIN_SCALE = 0.125;
    private static final double MAX_SCALE = 16.0;
    private static final double DEFAULT_SCALE = 2.0;
    private static final double ZOOM_STEP = 1.26;
    private static final int DEFAULT_CANDIDATE_WAYPOINT_Y = 64;

    static final int MARGIN = 6;
    static final int CONTROL_SIZE = 22;
    private static final int CONTROL_ICON_SIZE = 16;
    static final int CONTROL_GAP = 3;
    private static final Identifier VIEW_GROUP_ICON = Ids.of(
        "confluxmap", "textures/gui/group_view.png"
    );
    private static final Identifier WAYPOINT_GROUP_ICON = Ids.of(
        "confluxmap", "textures/gui/group_waypoints.png"
    );
    private static final Identifier ACTIONS_GROUP_ICON = Ids.of(
        "confluxmap", "textures/gui/group_actions.png"
    );
    private static final Identifier LOCAL_WAYPOINT_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_local.png"
    );
    private static final Identifier LOCAL_WAYPOINT_OFF_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_local_off.png"
    );
    private static final Identifier SHARED_WAYPOINT_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_shared.png"
    );
    private static final Identifier SHARED_WAYPOINT_OFF_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_shared_off.png"
    );
    private static final Identifier MANAGE_WAYPOINT_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_manage.png"
    );
    private static final Identifier STRUCTURE_SEARCH_ICON = Ids.of(
        "confluxmap", "textures/gui/structure_search.png"
    );
    private static final Identifier STRUCTURE_SEARCH_OFF_ICON = Ids.of(
        "confluxmap", "textures/gui/structure_search_off.png"
    );
    private static final Identifier MAP_EXPORT_ICON = Ids.of(
        "confluxmap", "textures/gui/map_export.png"
    );
    private static final Identifier MAP_IMPORT_ICON = Ids.of(
        "confluxmap", "textures/gui/map_import.png"
    );
    private static final Identifier MAP_SETTINGS_ICON = Ids.of(
        "confluxmap", "textures/gui/map_settings.png"
    );
    private static final Identifier WORLD_PROFILE_ICON = Ids.of(
        "confluxmap", "textures/gui/world_profile.png"
    );
    private static final Identifier ANNOTATION_DRAWING_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_drawing.png"
    );
    static final int TEXT_COLOR = 0xFFFFFFFF;
    /** Measure overlay: teal line reads over green/brown terrain without clashing with the amber annotation palette. */
    /** Region selection: amber stays distinct from the teal measure and blue export overlays. */
    private static final int REGION_SELECT_FILL = 0x26FFE066;
    private static final int REGION_SELECT_BORDER = 0xFFFFE066;
    private static final int REGION_SELECT_LABEL_BACKGROUND = 0xD0181822;
    private static final int REGION_SELECT_LABEL_PADDING = 3;
    private static final int REGION_SELECT_LABEL_LINE_GAP = 2;
    static final int BACKGROUND_COLOR = 0xFF101018;
    private static final int GRID_COLOR = 0x22FFFFFF;
    private static final int PLAYER_MARKER_COLOR = 0xFFFFFFFF;
    private static final double MIN_GRID_SPACING_PX = 8.0;
    /** Xaero-style faint dark lattice on chunk borders, understated over both light and dark terrain. */
    private static final int CHUNK_GRID_COLOR = 0x40000000;
    private static final int CHUNK_HIGHLIGHT_FILL = 0x22FFFFFF;
    private static final int CHUNK_HIGHLIGHT_BORDER = 0x66FFFFFF;
    /** Below this on-screen chunk width, skip the chunk grid/highlight entirely to avoid moire noise when zoomed out. */
    private static final double MIN_CHUNK_GRID_SPACING_PX = 6.0;
    /** Half of the ~9px-across VoxelMap-style marker (deliverable C) - slightly larger than the minimap's ~7px. */
    private static final float MARKER_HALF_SIZE = 4.5f;
    /** Blocks-per-pixel threshold below which every marker's name shows continuously, not just on hover (deliverable C). */
    private static final double NAME_LABEL_MAX_SCALE = 2.0;
    private static final double HOVER_RADIUS_PX = 6.0;
    private static final double DEFAULT_CREATE_Y = 64.0;
    /** Cursor travel between left-press and left-release below which a hovered marker click edits it, not pans (see {@link #mouseReleased}). */
    static final double CLICK_DRAG_TOLERANCE_PX = 4.0;
    /** Radar markers are ~12px across; cull with enough margin that one straddling the edge doesn't pop. */
    private static final float RADAR_CULL_MARGIN = 8f;

    /** Null when MaliLib owns the binding and closes this screen through the shared action handler. */
    private final KeyBinding openMapKey;
    final GameBridge gameBridge;
    private final MapWorldService mapWorlds;
    private final TileService tiles;
    private final TileTextureManager textures;
    private final SplitMapBlur embeddedBlur = new SplitMapBlur();
    final DaylightModel daylightModel;
    final PredictionState predictionState;
    final PredictionTileService predictionTiles;
    final FullscreenMapViewState viewState;
    private final CustomMarkerService customMarkers;
    /** Guards the public API's map-opened event against the resize re-init of {@link #init()}. */
    private boolean openAnnounced;
    final LayerSelector layerSelector;
    final WaypointService waypointService;
    final AnnotationService annotationService;
    final WaypointRenderCatalog waypointRenderCatalog;
    final WaypointRenderCatalog archivedWaypointRenderCatalog;
    final ConfluxConfig config;
    final SharedWaypointClient sharedWaypoints;
    final CompanionSession companion;
    final ChunkLoadStateClient chunkLoadStates;
    private final EntityRadarScanner radarScanner;
    final ServerPlayerRadarState serverPlayerRadar;
    private final EntityIconManager radarIconManager;
    private final RadarViewRange radarViewRange;
    private final PlayerTrail playerTrail;
    final StructureMarkerService structureMarkers;
    private final PortalService portals;
    final UpdateCheckService updateCheck;
    final ClientGroundTeleportService groundTeleport;
    final ClientMultiworldService clientMultiworld;
    final FullscreenMapBrowseService mapBrowser;
    final UiResourceTheme uiTheme;
    private InitialFocus initialFocus;
    final WaypointHighlightState waypointHighlightState;

    /** World point currently at screen center, and blocks-per-pixel; all mutable, panned/zoomed by input. */
    double centerX;
    double centerZ;
    double scale;
    SplitMapLayout embeddedLayout;

    /** Recomputed every frame by {@link #drawWaypoints} - the marker nearest the cursor within {@link #HOVER_RADIUS_PX}, or none. */
    WaypointRenderEntry hoveredWaypoint;
    StructureIndex.Marker hoveredStructure;

    /** Cursor position at the last left-button press, so {@link #mouseReleased} can tell a click from a pan drag. */
    double leftPressX;
    double leftPressY;
    boolean mapPointerPress;
    /**
     * Right-button press arms a chunk-snapped region selection instead of opening the location
     * menu: dragging past {@link #CLICK_DRAG_TOLERANCE_PX} starts the rectangle from the anchor
     * block, releasing without dragging opens the menu (see {@link #mouseReleased}). The
     * committed {@link #regionSelection} stays highlighted until the next input on this screen.
     */
    private boolean regionSelectPress;
    private boolean regionSelectDragging;
    private double regionSelectPressX;
    private double regionSelectPressY;
    private int regionSelectAnchorX;
    private int regionSelectAnchorZ;
    private MapExportBounds regionSelection;
    final FullscreenMapToolPanel toolPanel = new FullscreenMapToolPanel();
    private final MapTargetSelectors selectors;
    final MapAnnotationController annotations;
    final MapLocationMenuController locationMenu;
    final MapExportSelectionController exportControls;
    final MapLoadStateOverlay loadStateOverlay;
    private final MapInfoLabels infoLabels;
    private SharedWaypointAvailability sharedAvailability;
    private MapIconButton viewGroupButton;
    private MapIconButton waypointGroupButton;
    private MapIconButton drawingGroupButton;
    private MapIconButton actionsGroupButton;
    private MapIconButton localVisibilityButton;
    private MapIconButton sharedVisibilityButton;
    private MapIconButton manageWaypointsButton;
    private MapIconButton structureSearchButton;
    private MapIconButton displayModeButton;
    private MapIconButton mapExportButton;
    private MapIconButton mapImportButton;
    ButtonWidget clientWorldButton;
    private ButtonWidget worldSelectorButton;
    private ButtonWidget dimensionSelectorButton;
    private int waypointControlsBottom;
    ServerPlayerRadarState.PlayerView hoveredRadarPlayer;
    final Map<ButtonWidget, String> annotationTooltips = new LinkedHashMap<>();
    AnnotationToolbarBounds annotationToolbarBounds;
    AnnotationToolbarBounds annotationColorMenuBounds;
    private FullscreenDisplayMode controlsDisplayMode;
    /** Exclusive with every {@link AnnotationTool}: while on, left-click places measure points instead of drawing. */

    public FullscreenMapScreen(final KeyBinding openMapKey) {
        this(openMapKey, null);
    }

    private FullscreenMapScreen(final KeyBinding openMapKey, final InitialFocus initialFocus) {
        super(Texts.translatable("confluxmap.screen.map.title"));
        this.openMapKey = openMapKey;
        this.initialFocus = initialFocus;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.gameBridge = app.gameBridge();
        this.mapWorlds = app.mapWorlds();
        this.tiles = app.tileService();
        this.textures = app.tileTextureManager();
        this.daylightModel = app.daylightModel();
        this.predictionState = app.predictionState();
        this.predictionTiles = app.predictionTileService();
        this.viewState = app.fullscreenMapViewState();
        this.layerSelector = app.layerSelector();
        this.waypointService = app.waypointService();
        this.annotationService = app.annotationService();
        this.waypointRenderCatalog = app.waypointRenderCatalog();
        this.config = app.config();
        this.sharedWaypoints = app.sharedWaypoints();
        this.companion = app.companionSession();
        this.chunkLoadStates = app.chunkLoadStateClient();
        this.radarScanner = app.radarScanner();
        this.serverPlayerRadar = app.serverPlayerRadar();
        this.radarIconManager = app.entityIconManager();
        this.radarViewRange = app.radarViewRange();
        this.playerTrail = app.playerTrail();
        this.structureMarkers = app.structureMarkerService();
        this.portals = app.portalService();
        this.updateCheck = app.updateCheck();
        this.groundTeleport = app.groundTeleportService();
        this.clientMultiworld = app.clientMultiworldService();
        this.mapBrowser = app.fullscreenMapBrowseService();
        this.uiTheme = app.uiResourceTheme();
        this.customMarkers = app.customMarkers();
        this.mapBrowser.clear();
        this.archivedWaypointRenderCatalog = new WaypointRenderCatalog(
            mapBrowser.waypoints(), List::of, config
        );
        this.waypointHighlightState = app.waypointHighlightState();

        this.selectors = new MapTargetSelectors(this);
        this.annotations = new MapAnnotationController(this, app.measureState());
        this.locationMenu = new MapLocationMenuController(this);
        this.exportControls = new MapExportSelectionController(this);
        this.loadStateOverlay = new MapLoadStateOverlay(this);
        this.infoLabels = new MapInfoLabels(this);

        final DimensionId dimension = gameBridge.session().dimension();
        final Optional<PlayerView> player = gameBridge.viewpoint();
        final FullscreenMapViewState.View initialView = viewState.viewForOpening(
            dimension,
            player.isPresent() ? player.get().x() : 0.0,
            player.isPresent() ? player.get().z() : 0.0,
            DEFAULT_SCALE
        );
        centerX = initialView.centerX();
        centerZ = initialView.centerZ();
        scale = initialView.scale();
    }

    static FullscreenMapScreen focusedOnWaypoint(
        final DimensionId dimension,
        final double x,
        final double z
    ) {
        return new FullscreenMapScreen(null, new InitialFocus(dimension, x, z));
    }

    @Override
    protected void init() {
        locationMenu.reset();
        // init() reruns on resize; announce the open event exactly once per screen life.
        if (!openAnnounced) {
            openAnnounced = true;
            final ConfluxMapApiImpl api = ConfluxMapClient.get().api();
            if (api != null) {
                api.fireFullscreenMapOpened(gameBridge.session().dimension());
            }
        }
        final InitialFocus requestedFocus = initialFocus;
        if (requestedFocus != null) {
            initialFocus = null;
            selectViewTarget(new FullscreenMapBrowseService.Target(
                gameBridge.session().world(), requestedFocus.dimension()
            ));
            centerX = requestedFocus.x();
            centerZ = requestedFocus.z();
            scale = Math.min(scale, DEFAULT_SCALE);
        }
        if (exportControls.selecting()) {
            exportControls.rebuildExportSelectionControls();
            return;
        }
        rebuildWaypointControls();
    }


    void rebuildWaypointControls() {
        clearChildren();
        sharedAvailability = viewSharedAvailability();
        localVisibilityButton = null;
        sharedVisibilityButton = null;
        manageWaypointsButton = null;
        locationMenu.clearButtons();
        structureSearchButton = null;
        annotations.clearControls();
        displayModeButton = null;
        mapExportButton = null;
        clientWorldButton = null;
        selectors.clearButtons();
        controlsDisplayMode = null;
        viewGroupButton = null;
        waypointGroupButton = null;
        drawingGroupButton = null;
        actionsGroupButton = null;

        if (displayMode() == FullscreenDisplayMode.TERRAIN) {
            selectors.addTargetSelectors();
        } else {
            selectors.closeDropdown();
        }

        final int x = width - MARGIN - CONTROL_SIZE;
        final int top = MARGIN + this.textRenderer.fontHeight + 5;
        int y = top;
        viewGroupButton = addToolGroupButton(
            x, y, VIEW_GROUP_ICON, "confluxmap.map.tools.view", FullscreenMapToolPanel.Group.VIEW
        );
        y += CONTROL_SIZE + CONTROL_GAP;
        waypointGroupButton = addToolGroupButton(
            x, y, WAYPOINT_GROUP_ICON, "confluxmap.map.tools.waypoints",
            FullscreenMapToolPanel.Group.WAYPOINTS
        );
        y += CONTROL_SIZE + CONTROL_GAP;
        drawingGroupButton = addToolGroupButton(
            x, y, ANNOTATION_DRAWING_ICON, "confluxmap.map.tools.drawing",
            FullscreenMapToolPanel.Group.DRAWING
        );
        drawingGroupButton.active = viewAnnotationStore() != null;
        y += CONTROL_SIZE + CONTROL_GAP;
        actionsGroupButton = addToolGroupButton(
            x, y, ACTIONS_GROUP_ICON, "confluxmap.map.tools.actions",
            FullscreenMapToolPanel.Group.ACTIONS
        );
        y += CONTROL_SIZE + CONTROL_GAP;
        final MapIconButton settingsButton = addDrawableChild(new MapIconButton(
            x,
            y,
            MAP_SETTINGS_ICON,
            Texts.translatable("confluxmap.screen.config.title"),
            ignored -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(), new ConfigScreen(this)
            )
        ));
        annotationTooltips.put(settingsButton, "confluxmap.screen.config.title");
        waypointControlsBottom = y + CONTROL_SIZE;
        controlsDisplayMode = displayMode();

        final FullscreenMapToolPanel.Group openGroup = toolPanel.openGroup();
        if (openGroup != null) {
            switch (openGroup) {
                case VIEW -> rebuildViewControls(top);
                case WAYPOINTS -> rebuildWaypointGroupControls(top + CONTROL_SIZE + CONTROL_GAP);
                case DRAWING -> annotations.rebuildAnnotationControls(top + (CONTROL_SIZE + CONTROL_GAP) * 2);
                case ACTIONS -> rebuildActionControls(top + (CONTROL_SIZE + CONTROL_GAP) * 3);
                default -> throw new IllegalStateException("Unhandled tool group: " + openGroup);
            }
        }
        loadStateOverlay.addLoadStateDetailControl();
        if (locationMenu.isOpen()) {
            locationMenu.addLocationMenuButtons();
        }
    }


    private MapIconButton addToolGroupButton(
        final int x,
        final int y,
        final Identifier icon,
        final String tooltipKey,
        final FullscreenMapToolPanel.Group group
    ) {
        final MapIconButton button = addDrawableChild(new MapIconButton(
            x, y, icon, Texts.translatable(tooltipKey), ignored -> toggleToolGroup(group)
        ));
        button.setSelected(toolPanel.isOpen(group));
        annotationTooltips.put(button, tooltipKey);
        return button;
    }

    private void toggleToolGroup(final FullscreenMapToolPanel.Group group) {
        final boolean leavingDrawing = toolPanel.isOpen(FullscreenMapToolPanel.Group.DRAWING);
        toolPanel.toggle(group);
        annotations.closeColorMenu();
        if (leavingDrawing && !toolPanel.isOpen(FullscreenMapToolPanel.Group.DRAWING)) {
            annotations.selectAnnotationTool(AnnotationTool.SELECT);
            return;
        }
        rebuildWaypointControls();
    }

    private int secondaryControlsX() {
        return width - MARGIN - CONTROL_SIZE * 2 - CONTROL_GAP;
    }

    private void rebuildViewControls(final int top) {
        int y = top;
        for (final FullscreenDisplayMode mode : FullscreenDisplayMode.values()) {
            final boolean available = displayModeAvailable(mode);
            final MapIconButton button = addDrawableChild(new MapIconButton(
                secondaryControlsX(),
                y,
                DisplayModeIconCatalog.icon(mode, available),
                Texts.translatable(displayModeValueKey(mode)),
                ignored -> setDisplayMode(mode)
            ));
            button.setSelected(displayMode() == mode);
            button.active = available;
            annotationTooltips.put(button, displayModeValueKey(mode));
            if (displayMode() == mode) {
                displayModeButton = button;
            }
            y += CONTROL_SIZE + CONTROL_GAP;
        }
        annotationToolbarBounds = new AnnotationToolbarBounds(
            secondaryControlsX(), top, CONTROL_SIZE, y - top - CONTROL_GAP
        );
        refreshDisplayModeButton();
    }

    private void rebuildWaypointGroupControls(final int top) {
        final int x = secondaryControlsX();
        int y = top;
        localVisibilityButton = addDrawableChild(new MapIconButton(
            x, y, LOCAL_WAYPOINT_ICON, ignored -> {
                config.localWaypointsVisible = !config.localWaypointsVisible;
                ConfluxMapClient.get().configIo().save(config);
                refreshWaypointVisibilityButtons();
            }
        ));
        y += CONTROL_SIZE + CONTROL_GAP;
        if (sharedAvailability.visible()) {
            sharedVisibilityButton = addDrawableChild(new MapIconButton(
                x, y, SHARED_WAYPOINT_ICON, ignored -> {
                    config.sharedWaypointsVisible = !config.sharedWaypointsVisible;
                    ConfluxMapClient.get().configIo().save(config);
                    refreshWaypointVisibilityButtons();
                }
            ));
            y += CONTROL_SIZE + CONTROL_GAP;
        }
        refreshWaypointVisibilityButtons();
        manageWaypointsButton = addDrawableChild(new MapIconButton(
            x, y, MANAGE_WAYPOINT_ICON, Texts.translatable(
                "confluxmap.map.waypoints.manage.tooltip"
            ), ignored -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(), new WaypointListScreen(this, WaypointListScreen.Tab.LOCAL)
            )
        ));
        manageWaypointsButton.active = viewingLiveWorld();
        annotationToolbarBounds = new AnnotationToolbarBounds(
            x, top, CONTROL_SIZE, y + CONTROL_SIZE - top
        );
    }

    /**
     * Keeps both waypoint toggles showing what is actually on the map: the slashed icon covers
     * both "you hid this set" and "the server is not sharing", which is also why the shared
     * button's own enabled state has to be reapplied here.
     */
    private void refreshWaypointVisibilityButtons() {
        if (localVisibilityButton != null) {
            localVisibilityButton.setSelected(config.localWaypointsVisible);
            localVisibilityButton.setIcon(
                config.localWaypointsVisible ? LOCAL_WAYPOINT_ICON : LOCAL_WAYPOINT_OFF_ICON
            );
        }
        if (sharedVisibilityButton != null) {
            final boolean ready = sharedAvailability != null && sharedAvailability.ready();
            sharedVisibilityButton.setSelected(config.sharedWaypointsVisible);
            sharedVisibilityButton.setIcon(
                config.sharedWaypointsVisible && ready
                    ? SHARED_WAYPOINT_ICON
                    : SHARED_WAYPOINT_OFF_ICON
            );
            sharedVisibilityButton.active = ready;
        }
    }

    private void rebuildActionControls(final int top) {
        final int x = secondaryControlsX();
        int y = top;
        structureSearchButton = addDrawableChild(new MapIconButton(
            x, y, STRUCTURE_SEARCH_ICON, Texts.translatable("confluxmap.map.search"),
            ignored -> openStructureSearch()
        ));
        refreshStructureSearchButton();
        y += CONTROL_SIZE + CONTROL_GAP;
        mapExportButton = addDrawableChild(new MapIconButton(
            x, y, MAP_EXPORT_ICON, Texts.translatable("confluxmap.screen.map_export.tooltip"),
            ignored -> exportControls.openMapExport()
        ));
        mapExportButton.active = !mapBrowser.browsing();
        annotationTooltips.put(mapExportButton, "confluxmap.screen.map_export.tooltip");
        y += CONTROL_SIZE + CONTROL_GAP;
        mapImportButton = addDrawableChild(new MapIconButton(
            x, y, MAP_IMPORT_ICON, Texts.translatable("confluxmap.screen.map_import.tooltip"),
            ignored -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(), new XaeroImportScreen(this)
            )
        ));
        annotationTooltips.put(mapImportButton, "confluxmap.screen.map_import.tooltip");
        y += CONTROL_SIZE + CONTROL_GAP;
        if (clientMultiworld.canManageProfiles()) {
            clientWorldButton = addDrawableChild(new MapIconButton(
                x, y, WORLD_PROFILE_ICON, Texts.translatable(
                    "confluxmap.map.client_world",
                    clientMultiworld.currentProfile().map(ClientWorldProfile::displayName).orElse("?")
                ),
                ignored -> MinecraftAccess.setScreen(
                    MinecraftClient.getInstance(), new ClientWorldSelectScreen(this, openMapKey, false)
                )
            ));
            annotationTooltips.put(clientWorldButton, "confluxmap.map.client_world");
            y += CONTROL_SIZE + CONTROL_GAP;
        }
        annotationToolbarBounds = new AnnotationToolbarBounds(
            x, top, CONTROL_SIZE, y - top - CONTROL_GAP
        );
    }



    private void setDisplayMode(final FullscreenDisplayMode mode) {
        if (!displayModeAvailable(mode)) {
            return;
        }
        final FullscreenDisplayMode current = displayMode();
        config.fullscreenDisplayMode = mode;
        if (current == FullscreenDisplayMode.CHUNK_LOAD_STATE
            && mode != FullscreenDisplayMode.CHUNK_LOAD_STATE) {
            chunkLoadStates.deactivate();
        }
        ConfluxMapClient.get().configIo().save(config);
        rebuildWaypointControls();
    }

    private boolean displayModeAvailable(final FullscreenDisplayMode mode) {
        return switch (mode) {
            case TERRAIN -> true;
            case CHUNK_LOAD_STATE -> viewingLiveSession() && chunkLoadStates.available();
            case BIOME -> companion.biomeMapAllowed();
        };
    }

    private static String displayModeValueKey(final FullscreenDisplayMode mode) {
        return switch (mode) {
            case TERRAIN -> "confluxmap.map.display_mode.terrain";
            case CHUNK_LOAD_STATE -> "confluxmap.map.display_mode.chunk_load_state";
            case BIOME -> "confluxmap.map.display_mode.biome";
        };
    }



    @Override
    public void tick() {
        super.tick();
        if (!chunkLoadStates.available() && chunkLoadStates.snapshot().active()) {
            chunkLoadStates.reset();
        }
        if (controlsDisplayMode != displayMode()) {
            rebuildWaypointControls();
            return;
        }
        if (loadStateMode() != loadStateOverlay.hasDetailButton()) {
            rebuildWaypointControls();
            return;
        }
        refreshDisplayModeButton();
        final SharedWaypointAvailability availability = viewSharedAvailability();
        if (sharedAvailability == null || availability.visible() != sharedAvailability.visible()) {
            rebuildWaypointControls();
            return;
        }
        sharedAvailability = availability;
        refreshWaypointVisibilityButtons();
        if (locationMenu.refreshGroundTarget()) {
            rebuildWaypointControls();
        }
        refreshStructureSearchButton();
    }

    private Text visibilityTooltip(final boolean local) {
        final boolean visible = local ? config.localWaypointsVisible : config.sharedWaypointsVisible;
        return Texts.translatable(
            local
                ? "confluxmap.map.waypoints.local.tooltip"
                : "confluxmap.map.waypoints.shared.tooltip",
            Texts.translatable(visible ? "confluxmap.value.on" : "confluxmap.value.off").getString()
        );
    }

    private SharedWaypointAvailability viewSharedAvailability() {
        return viewingLiveWorld()
            ? sharedWaypoints.availability()
            : new SharedWaypointAvailability(false, false, false);
    }

    /** Keep the world (and this session's capture pipeline) running while the map is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or M below). */
    @Override
    public void onClose() {
        viewState.rememberScale(viewSession().dimension(), scale);
        chunkLoadStates.deactivate();
        tiles.clearViewport();
        mapBrowser.tiles().clearViewport();
        predictionTiles.clearViewport();
        ConfluxMapClient.get().mapSyncClient().clearViewport();
        structureMarkers.flush();
        mapBrowser.clear();
        super.onClose();
    }

    @Override
    public void removed() {
        chunkLoadStates.deactivate();
        ConfluxMapClient.get().mapSyncClient().clearViewport();
        embeddedBlur.close();
        if (openAnnounced) {
            openAnnounced = false;
            final ConfluxMapApiImpl api = ConfluxMapClient.get().api();
            if (api != null) {
                api.fireFullscreenMapClosed(gameBridge.session().dimension());
            }
        }
        super.removed();
    }

    @Override
    //#if MC>=12109
    //$$ public boolean keyPressed(final KeyInput input) {
    //$$     final int keyCode = input.key();
    //$$     final int modifiers = input.modifiers();
    //#else
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
    //#endif
        regionSelection = null;
        if (regionSelectDragging && keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE) {
            regionSelectDragging = false;
            regionSelectPress = false;
            return true;
        }
        if (exportControls.selecting()) {
            //#if MC>=12109
            //$$ if (keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE || openMapKey != null && openMapKey.matchesKey(input)) {
            //#else
            if (keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE
                || openMapKey != null && openMapKey.matchesKey(keyCode, scanCode)) {
            //#endif
                exportControls.cancelExportSelection();
                return true;
            }
        }
        final boolean controlDown = (modifiers & (cn.net.rms.confluxmap.compat.Keys.MOD_CONTROL | cn.net.rms.confluxmap.compat.Keys.MOD_SUPER)) != 0;
        final boolean shiftDown = (modifiers & cn.net.rms.confluxmap.compat.Keys.MOD_SHIFT) != 0;
        if (controlDown && keyCode == cn.net.rms.confluxmap.compat.Keys.Z && !shiftDown && annotations.isMeasureMode()) {
            annotations.undoMeasurePoint();
            return true;
        }
        if (controlDown && keyCode == cn.net.rms.confluxmap.compat.Keys.Z) {
            if (shiftDown) {
                annotations.redoAnnotationChange();
            } else {
                annotations.undoAnnotationChange();
            }
            return true;
        }
        if (controlDown && keyCode == cn.net.rms.confluxmap.compat.Keys.Y) {
            annotations.redoAnnotationChange();
            return true;
        }
        if (keyCode == cn.net.rms.confluxmap.compat.Keys.F9) {
            ConfluxMapClient.get().reloadPredictionTiles();
            return true;
        }
        if (keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE && selectors.dropdownOpen()) {
            selectors.closeDropdown();
            return true;
        }
        if (keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE && annotations.isColorMenuOpen()) {
            annotations.closeColorMenu();
            rebuildWaypointControls();
            return true;
        }
        if (keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE && locationMenu.isOpen()) {
            locationMenu.dismissLocationMenu();
            return true;
        }
        if (keyCode == cn.net.rms.confluxmap.compat.Keys.ESCAPE && toolPanel.openGroup() != null) {
            final boolean closingDrawing = toolPanel.isOpen(FullscreenMapToolPanel.Group.DRAWING);
            toolPanel.close();
            if (closingDrawing) {
                annotations.selectAnnotationTool(AnnotationTool.SELECT);
            } else {
                rebuildWaypointControls();
            }
            return true;
        }
        //#if MC>=12109
        //$$ if (openMapKey != null && openMapKey.matchesKey(input)) {
        //#else
        if (openMapKey != null && openMapKey.matchesKey(keyCode, scanCode)) {
        //#endif
            onClose();
            return true;
        }
        //#if MC>=12109
        //$$ return super.keyPressed(input);
        //#else
        return super.keyPressed(keyCode, scanCode, modifiers);
        //#endif
    }

    /**
     * Right-click opens a location-action menu for the clicked block position.
     * Left-click on a hovered marker opens the source-appropriate management flow -
     * see {@link #mouseReleased}, which fires once the click (as opposed to a pan
     * drag) completes.
     */
    @Override
    //#if MC>=12109
    //$$ public boolean mouseClicked(final Click click, final boolean doubledClick) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
    //#endif
        regionSelection = null;
        if (button == MouseButtons.LEFT && selectors.selectTargetDropdownOption(mouseX, mouseY)) {
            mapPointerPress = false;
            return true;
        }
        if (exportControls.selecting()) {
            //#if MC>=12109
            //$$ if (super.mouseClicked(click, doubledClick)) {
            //#else
            if (super.mouseClicked(mouseX, mouseY, button)) {
            //#endif
                return true;
            }
            if (button == MouseButtons.RIGHT) {
                exportControls.cancelExportSelection();
                return true;
            }
            if (button == MouseButtons.LEFT) {
                if (exportControls.selectionComplete()) {
                    return true;
                }
                leftPressX = mouseX;
                leftPressY = mouseY;
                mapPointerPress = true;
                return true;
            }
            return false;
        }
        if (locationMenu.isOpen()) {
            if (locationMenu.hoverContains(mouseX, mouseY)) {
                //#if MC>=12109
                //$$ super.mouseClicked(click, doubledClick);
                //#else
                super.mouseClicked(mouseX, mouseY, button);
                //#endif
                mapPointerPress = false;
                locationMenu.performPendingLocationAction();
                return true;
            }
            if (button == MouseButtons.RIGHT && !isOverMapControls(mouseX, mouseY)) {
                locationMenu.openLocationMenu(mouseX, mouseY);
                return true;
            }
            locationMenu.dismissLocationMenu();
            mapPointerPress = false;
            return true;
        }
        //#if MC>=12109
        //$$ if (super.mouseClicked(click, doubledClick)) {
        //#else
        if (super.mouseClicked(mouseX, mouseY, button)) {
        //#endif
            mapPointerPress = false;
            return true;
        }
        if (selectors.dropdownOpen()) {
            selectors.closeDropdown();
            mapPointerPress = false;
            return true;
        }
        if (annotations.isColorMenuOpen()) {
            annotations.closeColorMenu();
            rebuildWaypointControls();
            mapPointerPress = false;
            return true;
        }
        if (isOverMapControls(mouseX, mouseY)) {
            mapPointerPress = false;
            return true;
        }
        if (annotations.isMeasureMode()) {
            if (button == MouseButtons.RIGHT) {
                annotations.undoMeasurePoint();
                return true;
            }
            if (button == MouseButtons.LEFT) {
                // Press only arms pan; the point itself is placed on release (see mouseReleased)
                // so dragging still pans and a sub-tolerance release still measures.
                leftPressX = mouseX;
                leftPressY = mouseY;
                mapPointerPress = true;
                return true;
            }
            return false;
        }
        if (button == MouseButtons.LEFT && annotations.beginAnnotationPointer(mouseX, mouseY)) {
            return true;
        }
        if (button == MouseButtons.RIGHT) {
            // Press only arms; release without dragging opens the menu, dragging selects a
            // region (see mouseDragged/mouseReleased).
            regionSelectPress = true;
            regionSelectPressX = mouseX;
            regionSelectPressY = mouseY;
            return true;
        }
        if (button == MouseButtons.LEFT) {
            leftPressX = mouseX;
            leftPressY = mouseY;
            mapPointerPress = true;
            return true;
        }
        return false;
    }



    /**
     * Captures the right-click menu target for a split-map screen embedding this map.
     * Hover state comes from the last {@link #renderEmbedded} pass, and the panel is placed
     * within the map pane rather than the full screen.
     */
    FullscreenMapLocationMenu.Capture openEmbeddedLocationMenu(
        final double mouseX,
        final double mouseY,
        final SplitMapLayout layout
    ) {
        return locationMenu.captureLocationMenu(
            mouseX, mouseY, layout.mapCenterX(), layout.mapCenterY(),
            layout.mapWidth(), layout.screenHeight()
        );
    }


    /**
     * Ground answer for one map column: a captured surface (or captured void), else the
     * predicted surface (or predicted void), else unknown - in which case the prediction tile
     * holding this column is queued ahead of the viewport order, because a map action may be
     * waiting on it.
     */
    ColumnStore.SurfaceLookup groundAt(final int blockX, final int blockZ) {
        final ColumnStore.SurfaceLookup captured = capturedGroundAt(blockX, blockZ);
        if (captured.known()) {
            return captured;
        }
        final MapLayer surfaceLayer = teleportSurfaceLayer(viewSession().dimension());
        final SessionGuard.Session session = viewSession();
        return surfaceLayer.equals(viewLayer()) && predictionActive(surfaceLayer, session, false)
            ? predictionTiles.predictedSurfaceAt(session.dimension(), currentLod(), blockX, blockZ)
            : ColumnStore.SurfaceLookup.UNKNOWN;
    }

    /** The layer whose column heights describe where a teleport should land. */
    private static MapLayer teleportSurfaceLayer(final DimensionId dimension) {
        return dimension.equals(DimensionId.NETHER)
            ? MapLayer.NETHER_CEILING
            : dimension.equals(DimensionId.END) ? MapLayer.END_SURFACE : MapLayer.SURFACE;
    }

    /** Captured-only ground for teleport decisions: a real column, a real void, or unknown. */
    ColumnStore.SurfaceLookup capturedGroundAt(final int blockX, final int blockZ) {
        final MapWorld mapWorld = viewMapWorlds().current();
        if (mapWorld == null) {
            return ColumnStore.SurfaceLookup.UNKNOWN;
        }
        return mapWorld
            .store(teleportSurfaceLayer(viewSession().dimension()))
            .surfaceAt(blockX, blockZ);
    }


    /**
     * Offers the open menu's digit shortcuts to the shared key funnel in
     * {@link ConfluxScreen}. Runs the same pending-action path as a click, so the delete
     * confirmation two-step and the post-dispatch rebuild behavior stay identical.
     */
    @Override
    protected boolean embeddedMenuHotkeyPressed(final int keyCode) {
        return locationMenu.hotkeyPressed(keyCode);
    }


    /**
     * Completes the left-click-a-marker-to-edit gesture: if the cursor is still on
     * {@link #hoveredWaypoint} and travelled less than {@link #CLICK_DRAG_TOLERANCE_PX}
     * since {@link #mouseClicked}'s press, this was a click rather than a
     * {@link #mouseDragged} pan, so open the same edit flow {@link WaypointListScreen}
     * uses, returning to this screen on save/cancel.
     */
    @Override
    //#if MC>=12109
    //$$ public boolean mouseReleased(final Click click) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
    //#endif
        if (button == MouseButtons.RIGHT && regionSelectPress) {
            regionSelectPress = false;
            if (regionSelectDragging) {
                regionSelectDragging = false;
                final int cursorX = (int) Math.floor(centerX + (mouseX - width / 2.0) * scale);
                final int cursorZ = (int) Math.floor(centerZ + (mouseY - height / 2.0) * scale);
                regionSelection = ChunkSelectionMath.betweenBlocks(
                    regionSelectAnchorX, regionSelectAnchorZ, cursorX, cursorZ
                );
            } else if (!isOverMapControls(mouseX, mouseY)) {
                locationMenu.openLocationMenu(mouseX, mouseY);
            }
            return true;
        }
        if (exportControls.selecting() && button == MouseButtons.LEFT && mapPointerPress) {
            mapPointerPress = false;
            if (Math.hypot(mouseX - leftPressX, mouseY - leftPressY) < CLICK_DRAG_TOLERANCE_PX) {
                exportControls.selectExportCorner(mouseX, mouseY);
            }
            return true;
        }
        if (button == MouseButtons.LEFT && annotations.isPointerDown()) {
            annotations.commitAnnotationPointer(mouseX, mouseY);
            return true;
        }
        if (button != MouseButtons.LEFT || !mapPointerPress) {
            //#if MC>=12109
            //$$ return super.mouseReleased(click);
            //#else
            return super.mouseReleased(mouseX, mouseY, button);
            //#endif
        }
        mapPointerPress = false;
        if (annotations.placeMeasurePoint(mouseX, mouseY)) {
            return true;
        }
        if (hoveredWaypoint != null
            && !isOverMapControls(mouseX, mouseY)
            && Math.hypot(mouseX - leftPressX, mouseY - leftPressY) < CLICK_DRAG_TOLERANCE_PX) {
            openWaypoint(hoveredWaypoint);
            return true;
        }
        return true;
    }


    @Override
    //#if MC>=12109
    //$$ public boolean mouseDragged(final Click click, final double deltaX, final double deltaY) {
    //$$     final double mouseX = click.x();
    //$$     final double mouseY = click.y();
    //$$     final int button = click.button();
    //#else
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double deltaX, final double deltaY) {
    //#endif
        if (button == MouseButtons.LEFT
            && annotations.isPointerDown()
            && annotations.dragPointer(mouseX, mouseY, deltaX, deltaY)) {
            return true;
        }
        if (button == MouseButtons.RIGHT && regionSelectPress) {
            if (!regionSelectDragging
                && Math.hypot(mouseX - regionSelectPressX, mouseY - regionSelectPressY) >= CLICK_DRAG_TOLERANCE_PX) {
                regionSelectDragging = true;
                regionSelectAnchorX = (int) Math.floor(centerX + (regionSelectPressX - width / 2.0) * scale);
                regionSelectAnchorZ = (int) Math.floor(centerZ + (regionSelectPressY - height / 2.0) * scale);
            }
            return true;
        }
        if (button == MouseButtons.LEFT && mapPointerPress) {
            // Opposite the drag direction, 1:1 in world-space at the current scale (§4 pan mechanics).
            centerX -= deltaX * scale;
            centerZ -= deltaY * scale;
            return true;
        }
        //#if MC>=12109
        //$$ return super.mouseDragged(click, deltaX, deltaY);
        //#else
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
        //#endif
    }

    boolean isOverMapControls(final double mouseX, final double mouseY) {
        final int left = width - MARGIN - CONTROL_SIZE;
        final int top = MARGIN + this.textRenderer.fontHeight + 5;
        final boolean waypointControls = mouseX >= left && mouseX <= width - MARGIN
            && mouseY >= top && mouseY <= waypointControlsBottom;
        return waypointControls
            || annotationToolbarBounds != null && annotationToolbarBounds.contains(mouseX, mouseY)
            || annotationColorMenuBounds != null && annotationColorMenuBounds.contains(mouseX, mouseY);
    }

    private void openWaypoint(final WaypointRenderEntry waypoint) {
        if (waypoint.shared()) {
            if (!sharedWaypoints.availability().enabled()) {
                return;
            }
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new WaypointListScreen(
                this, WaypointListScreen.Tab.PUBLIC
            ));
            return;
        }
        for (final Waypoint local : viewWaypointService().list()) {
            if (local.id.equals(waypoint.id())) {
                MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointEditScreen.forEdit(
                    this, local, this::viewWaypointStore
                ));
                return;
            }
        }
    }

    @Override
    //#if MC>=12002
    //$$ public boolean mouseScrolled(
    //$$     final double mouseX,
    //$$     final double mouseY,
    //$$     final double horizontalAmount,
    //$$     final double amount
    //$$ ) {
    //#else
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
    //#endif
        if (!regionSelectDragging) {
            regionSelection = null;
        }
        if (selectors.wheelScrolled(mouseX, mouseY, amount)) {
            return true;
        }
        if (locationMenu.isOpen()) {
            locationMenu.dismissLocationMenu();
            return true;
        }
        if (amount == 0) {
            return false;
        }
        return zoomAt(mouseX, mouseY, width / 2.0, height / 2.0, amount);
    }

    private boolean zoomAt(
        final double mouseX,
        final double mouseY,
        final double viewportCenterX,
        final double viewportCenterY,
        final double amount
    ) {
        final double oldScale = scale;
        final double newScale = MathHelper.clamp(oldScale * (amount > 0 ? 1.0 / ZOOM_STEP : ZOOM_STEP), MIN_SCALE, MAX_SCALE);
        if (newScale != oldScale) {
            // Cursor-anchored: keep the world point under the cursor fixed on screen.
            final double cursorWorldX = centerX + (mouseX - viewportCenterX) * oldScale;
            final double cursorWorldZ = centerZ + (mouseY - viewportCenterY) * oldScale;
            centerX = cursorWorldX - (mouseX - viewportCenterX) * newScale;
            centerZ = cursorWorldZ - (mouseY - viewportCenterY) * newScale;
            scale = newScale;
        }
        return true;
    }

    void renderEmbedded(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta,
        final SplitMapLayout layout
    ) {
        final int previousWidth = width;
        final int previousHeight = height;
        final double previousCenterX = centerX;
        final SplitMapLayout previousEmbeddedLayout = embeddedLayout;
        width = layout.mapRenderWidth();
        height = layout.mapHeight();
        centerX = layout.renderCenterX(centerX, scale);
        embeddedLayout = layout;
        final boolean pointerInside = layout.containsMap(mouseX, mouseY);
        final int mapMouseX = pointerInside ? mouseX : (int) layout.mapCenterX();
        final int mapMouseY = pointerInside ? mouseY : (int) layout.mapCenterY();
        try {
            renderContents(draw, mapMouseX, mapMouseY, tickDelta);
        } finally {
            embeddedLayout = previousEmbeddedLayout;
            centerX = previousCenterX;
            width = previousWidth;
            height = previousHeight;
        }
    }

    boolean renderEmbeddedPanelBlur(final GuiDraw draw, final SplitMapLayout layout) {
        return embeddedBlur.render(
            draw, this.client, layout, matrices -> renderEmbeddedBackdrop(matrices, layout)
        );
    }

    private void renderEmbeddedBackdrop(
        final MatrixStack matrices,
        final SplitMapLayout layout
    ) {
        final int previousWidth = width;
        final int previousHeight = height;
        final double previousCenterX = centerX;
        width = layout.mapRenderWidth();
        height = layout.mapHeight();
        centerX = layout.renderCenterX(centerX, scale);
        try {
            RenderUtil.fillRect(matrices, 0, 0, width, height, BACKGROUND_COLOR);
            drawGrid(matrices);
            drawTiles(matrices, false);
        } finally {
            centerX = previousCenterX;
            width = previousWidth;
            height = previousHeight;
        }
    }

    void panEmbedded(final double deltaX, final double deltaY) {
        centerX -= deltaX * scale;
        centerZ -= deltaY * scale;
    }

    boolean zoomEmbedded(
        final double mouseX,
        final double mouseY,
        final double amount,
        final SplitMapLayout layout
    ) {
        return zoomAt(mouseX, mouseY, layout.mapCenterX(), layout.mapCenterY(), amount);
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        final MatrixStack matrices = draw.matrices();
        final TileService visibleTiles = viewTiles();
        if (mapBrowser.browsing()) {
            mapBrowser.textures().beginFrame();
        }
        visibleTiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));
        predictionTiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));
        // This screen owns radarViewRange while it's open (MinimapHudRenderer stops writing it -
        // see its render() javadoc).
        final Optional<PlayerView> radarObserver = viewingLiveSession()
            ? gameBridge.viewpoint(tickDelta)
            : Optional.empty();
        if (radarObserver.isPresent()) {
            final PlayerView observer = radarObserver.get();
            radarViewRange.setForAxisAlignedViewport(
                observer.x(), observer.z(), centerX, centerZ, width, height, scale
            );
        } else {
            radarViewRange.set(0);
        }

        RenderUtil.fillRect(matrices, 0, 0, width, height, BACKGROUND_COLOR);
        drawGrid(matrices);
        drawTiles(matrices);
        if (loadStateMode()) {
            loadStateOverlay.drawChunkLoadStateOverlay(draw);
        }
        drawChunkGrid(matrices, mouseX, mouseY);
        drawPlayerTrail(matrices);
        annotations.drawAnnotations(draw, mouseX, mouseY);
        drawStructures(draw, mouseX, mouseY);
        drawPortals(draw, mouseX, mouseY);
        drawRadar(draw, tickDelta, mouseX, mouseY);

        drawWaypoints(draw, mouseX, mouseY, radarObserver);
        drawCustomMarkers(draw);
        drawCameraMarker(matrices, radarObserver);
        drawPlayerMarker(matrices, tickDelta);
        drawRegionSelection(draw, mouseX, mouseY);
        annotations.drawMeasureOverlay(draw, mouseX, mouseY);
        exportControls.drawExportSelection(draw, mouseX, mouseY);
        infoLabels.drawDimensionLabel(draw);
        infoLabels.drawLayerLabel(draw);
        infoLabels.drawPredictionLabel(draw);
        infoLabels.drawServerSyncLabel(draw);
        if (loadStateMode()) {
            loadStateOverlay.drawChunkLoadStateLegend(draw);
        }
        infoLabels.drawScaleLabel(draw);
        infoLabels.drawCursorCoords(draw, mouseX, mouseY);
        infoLabels.drawUpdateBadge(draw);
        locationMenu.drawLocationMenu(draw);
    }


    /**
     * Right-drag region selection: a chunk-snapped rectangle with the covered chunk and block
     * sizes labeled at its center. The rectangle stays highlighted after release until the next
     * input anywhere on this screen clears {@link #regionSelection}.
     */
    private void drawRegionSelection(final GuiDraw draw, final int mouseX, final int mouseY) {
        final MapExportBounds bounds;
        if (regionSelectDragging) {
            final int cursorX = (int) Math.floor(centerX + (mouseX - width / 2.0) * scale);
            final int cursorZ = (int) Math.floor(centerZ + (mouseY - height / 2.0) * scale);
            bounds = ChunkSelectionMath.betweenBlocks(
                regionSelectAnchorX, regionSelectAnchorZ, cursorX, cursorZ
            );
        } else if (regionSelection != null) {
            bounds = regionSelection;
        } else {
            return;
        }
        final float left = (float) (width / 2.0 + (bounds.minX() - centerX) / scale);
        final float top = (float) (height / 2.0 + (bounds.minZ() - centerZ) / scale);
        final float right = (float) (width / 2.0 + ((long) bounds.maxX() + 1L - centerX) / scale);
        final float bottom = (float) (height / 2.0 + ((long) bounds.maxZ() + 1L - centerZ) / scale);
        final MatrixStack matrices = draw.matrices();
        RenderUtil.fillRect(matrices, left, top, right - left, bottom - top, REGION_SELECT_FILL);
        RenderUtil.fillRect(matrices, left, top, right - left, 1f, REGION_SELECT_BORDER);
        RenderUtil.fillRect(matrices, left, bottom - 1f, right - left, 1f, REGION_SELECT_BORDER);
        RenderUtil.fillRect(matrices, left, top, 1f, bottom - top, REGION_SELECT_BORDER);
        RenderUtil.fillRect(matrices, right - 1f, top, 1f, bottom - top, REGION_SELECT_BORDER);
        drawRegionSelectionLabel(draw, (left + right) / 2.0, (top + bottom) / 2.0, bounds);
    }

    private void drawRegionSelectionLabel(
        final GuiDraw draw,
        final double x,
        final double y,
        final MapExportBounds bounds
    ) {
        final String chunks = Texts.translatable(
            "confluxmap.map.selection.chunks",
            String.valueOf(ChunkSelectionMath.chunksAcrossX(bounds)),
            String.valueOf(ChunkSelectionMath.chunksAcrossZ(bounds))
        ).getString();
        final String blocks = Texts.translatable(
            "confluxmap.map.selection.blocks",
            String.valueOf(bounds.blockWidth()),
            String.valueOf(bounds.blockHeight())
        ).getString();
        final int chunksWidth = this.textRenderer.getWidth(chunks);
        final int blocksWidth = this.textRenderer.getWidth(blocks);
        final int textWidth = Math.max(chunksWidth, blocksWidth);
        final int fontHeight = this.textRenderer.fontHeight;
        final int plateWidth = textWidth + REGION_SELECT_LABEL_PADDING * 2;
        final int plateHeight = fontHeight * 2 + REGION_SELECT_LABEL_LINE_GAP + REGION_SELECT_LABEL_PADDING * 2;
        final float plateLeft = (float) (x - plateWidth / 2.0);
        final float plateTop = (float) (y - plateHeight / 2.0);
        if (plateLeft < 0.0f || plateTop < 0.0f
            || plateLeft + plateWidth > width || plateTop + plateHeight > height) {
            return;
        }
        if (mapOverlayIntersectsUi(
            plateLeft, plateTop, plateLeft + plateWidth, plateTop + plateHeight
        )) {
            return;
        }
        draw.fill(
            (int) plateLeft,
            (int) plateTop,
            (int) plateLeft + plateWidth,
            (int) plateTop + plateHeight,
            REGION_SELECT_LABEL_BACKGROUND
        );
        draw.drawTextWithShadow(
            this.textRenderer,
            chunks,
            plateLeft + REGION_SELECT_LABEL_PADDING + (textWidth - chunksWidth) / 2.0f,
            plateTop + REGION_SELECT_LABEL_PADDING,
            TEXT_COLOR
        );
        draw.drawTextWithShadow(
            this.textRenderer,
            blocks,
            plateLeft + REGION_SELECT_LABEL_PADDING + (textWidth - blocksWidth) / 2.0f,
            plateTop + REGION_SELECT_LABEL_PADDING + fontHeight + REGION_SELECT_LABEL_LINE_GAP,
            TEXT_COLOR
        );
    }

    private void drawPlayerTrail(final MatrixStack matrices) {
        if (!config.playerTrailEnabled || !viewingLiveSession()) {
            return;
        }
        PlayerTrailRenderer.draw(
            matrices,
            playerTrail,
            new PlayerTrailProjection(
                centerX, centerZ, width / 2.0, height / 2.0, scale, 0.0, width, height
            ),
            config.playerTrailDurationSeconds,
            config.playerTrailDotSize
        );
    }


    FullscreenDisplayMode displayMode() {
        if (config.fullscreenDisplayMode == FullscreenDisplayMode.CHUNK_LOAD_STATE
            && (!viewingLiveSession() || !chunkLoadStates.available())) {
            return FullscreenDisplayMode.TERRAIN;
        }
        if (config.fullscreenDisplayMode == FullscreenDisplayMode.BIOME
            && !companion.biomeMapAllowed()) {
            return FullscreenDisplayMode.TERRAIN;
        }
        return config.fullscreenDisplayMode;
    }

    boolean loadStateMode() {
        return displayMode() == FullscreenDisplayMode.CHUNK_LOAD_STATE;
    }

    boolean biomeMode() {
        return displayMode() == FullscreenDisplayMode.BIOME;
    }

    private Text displayModeTooltip() {
        final String valueKey = switch (displayMode()) {
            case TERRAIN -> "confluxmap.map.display_mode.terrain";
            case CHUNK_LOAD_STATE -> "confluxmap.map.display_mode.chunk_load_state";
            case BIOME -> "confluxmap.map.display_mode.biome";
        };
        final Text currentMode = Texts.translatable(
            "confluxmap.map.display_mode",
            Texts.translatable(valueKey).getString()
        );
        return companion.biomeMapAllowed()
            ? currentMode
            : Texts.translatable(
                "confluxmap.map.display_mode.biome_disabled_by_server",
                currentMode.getString()
            );
    }

    private void refreshDisplayModeButton() {
        if (displayModeButton == null) {
            return;
        }
        displayModeButton.setMessage(displayModeTooltip());
        displayModeButton.active = true;
    }





    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        locationMenu.drawHotkeyHints(draw);
        selectors.renderTargetDropdown(draw, mouseX, mouseY);
        final Text annotationTooltip = annotations.hoveredAnnotationTooltip();
        final Text locationActionTooltip = locationMenu.hoveredLocationActionTooltip();
        final Text tooltip;
        if (displayModeButton != null && displayModeButton.isHovered()) {
            tooltip = displayModeTooltip();
        } else if (localVisibilityButton != null && localVisibilityButton.isHovered()) {
            tooltip = visibilityTooltip(true);
        } else if (sharedVisibilityButton != null && sharedVisibilityButton.isHovered()) {
            tooltip = sharedAvailability.disabledByServer()
                ? Texts.translatable("confluxmap.shared_waypoints.disabled_by_server")
                : sharedVisibilityButton.active
                    ? visibilityTooltip(false)
                    : Texts.translatable("confluxmap.map.waypoints.shared.unavailable");
        } else if (manageWaypointsButton != null && manageWaypointsButton.isHovered()) {
            tooltip = Texts.translatable("confluxmap.map.waypoints.manage.tooltip");
        } else if (locationMenu.locationActionHeightUnavailable()) {
            tooltip = Texts.translatable("confluxmap.map.location_menu.height_unavailable");
        } else if (locationMenu.teleportUnavailableTooltip() != null) {
            tooltip = locationMenu.teleportUnavailableTooltip();
        } else if (locationActionTooltip != null) {
            tooltip = locationActionTooltip;
        } else if (structureSearchButton != null && structureSearchButton.isHovered()) {
            tooltip = Texts.translatable(
                structureSearchButton.active
                    ? "confluxmap.map.search.tooltip"
                    : "confluxmap.map.search.unavailable"
            );
        } else if (annotationTooltip != null) {
            tooltip = annotationTooltip;
        } else if (hoveredStructure != null) {
            tooltip = Texts.translatable(
                hoveredStructure.state() == StructureIndex.State.VERIFIED
                    ? "confluxmap.map.structure.verified_tooltip"
                    : "confluxmap.map.structure.candidate_tooltip",
                Texts.translatable(hoveredStructure.translationKey()).getString(),
                hoveredStructure.blockX(),
                hoveredStructure.blockZ()
            );
        } else {
            return;
        }
        draw.drawTooltip(this, this.textRenderer, tooltip, mouseX, mouseY);
    }




    record AnnotationToolbarBounds(int x, int y, int width, int height) {
        boolean contains(final double mouseX, final double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    final class MapIconButton extends ButtonWidget {
        private Identifier icon;
        private boolean selected;

        MapIconButton(
            final int x,
            final int y,
            final Identifier icon,
            final ButtonWidget.PressAction onPress
        ) {
            this(x, y, CONTROL_SIZE, icon, Texts.literal(""), onPress);
        }

        MapIconButton(
            final int x,
            final int y,
            final Identifier icon,
            final net.minecraft.text.Text message,
            final ButtonWidget.PressAction onPress
        ) {
            this(x, y, CONTROL_SIZE, icon, message, onPress);
        }

        MapIconButton(
            final int x,
            final int y,
            final int size,
            final Identifier icon,
            final net.minecraft.text.Text message,
            final ButtonWidget.PressAction onPress
        ) {
            //#if MC>=12111
            //$$ super(
            //$$     x, y, size, size, message,
            //$$     onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER
            //$$ );
            //#elseif MC>=11904
            //$$ super(x, y, size, size, message, onPress, ButtonWidget.DEFAULT_NARRATION_SUPPLIER);
            //#else
            super(x, y, size, size, message, onPress);
            //#endif
            this.icon = icon;
        }

        void setSelected(final boolean selected) {
            this.selected = selected;
        }

        void setIcon(final Identifier icon) {
            this.icon = icon;
        }

        /**
         * Draws the vanilla button background with a four-quadrant slice instead of calling
         * {@code super.renderButton}. The vanilla two-slice draw hardcodes the 20px-tall
         * widgets.png strip, so the 22px waypoint variant samples 2px into the next state's strip
         * and its bottom border lands 2px above the real button bounds. The annotation variant
         * reuses this path at the vanilla 20px size so both icon-button groups stay consistent.
         */
        @Override
        //#if MC>=260100
        //$$ protected void extractContents(
        //$$     final GuiGraphicsExtractor context,
        //$$     final int mouseX,
        //$$     final int mouseY,
        //$$     final float delta
        //$$ ) {
        //$$     extractDefaultSprite(context);
        //$$     drawContents(GuiDraw.of(context), Widgets.x(this), Widgets.y(this));
        //$$ }
        //#elseif MC>=12111
        //$$ protected void drawIcon(
        //$$     final DrawContext context,
        //$$     final int mouseX,
        //$$     final int mouseY,
        //$$     final float delta
        //$$ ) {
        //$$     drawButton(context);
        //$$     drawContents(GuiDraw.of(context), Widgets.x(this), Widgets.y(this));
        //$$ }
        //#elseif MC>=12108
        //$$ protected void renderWidget(
        //$$     final DrawContext context,
        //$$     final int mouseX,
        //$$     final int mouseY,
        //$$     final float delta
        //$$ ) {
        //$$     final int x = Widgets.x(this);
        //$$     final int y = Widgets.y(this);
        //$$     final Identifier background = !active
        //$$         ? Ids.of("widget/button_disabled")
        //$$         : isHovered() ? Ids.of("widget/button_highlighted") : Ids.of("widget/button");
        //$$     context.drawGuiTexture(
        //$$         RenderPipelines.GUI_TEXTURED,
        //$$         background,
        //$$         x,
        //$$         y,
        //$$         getWidth(),
        //$$         getHeight(),
        //$$         ((int) (alpha * 255.0f) << 24) | 0x00FFFFFF
        //$$     );
        //$$     drawContents(GuiDraw.of(context), x, y);
        //$$ }
        //#elseif MC>=12103
        //$$ protected void renderWidget(
        //$$     final DrawContext context,
        //$$     final int mouseX,
        //$$     final int mouseY,
        //$$     final float delta
        //$$ ) {
        //$$     final int x = Widgets.x(this);
        //$$     final int y = Widgets.y(this);
        //$$     final Identifier background = !active
        //$$         ? Ids.of("widget/button_disabled")
        //$$         : isHovered() ? Ids.of("widget/button_highlighted") : Ids.of("widget/button");
        //$$     context.drawGuiTexture(
        //$$         RenderLayer::getGuiTextured,
        //$$         background,
        //$$         x,
        //$$         y,
        //$$         getWidth(),
        //$$         getHeight(),
        //$$         ((int) (alpha * 255.0f) << 24) | 0x00FFFFFF
        //$$     );
        //$$     context.draw();
        //$$     drawContents(GuiDraw.of(context), x, y);
        //$$ }
        //#elseif MC>=12002
        //$$ protected void renderWidget(
        //$$     final DrawContext context,
        //$$     final int mouseX,
        //$$     final int mouseY,
        //$$     final float delta
        //$$ ) {
        //$$     final int x = Widgets.x(this);
        //$$     final int y = Widgets.y(this);
        //$$     final Identifier background = !active
        //$$         ? Ids.of("widget/button_disabled")
        //$$         : isHovered() ? Ids.of("widget/button_highlighted") : Ids.of("widget/button");
        //$$     context.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        //$$     context.drawGuiTexture(background, x, y, getWidth(), getHeight());
        //$$     context.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        //$$     context.draw();
        //$$     drawContents(GuiDraw.of(context), x, y);
        //$$ }
        //#elseif MC>=12000
        //$$ protected void renderButton(
        //$$     final DrawContext context,
        //$$     final int mouseX,
        //$$     final int mouseY,
        //$$     final float delta
        //$$ ) {
        //$$     context.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
        //$$     final int v = 46 + (!active ? 0 : isHovered() ? 2 : 1) * 20;
        //$$     final int x = Widgets.x(this);
        //$$     final int y = Widgets.y(this);
        //$$     final int leftW = getWidth() / 2;
        //$$     final int rightW = getWidth() - leftW;
        //$$     final int topH = getHeight() / 2;
        //$$     final int bottomH = getHeight() - topH;
        //$$     context.drawTexture(WIDGETS_TEXTURE, x, y, 0, v, leftW, topH);
        //$$     context.drawTexture(WIDGETS_TEXTURE, x + leftW, y, 200 - rightW, v, rightW, topH);
        //$$     context.drawTexture(WIDGETS_TEXTURE, x, y + topH, 0, v + 20 - bottomH, leftW, bottomH);
        //$$     context.drawTexture(
        //$$         WIDGETS_TEXTURE, x + leftW, y + topH,
        //$$         200 - rightW, v + 20 - bottomH, rightW, bottomH
        //$$     );
        //$$     context.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        //$$     context.draw();
        //$$     drawContents(GuiDraw.of(context), x, y);
        //$$ }
        //#else
        public void renderButton(
            final MatrixStack matrices,
            final int mouseX,
            final int mouseY,
            final float delta
        ) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, WIDGETS_TEXTURE);
            RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, alpha);
            RenderSystem.enableBlend();
            RenderSystem.defaultBlendFunc();
            RenderSystem.enableDepthTest();
            final int v = 46 + getYImage(isHovered()) * 20;
            final int x = Widgets.x(this);
            final int y = Widgets.y(this);
            final int leftW = getWidth() / 2;
            final int rightW = getWidth() - leftW;
            final int topH = getHeight() / 2;
            final int bottomH = getHeight() - topH;
            drawTexture(matrices, x, y, 0, v, leftW, topH);
            drawTexture(matrices, x + leftW, y, 200 - rightW, v, rightW, topH);
            drawTexture(matrices, x, y + topH, 0, v + 20 - bottomH, leftW, bottomH);
            drawTexture(matrices, x + leftW, y + topH, 200 - rightW, v + 20 - bottomH, rightW, bottomH);
            drawContents(GuiDraw.of(matrices), x, y);
        }
        //#endif

        private void drawContents(final GuiDraw draw, final int x, final int y) {
            final MatrixStack matrices = draw.matrices();
            final UiIcon resolvedIcon = uiTheme.icon(icon);
            final MapIconButtonVisualState visual = MapIconButtonVisualState.of(
                active, selected, isHovered(), resolvedIcon.colorMode(),
                uiTheme.useVanillaButtonStyle()
            );
            if (visual.background() != 0) {
                RenderUtil.fillRect(
                    matrices, x, y, getWidth(), getHeight(), visual.background()
                );
            }
            if (visual.border() != 0) {
                RenderUtil.fillRect(matrices, x, y, getWidth(), 1, visual.border());
                RenderUtil.fillRect(
                    matrices, x, y + getHeight() - 1, getWidth(), 1, visual.border()
                );
                RenderUtil.fillRect(matrices, x, y, 1, getHeight(), visual.border());
                RenderUtil.fillRect(
                    matrices, x + getWidth() - 1, y, 1, getHeight(), visual.border()
                );
            }
            final int iconX = x + (getWidth() - CONTROL_ICON_SIZE) / 2;
            final int iconY = y + (getHeight() - CONTROL_ICON_SIZE) / 2;
            final UiTextureRegion texture = resolvedIcon.region();
            RenderUtil.bindTexture(MinecraftClient.getInstance(), texture.texture());
            RenderUtil.drawTintedQuad(
                matrices,
                iconX,
                iconY,
                CONTROL_ICON_SIZE,
                CONTROL_ICON_SIZE,
                texture.u0(),
                texture.v0(),
                texture.u1(),
                texture.v1(),
                visual.iconTint()
            );
        }
    }

    int currentLod() {
        return TileMath.lodForScale(scale, this.config.mapQuality);
    }

    FullscreenMapBrowseService.Target viewTarget() {
        final SessionGuard.Session session = viewSession();
        return new FullscreenMapBrowseService.Target(session.world(), session.dimension());
    }

    void selectViewTarget(final FullscreenMapBrowseService.Target target) {
        final SessionGuard.Session previous = viewSession();
        final boolean targetChanged = !target.world().equals(previous.world())
            || !target.dimension().equals(previous.dimension());
        viewState.rememberScale(previous.dimension(), scale);
        final double previousCenterX = centerX;
        final double previousCenterZ = centerZ;
        final SessionGuard.Session live = gameBridge.session();
        mapBrowser.select(target, live);
        final SessionGuard.Session selected = viewSession();
        if (targetChanged) {
            toolPanel.close();
            annotations.resetForTargetChange();
        }
        centerX = DimensionScale.convertHorizontal(
            previousCenterX, previous.dimension(), selected.dimension()
        );
        centerZ = DimensionScale.convertHorizontal(
            previousCenterZ, previous.dimension(), selected.dimension()
        );
        scale = viewState.viewForOpening(
            selected.dimension(), centerX, centerZ, DEFAULT_SCALE
        ).scale();
        tiles.clearViewport();
        mapBrowser.tiles().clearViewport();
        predictionTiles.clearViewport();
        ConfluxMapClient.get().mapSyncClient().clearViewport();
        chunkLoadStates.deactivate();
        locationMenu.dismissLocationMenu();
        rebuildWaypointControls();
    }

    SessionGuard.Session viewSession() {
        return mapBrowser.displayedSession(gameBridge.session());
    }

    boolean viewingLiveWorld() {
        return viewSession().world().equals(gameBridge.session().world());
    }

    boolean viewingLiveSession() {
        final SessionGuard.Session viewed = viewSession();
        final SessionGuard.Session live = gameBridge.session();
        return viewed.world().equals(live.world()) && viewed.dimension().equals(live.dimension());
    }

    WaypointService viewWaypointService() {
        return viewingLiveWorld() ? waypointService : mapBrowser.waypoints();
    }

    WaypointStore viewWaypointStore() {
        return viewWaypointService().current();
    }

    WaypointRenderCatalog viewWaypointRenderCatalog() {
        return viewingLiveWorld() ? waypointRenderCatalog : archivedWaypointRenderCatalog;
    }

    AnnotationStore viewAnnotationStore() {
        final AnnotationService service = viewingLiveWorld()
            ? annotationService
            : mapBrowser.annotations();
        return service.current();
    }

    /** Fullscreen Nether maps default to the persistent top-of-roof surface, never the cave layer. */
    MapLayer viewLayer() {
        return FullscreenMapLayerPolicy.select(
            viewSession().dimension(), viewingLiveSession(), layerSelector.current().layer()
        );
    }

    MapWorldService viewMapWorlds() {
        return mapBrowser.browsing() ? mapBrowser.mapWorlds() : mapWorlds;
    }

    TileService viewTiles() {
        return mapBrowser.browsing() ? mapBrowser.tiles() : tiles;
    }

    TileTextureManager viewTextures() {
        return mapBrowser.browsing() ? mapBrowser.textures() : textures;
    }

    /**
     * Draws the real tile grid, and - when a seed-predicted/synchronized plane is available for the
     * current dimension+layer ({@link MapLayer.Type#SURFACE} in the Overworld, {@link
     * MapLayer.Type#NETHER_CEILING} in the Nether, or {@link MapLayer.Type#END_SURFACE} in the End) -
     * the matching selected-source tile drawn afterward. That texture is transparent where local
     * authoritative data is newer, contains synchronized pixels where the server is newer, and
     * falls back to prediction only where neither real source has coverage.
     */
    private void drawTiles(final MatrixStack matrices) {
        drawTiles(matrices, true);
    }

    private void drawTiles(final MatrixStack matrices, final boolean updateViewport) {
        final int lod = currentLod();
        final double pxPerBlock = 1.0 / scale;
        final double blocksPerTile = TileMath.blocksPerTile(lod);
        final TileViewport viewport = TileViewport.covering(centerX, centerZ, width, height, scale, lod);
        final int firstTileX = viewport.minTileX();
        final int lastTileX = viewport.maxTileX();
        final int firstTileZ = viewport.minTileZ();
        final int lastTileZ = viewport.maxTileZ();

        final SessionGuard.Session session = viewSession();
        final MapLayer layer = viewLayer();
        final String layerId = layer.cacheId();
        final boolean biomeMode = biomeMode();
        final boolean predictionActive = predictionActive(layer, session, biomeMode);
        final boolean syncActive = predictionActive(layer, session, false);
        if (updateViewport) {
            viewTiles().setViewport(layer, lod, firstTileX, lastTileX, firstTileZ, lastTileZ);
            if (predictionActive) {
                if (session.dimension().equals(DimensionId.NETHER)) {
                    predictionTiles.setNetherBiomeY(layerSelector.current().pivotY());
                }
                predictionTiles.setViewport(
                    session.dimension(), lod, firstTileX, lastTileX, firstTileZ, lastTileZ
                );
                if (syncActive) {
                    final ChunkViewport mapChunks = ChunkViewport.covering(
                        centerX, centerZ, width, height, scale
                    );
                    final ChunkViewport playerView;
                    if (this.client.player == null) {
                        playerView = null;
                    } else {
                        final int playerChunkX = this.client.player.getBlockPos().getX() >> 4;
                        final int playerChunkZ = this.client.player.getBlockPos().getZ() >> 4;
                        final int advertisedRadius = companion.serverViewDistance();
                        final int radius = advertisedRadius >= 0
                            ? advertisedRadius : MinecraftAccess.viewDistance(this.client);
                        playerView = ChunkViewport.centered(playerChunkX, playerChunkZ, radius);
                    }
                    ConfluxMapClient.get().mapSyncClient().reportViewport(
                        session.dimension(), lod, firstTileX, lastTileX, firstTileZ, lastTileZ,
                        mapChunks, playerView
                    );
                } else {
                    ConfluxMapClient.get().mapSyncClient().clearViewport();
                }
            } else {
                predictionTiles.clearViewport();
                ConfluxMapClient.get().mapSyncClient().clearViewport();
            }
        }

        RenderUtil.beginTexturedQuads();
        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey terrainKey = new TileKey(
                    session.world(), session.dimension(), layerId, lod, tileX, tileZ
                );
                final TileKey key = biomeMode ? BiomeTileKeys.toBiome(terrainKey) : terrainKey;
                if (viewTextures().bind(key)) {
                    final float screenX = (float) (width / 2.0 + (key.originBlockX() - centerX) * pxPerBlock);
                    final float screenY = (float) (height / 2.0 + (key.originBlockZ() - centerZ) * pxPerBlock);
                    final float quadSize = (float) (blocksPerTile * pxPerBlock);
                    RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
                }
            }
        }

        // The predicted texture is already transparent wherever local real data wins. Drawing it
        // last lets a newer synchronized chunk cover an older local capture without a third texture.
        if (predictionActive) {
            drawPredictedTiles(
                matrices, session, layerId, lod, firstTileX, lastTileX,
                firstTileZ, lastTileZ, pxPerBlock, blocksPerTile, biomeMode
            );
        }
    }

    private void drawPredictedTiles(
        final MatrixStack matrices,
        final SessionGuard.Session session,
        final String layerId,
        final int lod,
        final int firstTileX,
        final int lastTileX,
        final int firstTileZ,
        final int lastTileZ,
        final double pxPerBlock,
        final double blocksPerTile,
        final boolean biomeMode
    ) {
        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey terrainKey = new TileKey(
                    session.world(), session.dimension(), layerId, lod, tileX, tileZ
                );
                final TileKey key = biomeMode ? BiomeTileKeys.toBiome(terrainKey) : terrainKey;
                if (textures.bind(PredictedTileKeys.toPredicted(key))) {
                    final float screenX = (float) (
                        width / 2.0 + (key.originBlockX() - centerX) * pxPerBlock
                    );
                    final float screenY = (float) (
                        height / 2.0 + (key.originBlockZ() - centerZ) * pxPerBlock
                    );
                    final float quadSize = (float) (blocksPerTile * pxPerBlock);
                    RenderUtil.drawTintedQuad(
                        matrices, screenX, screenY, quadSize, quadSize,
                        0f, 0f, 1f, 1f, 0xFFFFFFFF
                    );
                }
            }
        }
    }

    /** Seed prediction can additionally fill lower Nether biome planes; synchronization stays top-only. */
    boolean predictionActive(
        final MapLayer layer,
        final SessionGuard.Session session,
        final boolean biomeMode
    ) {
        final SessionGuard.Session live = gameBridge.session();
        return config.predictionEnabled
            && session.world().equals(live.world())
            && session.dimension().equals(live.dimension())
            && FullscreenMapLayerPolicy.predictionAllowed(
                session.dimension(), layer, biomeMode
            )
            && predictionState.predictable(session.dimension());
    }

    /**
     * Radar entries above the map tiles but below waypoint markers (see {@link #render}), reusing
     * {@link RadarMarkerRenderer} in detailed mode while the minimap normally uses compact markers.
     * Always north-up (this screen never rotates), and positions use the same
     * live-interpolated-position-over-scan-snapshot preference as the minimap (see
     * {@code MinimapHudRenderer#drawRadar}'s javadoc). Category toggles and {@code radarEnabled}
     * already apply upstream in the scanner, so no extra filtering happens here beyond viewport
     * culling.
     */
    private void drawRadar(
        final GuiDraw draw,
        final float tickDelta,
        final int mouseX,
        final int mouseY
    ) {
        hoveredRadarPlayer = null;
        if (!viewingLiveWorld() || this.client.world == null) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.viewpoint(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final long nowMs = System.currentTimeMillis();
        final double pxPerBlock = 1.0 / scale;
        final List<RadarMarkerRenderer.Marker> markers = new ArrayList<>();
        final DimensionId viewedDimension = viewSession().dimension();
        final List<RadarEntry> entries = ServerPlayerRadarEntries.merge(
            viewingLiveSession() ? radarScanner.snapshot() : List.of(),
            config.radarShowPlayers
                ? serverPlayerRadar.playersIn(viewedDimension, nowMs)
                : List.of(),
            this.client.player == null ? null : this.client.player.getUuid(),
            viewedDimension.equals(gameBridge.session().dimension()) ? player.y() : Double.NaN
        );
        final Optional<ServerPlayerRadarState.HighlightView> highlight =
            serverPlayerRadar.highlightedIn(
                viewedDimension,
                nowMs,
                config.radarPlayerHighlightGhostSeconds * 1_000L
            );
        final List<RadarEntry> visibleEntries;
        if (highlight.isPresent() && highlight.get().ghost()) {
            visibleEntries = new ArrayList<>(entries);
            visibleEntries.add(ServerPlayerRadarEntries.entry(
                highlight.get().player(),
                viewedDimension.equals(gameBridge.session().dimension())
                    ? player.y() : Double.NaN
            ));
        } else {
            visibleEntries = entries;
        }
        for (final RadarEntry entry : visibleEntries) {
            double ex = entry.x();
            double ez = entry.z();
            int yDelta = entry.yDelta();
            final Entity live = this.client.world.getEntityById(entry.entityId());
            if (live != null) {
                ex = MathHelper.lerp(tickDelta, live.prevX, live.getX());
                ez = MathHelper.lerp(tickDelta, live.prevZ, live.getZ());
                yDelta = (int) Math.round(live.getY() - player.y());
            }

            float screenX = (float) (width / 2.0 + (ex - centerX) * pxPerBlock);
            float screenY = (float) (height / 2.0 + (ez - centerZ) * pxPerBlock);
            final boolean highlighted = entry.playerId() != null
                && serverPlayerRadar.isHighlighted(entry.playerId());
            final boolean outside =
                screenX < -RADAR_CULL_MARGIN || screenX > width + RADAR_CULL_MARGIN
                    || screenY < -RADAR_CULL_MARGIN || screenY > height + RADAR_CULL_MARGIN;
            if (outside) {
                if (!highlighted) {
                    continue;
                }
                final float dx = screenX - width / 2f;
                final float dy = screenY - height / 2f;
                final float xLimit = width / 2f - Math.max(8f, config.radarIconSize);
                final float yLimit = height / 2f - Math.max(8f, config.radarIconSize);
                final float factor = Math.min(
                    dx == 0f ? Float.POSITIVE_INFINITY : xLimit / Math.abs(dx),
                    dy == 0f ? Float.POSITIVE_INFINITY : yLimit / Math.abs(dy)
                );
                screenX = width / 2f + dx * factor;
                screenY = height / 2f + dy * factor;
            }
            final boolean ghost = highlight.isPresent()
                && highlight.get().ghost()
                && entry.playerId() != null
                && entry.playerId().equals(highlight.get().player().playerId());
            final RadarMarkerRenderer.Marker marker = new RadarMarkerRenderer.Marker(
                entry,
                screenX,
                screenY,
                yDelta,
                live,
                highlighted,
                ghost,
                ghost ? highlight.get().destination() : null
            );
            markers.add(marker);
            if (entry.playerId() != null
                && !radarMarkerIntersectsUi(marker)
                && Math.hypot(mouseX - screenX, mouseY - screenY)
                    <= Math.max(8.0, config.radarIconSize / 2.0)) {
                hoveredRadarPlayer = ghost
                    ? highlight.get().player()
                    : serverPlayerRadar.player(entry.playerId(), nowMs).orElse(null);
            }
        }
        RadarMarkerRenderer.drawAll(
            draw, this.client, config, radarIconManager, markers,
            RadarMarkerRenderer.Presentation.detailed(config.radarShowPlayerNames),
            marker -> !radarMarkerIntersectsUi(marker)
        );
    }

    private static int withAlpha(final int color, final float alpha) {
        final int clamped = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (clamped << 24) | (color & 0x00FFFFFF);
    }

    private boolean radarMarkerIntersectsUi(final RadarMarkerRenderer.Marker marker) {
        final float iconHalf = Math.max(2.5f, config.radarIconSize / 2f);
        final String name = marker.entry().name();
        final MapOverlayBounds bounds = MapOverlayBounds.radar(
            marker.x(), marker.y(), iconHalf,
            name == null ? 0f : this.textRenderer.getWidth(name),
            this.textRenderer.fontHeight,
            marker.entry().category(),
            config.radarShowPlayerNames
        );
        return mapOverlayIntersectsUi(bounds);
    }

    private void drawStructures(final GuiDraw draw, final int mouseX, final int mouseY) {
        hoveredStructure = null;
        if (!viewingLiveWorld()
            || !companion.structureSearchAllowed() || !config.predictionShowStructures
            || FullscreenZoomLabel.isAtOrBelow(scale, config.predictionStructureIconHideZoom)
            || !predictionState.seedKnown()) {
            return;
        }
        final int minX = (int) Math.floor(centerX - width / 2.0 * scale);
        final int maxX = (int) Math.ceil(centerX + width / 2.0 * scale);
        final int minZ = (int) Math.floor(centerZ - height / 2.0 * scale);
        final int maxZ = (int) Math.ceil(centerZ + height / 2.0 * scale);
        final double pxPerBlock = 1.0 / scale;
        final DimensionId dimension = viewSession().dimension();
        final java.util.EnumSet<StructureIndex.StructureType> visibleTypes =
            config.predictionStructureVisibility.visibleTypes(
                predictionState.mcVersion(),
                dimension,
                structureMarkers.availableTypes(dimension)
            );
        final int cellSize = StructureMarkerGrid.cellSize(scale);
        final List<StructureIndex.Marker> visibleMarkers = StructureMarkerGrid.select(
            structureMarkers.queryViewport(
                StructureMarkerGrid.minimum(minX, cellSize),
                StructureMarkerGrid.maximum(maxX, cellSize),
                StructureMarkerGrid.minimum(minZ, cellSize),
                StructureMarkerGrid.maximum(maxZ, cellSize),
                scale, visibleTypes
            ),
            cellSize
        );
        double bestHoverDistance = 8.0;
        for (final StructureIndex.Marker marker : visibleMarkers) {
            final float screenX = (float) (width / 2.0 + (marker.blockX() - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.blockZ() - centerZ) * pxPerBlock);
            if (mapOverlayIntersectsUi(MapOverlayBounds.structureIcon(screenX, screenY, true))) {
                continue;
            }
            final double hoverDistance = Math.hypot(mouseX - screenX, mouseY - screenY);
            if (hoverDistance <= bestHoverDistance) {
                bestHoverDistance = hoverDistance;
                hoveredStructure = marker;
            }
        }
        for (final StructureIndex.Marker marker : visibleMarkers) {
            final float screenX = (float) (width / 2.0 + (marker.blockX() - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.blockZ() - centerZ) * pxPerBlock);
            if (screenX < -8 || screenX > width + 8 || screenY < -8 || screenY > height + 8) {
                continue;
            }
            final boolean hovered = marker.equals(hoveredStructure);
            final MapOverlayBounds iconBounds = MapOverlayBounds.structureIcon(
                screenX, screenY, hovered
            );
            if (!mapOverlayIntersectsUi(iconBounds)) {
                StructureMarkerRenderer.draw(draw, marker, screenX, screenY, hovered);
            }
            if (hovered) {
                final var label = Texts.translatable(marker.translationKey());
                final float labelX = screenX + 10f;
                final float labelY = screenY - 4f;
                final MapOverlayBounds labelBounds = MapOverlayBounds.text(
                    labelX, labelY, this.textRenderer.getWidth(label), this.textRenderer.fontHeight
                );
                if (!mapOverlayIntersectsUi(labelBounds)) {
                    draw.drawTextWithShadow(
                        this.textRenderer, label, labelX, labelY, TEXT_COLOR
                    );
                }
            }
        }
    }

    /**
     * Activated portal markers of the viewed dimension: an optional translucent
     * fill of each containing chunk, then icon plates at the anchors with the
     * same hover-label treatment as structure markers. Portals are
     * client-observed facts, so unlike predicted structures they need no seed
     * or companion permission - only a live world to belong to.
     */
    private void drawPortals(final GuiDraw draw, final int mouseX, final int mouseY) {
        if (!viewingLiveWorld() || !config.portalMarkersEnabled) {
            return;
        }
        final List<PortalMarker> markers = portals.list(viewSession().dimension());
        if (markers.isEmpty()) {
            return;
        }
        final double pxPerBlock = 1.0 / scale;
        if (config.portalChunkHighlightEnabled) {
            final java.util.HashSet<Long> filledChunks = new java.util.HashSet<>();
            for (final PortalMarker marker : markers) {
                if (!filledChunks.add(((long) marker.chunkX() << 32) ^ marker.chunkZ() & 0xFFFFFFFFL)) {
                    continue;
                }
                final float left = (float) (width / 2.0 + (marker.chunkX() * 16.0 - centerX) * pxPerBlock);
                final float top = (float) (height / 2.0 + (marker.chunkZ() * 16.0 - centerZ) * pxPerBlock);
                final float sizePx = (float) (16.0 * pxPerBlock);
                if (sizePx < 2f || left > width || top > height || left + sizePx < 0f || top + sizePx < 0f) {
                    continue;
                }
                RenderUtil.fillRect(
                    draw.matrices(), left, top, sizePx, sizePx, config.portalHighlightColor.argb()
                );
            }
        }
        if (!config.portalIconsEnabled
            || FullscreenZoomLabel.isAtOrBelow(scale, config.portalIconHideZoom)) {
            return;
        }
        PortalMarker hovered = null;
        double bestHoverDistance = 8.0;
        for (final PortalMarker marker : markers) {
            final float screenX = (float) (width / 2.0 + (marker.anchorX() + 0.5 - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.anchorZ() + 0.5 - centerZ) * pxPerBlock);
            if (screenX < -16 || screenX > width + 16 || screenY < -16 || screenY > height + 16) {
                continue;
            }
            final double hoverDistance = Math.hypot(mouseX - screenX, mouseY - screenY);
            if (hoverDistance <= bestHoverDistance) {
                bestHoverDistance = hoverDistance;
                hovered = marker;
            }
        }
        for (final PortalMarker marker : markers) {
            final float screenX = (float) (width / 2.0 + (marker.anchorX() + 0.5 - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.anchorZ() + 0.5 - centerZ) * pxPerBlock);
            if (screenX < -16 || screenX > width + 16 || screenY < -16 || screenY > height + 16) {
                continue;
            }
            final boolean isHovered = marker.equals(hovered);
            final MapOverlayBounds bounds = new MapOverlayBounds(
                screenX - config.portalIconSize / 2f,
                screenY - config.portalIconSize / 2f,
                screenX + config.portalIconSize / 2f,
                screenY + config.portalIconSize / 2f
            );
            if (mapOverlayIntersectsUi(bounds)) {
                continue;
            }
            PortalMarkerRenderer.draw(
                draw, marker.kind(), config.portalIconTexture(marker.kind()),
                screenX, screenY,
                config.portalIconSize, config.portalIconOpacity, isHovered
            );
            if (isHovered) {
                final var label = Texts.translatable("confluxmap.portal." + marker.kind().blockId());
                final float labelX = screenX + 10f;
                final float labelY = screenY - 4f;
                final MapOverlayBounds labelBounds = MapOverlayBounds.text(
                    labelX, labelY, this.textRenderer.getWidth(label), this.textRenderer.fontHeight
                );
                if (!mapOverlayIntersectsUi(labelBounds)) {
                    draw.drawTextWithShadow(this.textRenderer, label, labelX, labelY, TEXT_COLOR);
                }
            }
        }
    }

    /** Faint lines on LOD-0 tile boundaries (256-block spacing), skipped once they'd be denser than {@link #MIN_GRID_SPACING_PX}. */
    private void drawGrid(final MatrixStack matrices) {
        final double pxPerBlock = 1.0 / scale;
        final double spacingPx = TileMath.TILE_SIZE * pxPerBlock;
        if (spacingPx < MIN_GRID_SPACING_PX) {
            return;
        }
        final int firstLineX = TileMath.blockToTile((int) Math.floor(centerX - width / 2.0 * scale));
        final int lastLineX = TileMath.blockToTile((int) Math.ceil(centerX + width / 2.0 * scale));
        for (int tx = firstLineX; tx <= lastLineX + 1; tx++) {
            final float screenX = (float) (width / 2.0 + (tx * (double) TileMath.TILE_SIZE - centerX) * pxPerBlock);
            RenderUtil.fillRect(matrices, screenX, 0f, 1f, height, GRID_COLOR);
        }
        final int firstLineZ = TileMath.blockToTile((int) Math.floor(centerZ - height / 2.0 * scale));
        final int lastLineZ = TileMath.blockToTile((int) Math.ceil(centerZ + height / 2.0 * scale));
        for (int tz = firstLineZ; tz <= lastLineZ + 1; tz++) {
            final float screenY = (float) (height / 2.0 + (tz * (double) TileMath.TILE_SIZE - centerZ) * pxPerBlock);
            RenderUtil.fillRect(matrices, 0f, screenY, width, 1f, GRID_COLOR);
        }
    }

    /**
     * Faint chunk-border lattice (Xaero-World-Map-style: thin, low-alpha, dark - readable over
     * both light and dark terrain) plus a highlight on the chunk under the cursor. Drawn after
     * the map tiles but before waypoint markers. Guarded by {@link ConfluxConfig#fullmapChunkGrid}
     * and skipped once a chunk would render under {@link #MIN_CHUNK_GRID_SPACING_PX} wide, to
     * avoid moire noise when zoomed far out.
     */
    private void drawChunkGrid(final MatrixStack matrices, final int mouseX, final int mouseY) {
        if (!config.fullmapChunkGrid) {
            return;
        }
        final double pxPerBlock = 1.0 / scale;
        final double chunkSpacingPx = 16.0 * pxPerBlock;
        if (chunkSpacingPx < MIN_CHUNK_GRID_SPACING_PX) {
            return;
        }

        // Floor/ceil-then-blockToChunk (arithmetic shift) keeps lines exact on chunk borders
        // for negative world coordinates too.
        final int firstChunkX = TileMath.blockToChunk((int) Math.floor(centerX - width / 2.0 * scale));
        final int lastChunkX = TileMath.blockToChunk((int) Math.ceil(centerX + width / 2.0 * scale));
        for (int cx = firstChunkX; cx <= lastChunkX + 1; cx++) {
            final float screenX = (float) (width / 2.0 + (cx * 16.0 - centerX) * pxPerBlock);
            RenderUtil.fillRect(matrices, screenX, 0f, 1f, height, CHUNK_GRID_COLOR);
        }
        final int firstChunkZ = TileMath.blockToChunk((int) Math.floor(centerZ - height / 2.0 * scale));
        final int lastChunkZ = TileMath.blockToChunk((int) Math.ceil(centerZ + height / 2.0 * scale));
        for (int cz = firstChunkZ; cz <= lastChunkZ + 1; cz++) {
            final float screenY = (float) (height / 2.0 + (cz * 16.0 - centerZ) * pxPerBlock);
            RenderUtil.fillRect(matrices, 0f, screenY, width, 1f, CHUNK_GRID_COLOR);
        }

        drawHoveredChunkHighlight(matrices, mouseX, mouseY, pxPerBlock);
    }

    /** Fills the 16x16-block chunk under the cursor and outlines it, layered on top of the grid lines drawn just above. */
    private void drawHoveredChunkHighlight(final MatrixStack matrices, final int mouseX, final int mouseY, final double pxPerBlock) {
        final double hoverWorldX = centerX + (mouseX - width / 2.0) * scale;
        final double hoverWorldZ = centerZ + (mouseY - height / 2.0) * scale;
        final int chunkX = TileMath.blockToChunk((int) Math.floor(hoverWorldX));
        final int chunkZ = TileMath.blockToChunk((int) Math.floor(hoverWorldZ));
        final float screenX = (float) (width / 2.0 + (chunkX * 16.0 - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (chunkZ * 16.0 - centerZ) * pxPerBlock);
        final float sizePx = (float) (16.0 * pxPerBlock);

        RenderUtil.fillRect(matrices, screenX, screenY, sizePx, sizePx, CHUNK_HIGHLIGHT_FILL);
        RenderUtil.fillRect(matrices, screenX, screenY, sizePx, 1f, CHUNK_HIGHLIGHT_BORDER);
        RenderUtil.fillRect(matrices, screenX, screenY + sizePx - 1f, sizePx, 1f, CHUNK_HIGHLIGHT_BORDER);
        RenderUtil.fillRect(matrices, screenX, screenY, 1f, sizePx, CHUNK_HIGHLIGHT_BORDER);
        RenderUtil.fillRect(matrices, screenX + sizePx - 1f, screenY, 1f, sizePx, CHUNK_HIGHLIGHT_BORDER);
    }

    /** Draws a translucent marker at the detached camera's world position. */
    private void drawCameraMarker(
        final MatrixStack matrices,
        final Optional<PlayerView> viewpoint
    ) {
        if (!viewingLiveSession() || !gameBridge.isCameraDetached()) {
            return;
        }
        if (viewpoint.isEmpty()) {
            return;
        }
        final PlayerView camera = viewpoint.get();
        final double pxPerBlock = 1.0 / scale;
        final float screenX = (float) (width / 2.0 + (camera.x() - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (camera.z() - centerZ) * pxPerBlock);
        PlayerMarkerRenderer.draw(
            this.client,
            matrices,
            uiTheme,
            config.playerMarkerStyle,
            screenX,
            screenY,
            camera.yawDegrees() + 180f,
            PLAYER_MARKER_COLOR,
            0.5f
        );
    }

    /** Always north-locked, so only the arrow itself rotates with the player's facing (mirrors {@code MinimapHudRenderer}'s north-locked mode). */
    private void drawPlayerMarker(final MatrixStack matrices, final float tickDelta) {
        if (!viewingLiveSession()) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final double pxPerBlock = 1.0 / scale;
        final float screenX = (float) (width / 2.0 + (player.x() - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (player.z() - centerZ) * pxPerBlock);
        PlayerMarkerRenderer.draw(
            this.client,
            matrices,
            uiTheme,
            config.playerMarkerStyle,
            screenX,
            screenY,
            player.yawDegrees() + 180f,
            PLAYER_MARKER_COLOR
        );
    }

    /**
     * Markers stored in the viewed dimension, using their raw local coordinates. Fixed
     * on-screen size regardless of zoom - only their world position, and thus screen
     * position, changes with pan/zoom. Names are shown continuously once zoomed in past
     * {@link #NAME_LABEL_MAX_SCALE} blocks-per-pixel, or always for the single marker
     * nearest the cursor within {@link #HOVER_RADIUS_PX} (deliverable C), which also gets
     * brightened (see {@link WaypointMarkerRenderer}) and has its coordinates shown in the
     * footer by {@link #drawCursorCoords}.
     */
    private void drawWaypoints(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final Optional<PlayerView> playerView
    ) {
        final DimensionId currentDimension = viewSession().dimension();
        final double pxPerBlock = 1.0 / scale;
        final List<ScreenMarker> markers = new ArrayList<>();
        final List<WaypointRenderEntry> waypoints = waypointsForRender(currentDimension);
        for (final WaypointRenderEntry waypoint : waypoints) {
            final float screenX = (float) (width / 2.0 + (waypoint.x() - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (waypoint.z() - centerZ) * pxPerBlock);
            if (screenX < -MARKER_HALF_SIZE || screenX > width + MARKER_HALF_SIZE
                || screenY < -MARKER_HALF_SIZE || screenY > height + MARKER_HALF_SIZE) {
                continue;
            }
            markers.add(new ScreenMarker(waypoint, screenX, screenY));
        }

        WaypointRenderEntry hovered = null;
        double bestHoverDist = HOVER_RADIUS_PX;
        for (final ScreenMarker marker : markers) {
            final double hoverDist = Math.hypot(mouseX - marker.screenX(), mouseY - marker.screenY());
            if (hoverDist <= bestHoverDist) {
                bestHoverDist = hoverDist;
                hovered = marker.waypoint();
            }
        }
        hoveredWaypoint = hovered;
        final boolean hasHighlight = waypointHighlightState.hasRenderableTarget(
            waypoints, currentDimension
        );

        for (final ScreenMarker marker : markers) {
            final WaypointRenderEntry waypoint = marker.waypoint();
            final boolean selected = waypointHighlightState.matchesEntry(waypoint, currentDimension);
            final boolean isHovered = waypoint == hoveredWaypoint || selected;
            final WaypointVerticalRelation relation = playerView
                .map(player -> WaypointVerticalRelation.between(waypoint.y(), player.y()))
                .orElse(WaypointVerticalRelation.NONE);
            final float markerLeft = marker.screenX() - MARKER_HALF_SIZE - 1f;
            final float markerTop = marker.screenY() - MARKER_HALF_SIZE - 1f;
            final float markerRight = marker.screenX() + MARKER_HALF_SIZE + 1f;
            final float markerBottom = marker.screenY() + MARKER_HALF_SIZE + 1f;
            if (!mapOverlayIntersectsUi(markerLeft, markerTop, markerRight, markerBottom)) {
                WaypointMarkerRenderer.draw(
                    draw, this.client.textRenderer, waypoint, marker.screenX(), marker.screenY(),
                    MARKER_HALF_SIZE, visibilityAlpha(selected, hasHighlight), isHovered, relation
                );
            }
            if (scale <= NAME_LABEL_MAX_SCALE || isHovered) {
                final float labelX = marker.screenX() + MARKER_HALF_SIZE + 2;
                final float labelY = marker.screenY() - 4;
                final float labelRight = labelX + this.textRenderer.getWidth(waypoint.name());
                final float labelBottom = labelY + this.textRenderer.fontHeight;
                if (!mapOverlayIntersectsUi(labelX, labelY, labelRight, labelBottom)) {
                    draw.drawTextWithShadow(
                        this.textRenderer, waypoint.name(), labelX, labelY,
                        withAlpha(TEXT_COLOR, visibilityAlpha(selected, hasHighlight))
                    );
                }
            }
        }
    }

    private List<WaypointRenderEntry> waypointsForRender(final DimensionId dimension) {
        final List<WaypointRenderEntry> base = viewWaypointRenderCatalog().snapshot(dimension);
        // The highlight belongs to the live world's session; a browsed world's map never
        // shows it, not even in the highlighted dimension.
        final Optional<WaypointHighlightState.Target> target = viewingLiveWorld()
            ? waypointHighlightState.locationTargetIn(dimension)
            : Optional.empty();
        if (target.isEmpty()) {
            return base;
        }
        final List<WaypointRenderEntry> result = new ArrayList<>(base.size() + 1);
        result.addAll(base);
        result.add(selectedLocationEntry(target.get(), dimension));
        return result;
    }

    private WaypointRenderEntry selectedLocationEntry(
        final WaypointHighlightState.Target target,
        final DimensionId displayedDimension
    ) {
        return WaypointHighlightState.locationEntry(
            target,
            WaypointHighlightState.locationDisplayName(target, displayedDimension),
            target.y(),
            0xFFFFE066
        );
    }

    private float visibilityAlpha(final boolean selected, final boolean hasHighlight) {
        return selected || !hasHighlight ? 1f : config.waypointHighlightDimOpacity / 100f;
    }

    boolean mapOverlayIntersectsUi(final MapOverlayBounds bounds) {
        return mapOverlayIntersectsUi(
            bounds.left(), bounds.top(), bounds.right(), bounds.bottom()
        );
    }

    /** Keeps map overlays out of persistent controls; the location menu draws over them instead. */
    boolean mapOverlayIntersectsUi(
        final float left,
        final float top,
        final float right,
        final float bottom
    ) {
        final var dropdown = selectors.targetDropdown();
        if (dropdown != null && intersects(
            left, top, right, bottom,
            dropdown.x() - 1f, dropdown.y() - 1f,
            dropdown.x() + dropdown.width() + 1f,
            dropdown.y() + dropdown.height() + 1f
        )) {
            return true;
        }
        for (final var child : children()) {
            if (!(child instanceof ClickableWidget widget) || !widget.visible
                || locationMenu.ownsTooltipWidget(widget)) {
                continue;
            }
            if (intersects(
                left, top, right, bottom,
                Widgets.x(widget), Widgets.y(widget),
                Widgets.x(widget) + widget.getWidth(),
                Widgets.y(widget) + widget.getHeight()
            )) {
                return true;
            }
        }
        return false;
    }

    private static boolean intersects(
        final float left,
        final float top,
        final float right,
        final float bottom,
        final float otherLeft,
        final float otherTop,
        final float otherRight,
        final float otherBottom
    ) {
        return right > otherLeft && left < otherRight
            && bottom > otherTop && top < otherBottom;
    }

    /** One waypoint's already-converted, already-viewport-culled screen position for this frame's {@link #drawWaypoints} pass. */
    private record ScreenMarker(WaypointRenderEntry waypoint, float screenX, float screenY) {
    }

    /**
     * Third-party markers from the public API, above waypoints: same marker visuals and
     * label threshold as {@link #drawWaypoints}, but no hover/selection state - external
     * markers are informational, not navigation targets.
     */
    private void drawCustomMarkers(final GuiDraw draw) {
        final List<WaypointRenderEntry> entries = customMarkers.renderEntries(
            viewSession().dimension(), false
        );
        if (entries.isEmpty()) {
            return;
        }
        final double pxPerBlock = 1.0 / scale;
        for (final WaypointRenderEntry marker : entries) {
            final float screenX = (float) (width / 2.0 + (marker.x() - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.z() - centerZ) * pxPerBlock);
            if (screenX < -MARKER_HALF_SIZE || screenX > width + MARKER_HALF_SIZE
                || screenY < -MARKER_HALF_SIZE || screenY > height + MARKER_HALF_SIZE) {
                continue;
            }
            if (!mapOverlayIntersectsUi(
                screenX - MARKER_HALF_SIZE - 1f, screenY - MARKER_HALF_SIZE - 1f,
                screenX + MARKER_HALF_SIZE + 1f, screenY + MARKER_HALF_SIZE + 1f
            )) {
                WaypointMarkerRenderer.draw(
                    draw, this.textRenderer, marker, screenX, screenY,
                    MARKER_HALF_SIZE, 1f, false, WaypointVerticalRelation.NONE
                );
            }
            if (scale <= NAME_LABEL_MAX_SCALE) {
                final String label = marker.name();
                final float labelX = screenX + MARKER_HALF_SIZE + 2;
                final float labelY = screenY - 4;
                final float labelRight = labelX + this.textRenderer.getWidth(label);
                final float labelBottom = labelY + this.textRenderer.fontHeight;
                if (!mapOverlayIntersectsUi(labelX, labelY, labelRight, labelBottom)) {
                    draw.drawTextWithShadow(this.textRenderer, label, labelX, labelY, TEXT_COLOR);
                }
            }
        }
    }






    static String dimensionDisplayName(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return Texts.translatable("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return Texts.translatable("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return Texts.translatable("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }

    private void refreshStructureSearchButton() {
        if (structureSearchButton == null) {
            return;
        }
        final boolean available = viewingLiveWorld();
        structureSearchButton.active = available;
        structureSearchButton.setIcon(
            available ? STRUCTURE_SEARCH_ICON : STRUCTURE_SEARCH_OFF_ICON
        );
    }

    private void openStructureSearch() {
        if (!viewingLiveWorld()) {
            return;
        }
        final DimensionId dimension = viewSession().dimension();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), new StructureSearchScreen(
            this,
            structureMarkers,
            dimension,
            new ArrayList<>(structureMarkers.availableTypes(dimension))
        ));
    }

    int centerBlockX() {
        return (int) Math.floor(centerX);
    }

    int centerBlockZ() {
        return (int) Math.floor(centerZ);
    }

    void focusStructure(final StructureIndex.Marker marker) {
        centerX = marker.blockX();
        centerZ = marker.blockZ();
        scale = Math.min(scale, DEFAULT_SCALE);
    }

    void focusBiome(final BiomeCandidateSearch.Candidate candidate) {
        centerX = candidate.blockX();
        centerZ = candidate.blockZ();
        scale = Math.min(scale, DEFAULT_SCALE);
    }

    void createWaypointForStructure(
        final StructureIndex.Marker marker,
        final Screen returnScreen
    ) {
        final Optional<PlayerView> player = gameBridge.player();
        final OptionalInt playerY = player.isPresent()
            ? OptionalInt.of(player.get().blockY())
            : OptionalInt.empty();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointEditScreen.forCreate(
            returnScreen,
            viewSession().dimension(),
            Texts.translatable(marker.translationKey()).getString(),
            marker.blockX(),
            candidateWaypointY(groundAt(marker.blockX(), marker.blockZ()).surfaceY(), playerY),
            marker.blockZ()
        ));
    }

    void createWaypointForBiome(
        final String name,
        final BiomeCandidateSearch.Candidate candidate,
        final Screen returnScreen
    ) {
        final Optional<PlayerView> player = gameBridge.player();
        final OptionalInt playerY = player.isPresent()
            ? OptionalInt.of(player.get().blockY())
            : OptionalInt.empty();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointEditScreen.forCreate(
            returnScreen,
            viewSession().dimension(),
            name,
            candidate.blockX(),
            candidateWaypointY(groundAt(candidate.blockX(), candidate.blockZ()).surfaceY(), playerY),
            candidate.blockZ()
        ));
    }

    ColumnStore biomeSearchStore() {
        final MapWorld world = viewMapWorlds().current();
        final MapLayer layer = PredictionDimensions.layer(viewSession().dimension());
        return world == null || layer == null ? new ColumnStore() : world.store(layer);
    }

    BaselineSampler biomeSearchSampler(final DimensionId dimension) {
        if (!predictionState.cubiomesBacked(dimension)) {
            return null;
        }
        return new NativeBaselineSampler(
            predictionState.mcVersion(),
            predictionState.seed(),
            PredictionDimensions.nativeDim(dimension),
            predictionState.cubiomesFlags(dimension)
        );
    }

    static int candidateWaypointY(final OptionalInt surfaceY, final OptionalInt playerY) {
        return surfaceY.orElseGet(() -> playerY.orElse(DEFAULT_CANDIDATE_WAYPOINT_Y));
    }

    private record InitialFocus(DimensionId dimension, double x, double z) {
    }
    TextRenderer font() {
        return this.textRenderer;
    }

    MinecraftClient client() {
        return this.client;
    }

    <T extends ClickableWidget> T addWidget(final T widget) {
        return addDrawableChild(widget);
    }

    void clearMapWidgets() {
        //#if MC>=260100
        //$$ clearWidgets();
        //#else
        clearChildren();
        //#endif
    }

    MapIconButton iconButton(
        final int x, final int y, final Identifier icon, final ButtonWidget.PressAction onPress
    ) {
        return new MapIconButton(x, y, icon, onPress);
    }

    MapIconButton iconButton(
        final int x, final int y, final int size, final Identifier icon,
        final Text message, final ButtonWidget.PressAction onPress
    ) {
        return new MapIconButton(x, y, size, icon, message, onPress);
    }

    MapIconButton iconButton(
        final int x, final int y, final Identifier icon, final Text message, final ButtonWidget.PressAction onPress
    ) {
        return new MapIconButton(x, y, icon, message, onPress);
    }

    void beginExportSelection(final MapExportScreen screen) {
        exportControls.beginExportSelection(screen);
    }

    MapExportRequest createExportRequest(
        final MapExportBounds bounds,
        final MapExportResolution resolution
    ) {
        return exportControls.createExportRequest(bounds, resolution);
    }

    void runLocationAction(
        final FullscreenMapLocationMenu.Action action,
        final FullscreenMapLocationMenu.Target target,
        final WaypointRenderEntry waypoint,
        final UUID playerId,
        final Screen returnTo
    ) {
        locationMenu.runLocationAction(action, target, waypoint, playerId, returnTo);
    }

    List<FullscreenMapLocationMenu.ButtonSpec> locationMenuButtonSpecs(
        final FullscreenMapLocationMenu.Target target,
        final WaypointRenderEntry waypoint,
        final UUID playerId,
        final boolean deletePending
    ) {
        return locationMenu.locationMenuButtonSpecs(target, waypoint, playerId, deletePending);
    }
}
