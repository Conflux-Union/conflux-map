package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.Ids;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationDraft;
import cn.net.rms.confluxmap.core.annotation.AnnotationGeometry;
import cn.net.rms.confluxmap.core.annotation.AnnotationPersistence;
import cn.net.rms.confluxmap.core.annotation.AnnotationPoint;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.annotation.AnnotationStore;
import cn.net.rms.confluxmap.core.annotation.AnnotationStyle;
import cn.net.rms.confluxmap.core.annotation.AnnotationTool;
import cn.net.rms.confluxmap.core.color.DaylightModel;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadDetailMode;
import cn.net.rms.confluxmap.core.loadstate.ChunkLoadOverlayStyle;
import cn.net.rms.confluxmap.core.loadstate.ChunkScreenRect;
import cn.net.rms.confluxmap.core.loadstate.FullscreenDisplayMode;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.net.ChunkLoadBand;
import cn.net.rms.confluxmap.core.net.LoadStateDeltaS2C;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.predict.CubiomesBiomeIds;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.PredictionLighting;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.store.MapWorld;
import cn.net.rms.confluxmap.core.store.MapWorldService;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.BiomeTileKeys;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.util.TileViewport;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.core.waypoint.chat.WaypointChatCodec;
import cn.net.rms.confluxmap.mc.net.ChunkLoadStateClient;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.StructureMarkerService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.RadarBackdrop;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.teleport.ClientGroundTeleportService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.AnnotationRenderer;
import cn.net.rms.confluxmap.mc.ui.DisplayModeIconCatalog;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.StructureMarkerRenderer;
import cn.net.rms.confluxmap.mc.world.ClientChunkLookup;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
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
import org.lwjgl.glfw.GLFW;

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
 * javadoc on {@code MinimapHudRenderer.render} for why that would double it up.
 */
public final class FullscreenMapScreen extends ConfluxScreen {
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 16.0;
    private static final double DEFAULT_SCALE = 2.0;
    private static final double ZOOM_STEP = 1.26;

    private static final int MARGIN = 6;
    private static final int CONTROL_SIZE = 22;
    private static final int CONTROL_ICON_SIZE = 16;
    private static final int CONTROL_GAP = 3;
    private static final int LOCAL_CONTROL_ACCENT = 0xFFFFD83D;
    private static final int SHARED_CONTROL_ACCENT = 0xFF55DDE0;
    private static final Identifier LOCAL_WAYPOINT_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_local.png"
    );
    private static final Identifier SHARED_WAYPOINT_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_shared.png"
    );
    private static final Identifier MANAGE_WAYPOINT_ICON = Ids.of(
        "confluxmap", "textures/gui/waypoint_manage.png"
    );
    private static final Identifier STRUCTURE_SEARCH_ICON = Ids.of(
        "confluxmap", "textures/gui/structure_search.png"
    );
    private static final Identifier ANNOTATION_PERSISTENCE_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_persistence.png"
    );
    private static final Identifier ANNOTATION_ERASER_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_eraser.png"
    );
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int SYNCING_TEXT_COLOR = 0xFFFFE066;
    private static final int SYNCED_TEXT_COLOR = 0xFF80E080;
    private static final int SYNC_FAILED_TEXT_COLOR = 0xFFFF7777;
    private static final int UPDATE_TEXT_COLOR = 0xFFFFE066;
    private static final int BACKGROUND_COLOR = 0xFF101018;
    private static final int LOAD_STATE_ENTITY_COLOR = 0x7048B85E;
    private static final int LOAD_STATE_BLOCK_COLOR = 0x70D8A83E;
    private static final int LOAD_STATE_BORDER_COLOR = 0x704E78C4;
    private static final int LOAD_STATE_UNLOADED_COLOR = 0x00000000;
    private static final int LOAD_STATE_OUTLINE_COLOR = 0xA0101018;
    private static final int LOAD_STATE_LEGEND_BACKGROUND = 0xD0181822;
    private static final int LOCATION_MENU_BACKGROUND = 0xF0181822;
    private static final int LOCATION_MENU_BORDER = 0xFF9A9AA8;
    private static final int TEMPORARY_LOCATION_COLOR = 0xFF3498DB;
    private static final int GRID_COLOR = 0x22FFFFFF;
    private static final int ARROW_OUTLINE = 0xFF101010;
    private static final int ARROW_FILL = 0xFFFFE066;
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
    private static final int MAX_VISIBLE_MARKERS_PER_STRUCTURE = 8;
    private static final double DEFAULT_CREATE_Y = 64.0;
    /** Cursor travel between left-press and left-release below which a hovered marker click edits it, not pans (see {@link #mouseReleased}). */
    private static final double CLICK_DRAG_TOLERANCE_PX = 4.0;
    private static final double ANNOTATION_HIT_TOLERANCE_PX = 5.0;
    private static final int ANNOTATION_CONTROL_SIZE = 20;
    private static final int ANNOTATION_CONTROL_GAP = 3;
    private static final int ANNOTATION_MAX_ROWS = 4;
    private static final int ANNOTATION_COLOR_MENU_COLUMNS = 4;
    private static final long DOUBLE_CLICK_INTERVAL_MS = 350L;
    private static final UUID ANNOTATION_DRAFT_ID = new UUID(0L, 0L);
    private static final int[] ANNOTATION_COLORS = {
        0xFFE74C3C, 0xFFE67E22, 0xFFF1C40F, 0xFF2ECC71,
        0xFF1ABC9C, 0xFF3498DB, 0xFF9B59B6, 0xFFECF0F1
    };
    /** Radar markers are ~12px across including their contour (see RadarMarkerRenderer); cull with that margin so one straddling the edge doesn't pop. */
    private static final float RADAR_CULL_MARGIN = 8f;

    /** Null when MaliLib owns the binding and closes this screen through the shared action handler. */
    private final KeyBinding openMapKey;
    private final GameBridge gameBridge;
    private final MapWorldService mapWorlds;
    private final TileService tiles;
    private final TileTextureManager textures;
    private final DaylightModel daylightModel;
    private final PredictionState predictionState;
    private final PredictionTileService predictionTiles;
    private final FullscreenMapViewState viewState;
    private final LayerSelector layerSelector;
    private final WaypointService waypointService;
    private final AnnotationService annotationService;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final ConfluxConfig config;
    private final SharedWaypointClient sharedWaypoints;
    private final CompanionSession companion;
    private final ChunkLoadStateClient chunkLoadStates;
    private final EntityRadarScanner radarScanner;
    private final EntityIconManager radarIconManager;
    private final RadarViewRange radarViewRange;
    private final StructureMarkerService structureMarkers;
    private final UpdateCheckService updateCheck;
    private final ClientGroundTeleportService groundTeleport;

    /** World point currently at screen center, and blocks-per-pixel; all mutable, panned/zoomed by input. */
    private double centerX;
    private double centerZ;
    private double scale;

    /** Recomputed every frame by {@link #drawWaypoints} - the marker nearest the cursor within {@link #HOVER_RADIUS_PX}, or none. */
    private WaypointRenderEntry hoveredWaypoint;
    private StructureIndex.Marker hoveredStructure;

    /** Cursor position at the last left-button press, so {@link #mouseReleased} can tell a click from a pan drag. */
    private double leftPressX;
    private double leftPressY;
    private boolean mapPointerPress;
    private SharedWaypointAvailability sharedAvailability;
    private MapIconButton localVisibilityButton;
    private MapIconButton sharedVisibilityButton;
    private MapIconButton manageWaypointsButton;
    private MapIconButton structureSearchButton;
    private MapIconButton displayModeButton;
    private int waypointControlsBottom;
    private FullscreenMapLocationMenu.Bounds locationMenuBounds;
    private FullscreenMapLocationMenu.Target locationMenuTarget;
    private FullscreenMapLocationMenu.Action pendingLocationAction;
    private ButtonWidget setWaypointLocationButton;
    private ButtonWidget shareLocationButton;
    private ButtonWidget teleportLocationButton;
    private final Map<ButtonWidget, String> annotationTooltips = new LinkedHashMap<>();
    private final Map<AnnotationTool, ButtonWidget> annotationToolButtons = new EnumMap<>(AnnotationTool.class);
    private AnnotationToolbarBounds annotationToolbarBounds;
    private AnnotationToolbarBounds annotationColorMenuBounds;
    private MapIconButton annotationPersistenceButton;
    private MapIconButton annotationEraserButton;
    private ButtonWidget annotationLabelButton;
    private ButtonWidget annotationUndoButton;
    private ButtonWidget annotationRedoButton;
    private FullscreenDisplayMode controlsDisplayMode;
    private boolean annotationColorMenuOpen;
    private AnnotationTool annotationTool = AnnotationTool.SELECT;
    private AnnotationPersistence newAnnotationPersistence = AnnotationPersistence.PERSISTENT;
    private int newAnnotationColor = ANNOTATION_COLORS[5];
    private UUID selectedAnnotationId;
    private AnnotationDraft annotationDraft;
    private Annotation movingAnnotation;
    private boolean annotationPointerPress;
    private double annotationMoveDx;
    private double annotationMoveDz;
    private final Set<UUID> erasingAnnotationIds = new LinkedHashSet<>();
    private long lastEraserButtonClickMs = Long.MIN_VALUE;
    private ButtonWidget loadStateDetailButton;

    public FullscreenMapScreen(final KeyBinding openMapKey) {
        super(Texts.translatable("confluxmap.screen.map.title"));
        this.openMapKey = openMapKey;
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
        this.radarIconManager = app.entityIconManager();
        this.radarViewRange = app.radarViewRange();
        this.structureMarkers = app.structureMarkerService();
        this.updateCheck = app.updateCheck();
        this.groundTeleport = app.groundTeleportService();

        final DimensionId dimension = gameBridge.session().dimension();
        final Optional<PlayerView> player = gameBridge.player();
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

    @Override
    protected void init() {
        locationMenuBounds = null;
        locationMenuTarget = null;
        pendingLocationAction = null;
        rebuildWaypointControls();
    }

    private void rebuildWaypointControls() {
        clearChildren();
        sharedAvailability = sharedWaypoints.availability();
        localVisibilityButton = null;
        sharedVisibilityButton = null;
        manageWaypointsButton = null;
        setWaypointLocationButton = null;
        shareLocationButton = null;
        teleportLocationButton = null;
        structureSearchButton = null;
        annotationTooltips.clear();
        annotationToolButtons.clear();
        annotationPersistenceButton = null;
        annotationEraserButton = null;
        annotationLabelButton = null;
        annotationUndoButton = null;
        annotationRedoButton = null;
        annotationToolbarBounds = null;
        annotationColorMenuBounds = null;
        displayModeButton = null;
        controlsDisplayMode = null;
        loadStateDetailButton = null;

        final int x = width - MARGIN - CONTROL_SIZE;
        int y = MARGIN + this.textRenderer.fontHeight + 5;
        displayModeButton = addDrawableChild(new MapIconButton(
            x,
            y,
            DisplayModeIconCatalog.icon(displayMode()),
            displayModeTooltip(),
            0,
            ignored -> cycleDisplayMode()
        ));
        controlsDisplayMode = displayMode();
        refreshDisplayModeButton();
        y += CONTROL_SIZE + CONTROL_GAP;
        localVisibilityButton = addDrawableChild(new MapIconButton(
            x, y, LOCAL_WAYPOINT_ICON, LOCAL_CONTROL_ACCENT, b -> {
                config.localWaypointsVisible = !config.localWaypointsVisible;
                ConfluxMapClient.get().configIo().save(config);
                localVisibilityButton.setSelected(config.localWaypointsVisible);
            }
        ));
        localVisibilityButton.setSelected(config.localWaypointsVisible);
        y += CONTROL_SIZE + CONTROL_GAP;
        if (sharedAvailability.visible()) {
            sharedVisibilityButton = addDrawableChild(new MapIconButton(
                x, y, SHARED_WAYPOINT_ICON, SHARED_CONTROL_ACCENT, b -> {
                    config.sharedWaypointsVisible = !config.sharedWaypointsVisible;
                    ConfluxMapClient.get().configIo().save(config);
                    sharedVisibilityButton.setSelected(config.sharedWaypointsVisible);
                }
            ));
            sharedVisibilityButton.setSelected(config.sharedWaypointsVisible);
            sharedVisibilityButton.active = sharedAvailability.ready();
            y += CONTROL_SIZE + CONTROL_GAP;
        }
        manageWaypointsButton = addDrawableChild(new MapIconButton(
            x, y, MANAGE_WAYPOINT_ICON, 0,
            b -> MinecraftAccess.setScreen(MinecraftClient.getInstance(),
                new WaypointListScreen(this, WaypointListScreen.Tab.LOCAL)
            )
        ));
        y += CONTROL_SIZE + CONTROL_GAP;
        structureSearchButton = addDrawableChild(new MapIconButton(
            x, y,
            STRUCTURE_SEARCH_ICON,
            Texts.translatable("confluxmap.map.structure_search"),
            0,
            ignored -> openStructureSearch()
        ));
        refreshStructureSearchButton();
        waypointControlsBottom = y + CONTROL_SIZE;
        rebuildAnnotationControls();
        addLoadStateDetailControl();
        if (locationMenuBounds != null && locationMenuTarget != null) {
            addLocationMenuButtons();
        }
    }

    private void rebuildAnnotationControls() {
        final int controlCount = AnnotationTool.values().length + 5;
        final int stride = ANNOTATION_CONTROL_SIZE + ANNOTATION_CONTROL_GAP;
        final int desiredTop = waypointControlsBottom + CONTROL_GAP;
        final int maxColumns = Math.max(1, (width - MARGIN * 2 + ANNOTATION_CONTROL_GAP) / stride);
        final int minimumRows = (controlCount + maxColumns - 1) / maxColumns;
        final int availableRows = Math.max(
            1, (height - MARGIN - desiredTop + ANNOTATION_CONTROL_GAP) / stride
        );
        final int rows = Math.min(
            ANNOTATION_MAX_ROWS, Math.max(minimumRows, Math.min(ANNOTATION_MAX_ROWS, availableRows))
        );
        final int columns = (controlCount + rows - 1) / rows;
        final int toolbarWidth = columns * stride - ANNOTATION_CONTROL_GAP;
        final int toolbarHeight = Math.min(rows, controlCount) * stride - ANNOTATION_CONTROL_GAP;
        final boolean fitsBelowWaypoints = desiredTop + toolbarHeight <= height - MARGIN;
        final int top = fitsBelowWaypoints
            ? desiredTop
            : Math.max(MARGIN, height - MARGIN - toolbarHeight);
        final int left = fitsBelowWaypoints
            ? Math.max(MARGIN, width - MARGIN - toolbarWidth)
            : MARGIN;
        annotationToolbarBounds = new AnnotationToolbarBounds(left, top, toolbarWidth, toolbarHeight);

        int index = 0;
        for (final AnnotationTool tool : AnnotationTool.values()) {
            final int buttonX = controlX(left, stride, rows, index);
            final int buttonY = controlY(top, stride, rows, index);
            final ButtonWidget button;
            if (tool == AnnotationTool.ERASER) {
                annotationEraserButton = addDrawableChild(new MapIconButton(
                    buttonX,
                    buttonY,
                    ANNOTATION_CONTROL_SIZE,
                    ANNOTATION_ERASER_ICON,
                    Texts.literal(""),
                    LOCAL_CONTROL_ACCENT,
                    ignored -> selectAnnotationTool(tool)
                ));
                annotationTooltips.put(annotationEraserButton, toolTooltip(tool));
                button = annotationEraserButton;
            } else {
                button = addAnnotationButton(
                    buttonX,
                    buttonY,
                    toolGlyph(tool),
                    toolTooltip(tool),
                    ignored -> selectAnnotationTool(tool)
                );
            }
            annotationToolButtons.put(tool, button);
            index++;
        }
        addColorButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            selectedAnnotationColor(),
            true
        );
        index++;
        annotationPersistenceButton = addDrawableChild(new MapIconButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            ANNOTATION_CONTROL_SIZE,
            ANNOTATION_PERSISTENCE_ICON,
            Texts.literal(""),
            LOCAL_CONTROL_ACCENT,
            ignored -> toggleAnnotationPersistence()
        ));
        annotationTooltips.put(
            annotationPersistenceButton, "confluxmap.map.annotation.persistence.tooltip"
        );
        index++;
        annotationLabelButton = addAnnotationButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            Texts.literal("T"),
            "confluxmap.map.annotation.label.tooltip",
            ignored -> editSelectedAnnotationLabel()
        );
        index++;
        annotationUndoButton = addAnnotationButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            Texts.literal("↶"),
            "confluxmap.map.annotation.undo.tooltip",
            ignored -> undoAnnotationChange()
        );
        index++;
        annotationRedoButton = addAnnotationButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            Texts.literal("↷"),
            "confluxmap.map.annotation.redo.tooltip",
            ignored -> redoAnnotationChange()
        );
        if (annotationColorMenuOpen) {
            rebuildAnnotationColorMenu();
        }
        refreshAnnotationControls();
    }

    private void rebuildAnnotationColorMenu() {
        final int stride = ANNOTATION_CONTROL_SIZE + ANNOTATION_CONTROL_GAP;
        final int rows = (ANNOTATION_COLORS.length + ANNOTATION_COLOR_MENU_COLUMNS - 1)
            / ANNOTATION_COLOR_MENU_COLUMNS;
        final int menuWidth = ANNOTATION_COLOR_MENU_COLUMNS * stride - ANNOTATION_CONTROL_GAP;
        final int menuHeight = rows * stride - ANNOTATION_CONTROL_GAP;
        final int leftCandidate = annotationToolbarBounds.x() - ANNOTATION_CONTROL_GAP - menuWidth;
        final int rightCandidate = annotationToolbarBounds.x()
            + annotationToolbarBounds.width() + ANNOTATION_CONTROL_GAP;
        final int left = leftCandidate >= MARGIN
            ? leftCandidate
            : rightCandidate + menuWidth <= width - MARGIN ? rightCandidate : MARGIN;
        final int top = Math.max(
            MARGIN, Math.min(annotationToolbarBounds.y(), height - MARGIN - menuHeight)
        );
        annotationColorMenuBounds = new AnnotationToolbarBounds(left, top, menuWidth, menuHeight);
        for (int index = 0; index < ANNOTATION_COLORS.length; index++) {
            addColorButton(
                left + index % ANNOTATION_COLOR_MENU_COLUMNS * stride,
                top + index / ANNOTATION_COLOR_MENU_COLUMNS * stride,
                ANNOTATION_COLORS[index],
                false
            );
        }
    }

    private static int controlX(final int left, final int stride, final int rows, final int index) {
        return left + index / rows * stride;
    }

    private static int controlY(final int top, final int stride, final int rows, final int index) {
        return top + index % rows * stride;
    }

    private ButtonWidget addAnnotationButton(
        final int x,
        final int y,
        final Text label,
        final String tooltipKey,
        final ButtonWidget.PressAction action
    ) {
        final ButtonWidget button = addDrawableChild(Widgets.button(
            x, y, ANNOTATION_CONTROL_SIZE, ANNOTATION_CONTROL_SIZE, label, action
        ));
        annotationTooltips.put(button, tooltipKey);
        return button;
    }

    private void addColorButton(
        final int x,
        final int y,
        final int color,
        final boolean opensMenu
    ) {
        //#if MC>=260100
        //$$ final var button = addRenderableWidget(new Button(
        //$$     x, y, ANNOTATION_CONTROL_SIZE, ANNOTATION_CONTROL_SIZE, Texts.literal(""),
        //$$     ignored -> activateAnnotationColorButton(color, opensMenu), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void extractContents(
        //$$         final GuiGraphicsExtractor context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#elseif MC>=12111
        //$$ final ButtonWidget button = addDrawableChild(new ButtonWidget(
        //$$     x, y, ANNOTATION_CONTROL_SIZE, ANNOTATION_CONTROL_SIZE, Texts.literal(""),
        //$$     ignored -> activateAnnotationColorButton(color, opensMenu), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void drawIcon(
        //$$         final DrawContext context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#elseif MC>=11904
        //$$ final ButtonWidget button = addDrawableChild(new ButtonWidget(
        //$$     x, y, ANNOTATION_CONTROL_SIZE, ANNOTATION_CONTROL_SIZE, Texts.literal(""),
        //$$     ignored -> activateAnnotationColorButton(color, opensMenu), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void renderWidget(
        //$$         final DrawContext context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#else
        final ButtonWidget button = addDrawableChild(new ButtonWidget(
            x, y, ANNOTATION_CONTROL_SIZE, ANNOTATION_CONTROL_SIZE, Texts.literal(""),
            ignored -> activateAnnotationColorButton(color, opensMenu)
        ) {
            @Override
            public void renderButton(
                final MatrixStack matrices,
                final int mouseX,
                final int mouseY,
                final float delta
            ) {
                renderAnnotationColorButton(GuiDraw.of(matrices), this, color, opensMenu);
            }
        });
        //#endif
        annotationTooltips.put(button, "confluxmap.map.annotation.color.tooltip");
    }

    private void renderAnnotationColorButton(
        final GuiDraw draw,
        final ButtonWidget button,
        final int color,
        final boolean opensMenu
    ) {
        final int x = Widgets.x(button);
        final int y = Widgets.y(button);
        final int displayedColor = opensMenu ? selectedAnnotationColor() : color;
        RenderUtil.fillRect(
            draw.matrices(), x, y, button.getWidth(), button.getHeight(), displayedColor | 0xFF000000
        );
        if (opensMenu ? annotationColorMenuOpen : color == selectedAnnotationColor()) {
            RenderUtil.fillRect(draw.matrices(), x, y, button.getWidth(), 2, 0xFFFFFFFF);
            RenderUtil.fillRect(draw.matrices(), x, y + button.getHeight() - 2, button.getWidth(), 2, 0xFFFFFFFF);
            RenderUtil.fillRect(draw.matrices(), x, y, 2, button.getHeight(), 0xFFFFFFFF);
            RenderUtil.fillRect(draw.matrices(), x + button.getWidth() - 2, y, 2, button.getHeight(), 0xFFFFFFFF);
        }
    }

    private void refreshAnnotationControls() {
        for (final Map.Entry<AnnotationTool, ButtonWidget> entry : annotationToolButtons.entrySet()) {
            entry.getValue().active = entry.getKey() == AnnotationTool.ERASER
                || entry.getKey() != annotationTool;
        }
        final boolean selected = selectedAnnotation().isPresent();
        if (annotationLabelButton != null) {
            annotationLabelButton.active = selected;
        }
        if (annotationEraserButton != null) {
            annotationEraserButton.setSelected(annotationTool == AnnotationTool.ERASER);
        }
        if (annotationPersistenceButton != null) {
            annotationPersistenceButton.setSelected(
                selectedAnnotationPersistence() == AnnotationPersistence.PERSISTENT
            );
        }
        final AnnotationStore store = annotationService.current();
        if (annotationUndoButton != null) {
            annotationUndoButton.active = store != null && store.canUndo();
        }
        if (annotationRedoButton != null) {
            annotationRedoButton.active = store != null && store.canRedo();
        }
    }

    private static Text toolGlyph(final AnnotationTool tool) {
        return Texts.literal(switch (tool) {
            case SELECT -> "↖";
            case LINE -> "/";
            case CIRCLE -> "○";
            case RECTANGLE -> "□";
            case FREEHAND -> "~";
            case ERASER -> "⌫";
        });
    }

    private static String toolTooltip(final AnnotationTool tool) {
        return switch (tool) {
            case SELECT -> "confluxmap.map.annotation.select.tooltip";
            case LINE -> "confluxmap.map.annotation.line.tooltip";
            case CIRCLE -> "confluxmap.map.annotation.circle.tooltip";
            case RECTANGLE -> "confluxmap.map.annotation.rectangle.tooltip";
            case FREEHAND -> "confluxmap.map.annotation.freehand.tooltip";
            case ERASER -> "confluxmap.map.annotation.eraser.tooltip";
        };
    }

    private Optional<Annotation> selectedAnnotation() {
        final AnnotationStore store = annotationService.current();
        return store == null || selectedAnnotationId == null
            ? Optional.empty()
            : store.get(selectedAnnotationId);
    }

    private AnnotationPersistence selectedAnnotationPersistence() {
        return selectedAnnotation().map(Annotation::persistence).orElse(newAnnotationPersistence);
    }

    private int selectedAnnotationColor() {
        return selectedAnnotation().map(annotation -> annotation.style().colorArgb()).orElse(newAnnotationColor);
    }

    private void selectAnnotationTool(final AnnotationTool tool) {
        final long now = Util.getMeasuringTimeMs();
        final boolean openEraserSettings = tool == AnnotationTool.ERASER
            && lastEraserButtonClickMs != Long.MIN_VALUE
            && now - lastEraserButtonClickMs <= DOUBLE_CLICK_INTERVAL_MS;
        lastEraserButtonClickMs = tool == AnnotationTool.ERASER ? now : Long.MIN_VALUE;
        annotationTool = tool;
        annotationDraft = null;
        movingAnnotation = null;
        annotationPointerPress = false;
        erasingAnnotationIds.clear();
        annotationColorMenuOpen = false;
        rebuildWaypointControls();
        if (openEraserSettings) {
            lastEraserButtonClickMs = Long.MIN_VALUE;
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new AnnotationEraserSettingsScreen(this, config));
        }
    }

    private void activateAnnotationColorButton(final int color, final boolean opensMenu) {
        if (opensMenu) {
            annotationColorMenuOpen = !annotationColorMenuOpen;
        } else {
            selectAnnotationColor(color);
            annotationColorMenuOpen = false;
        }
        rebuildWaypointControls();
    }

    private void selectAnnotationColor(final int color) {
        newAnnotationColor = color;
        final AnnotationStore store = annotationService.current();
        if (store != null) {
            selectedAnnotation().ifPresent(annotation -> store.update(
                annotation.withStyle(new AnnotationStyle(color))
            ));
        }
    }

    private void undoAnnotationChange() {
        final AnnotationStore store = annotationService.current();
        if (store != null && store.undo()) {
            clearMissingSelection(store);
            refreshAnnotationControls();
        }
    }

    private void redoAnnotationChange() {
        final AnnotationStore store = annotationService.current();
        if (store != null && store.redo()) {
            clearMissingSelection(store);
            refreshAnnotationControls();
        }
    }

    private void clearMissingSelection(final AnnotationStore store) {
        if (selectedAnnotationId != null && store.get(selectedAnnotationId).isEmpty()) {
            selectedAnnotationId = null;
        }
    }

    private void toggleAnnotationPersistence() {
        final AnnotationPersistence next = selectedAnnotationPersistence() == AnnotationPersistence.PERSISTENT
            ? AnnotationPersistence.TRANSIENT
            : AnnotationPersistence.PERSISTENT;
        final AnnotationStore store = annotationService.current();
        final Optional<Annotation> selected = selectedAnnotation();
        if (store != null && selected.isPresent()) {
            store.update(selected.get().withPersistence(next));
        } else {
            newAnnotationPersistence = next;
        }
        refreshAnnotationControls();
    }

    private void editSelectedAnnotationLabel() {
        final AnnotationStore store = annotationService.current();
        if (store == null) {
            return;
        }
        selectedAnnotation().ifPresent(annotation -> MinecraftAccess.setScreen(MinecraftClient.getInstance(),
            new AnnotationLabelScreen(this, store, annotation)
        ));
    }

    private void addLoadStateDetailControl() {
        if (!loadStateMode()) {
            return;
        }
        final int buttonWidth = Math.min(180, Math.max(90, width - 2 * (MARGIN + 80)));
        final int x = (width - buttonWidth) / 2;
        loadStateDetailButton = addDrawableChild(Widgets.button(
            x,
            MARGIN,
            buttonWidth,
            20,
            loadStateDetailLabel(),
            ignored -> {
                config.chunkLoadDetailMode = config.chunkLoadDetailMode == ChunkLoadDetailMode.BANDS
                    ? ChunkLoadDetailMode.EXACT
                    : ChunkLoadDetailMode.BANDS;
                ConfluxMapClient.get().configIo().save(config);
                loadStateDetailButton.setMessage(loadStateDetailLabel());
            }
        ));
    }

    private void cycleDisplayMode() {
        final FullscreenDisplayMode current = displayMode();
        config.fullscreenDisplayMode = current.next(
            chunkLoadStates.available(), companion.biomeMapAllowed()
        );
        if (current == FullscreenDisplayMode.CHUNK_LOAD_STATE
            && config.fullscreenDisplayMode != FullscreenDisplayMode.CHUNK_LOAD_STATE) {
            chunkLoadStates.deactivate();
        }
        ConfluxMapClient.get().configIo().save(config);
        rebuildWaypointControls();
    }

    private void addLocationMenuButtons() {
        final boolean heightKnown = locationMenuTarget.blockY().isPresent();
        final MinecraftClient client = MinecraftClient.getInstance();
        final boolean playerPresent = client.player != null;
        final boolean teleportCommandAvailable = MinecraftAccess.canSendCommand(client, "teleport", "tp");
        for (int index = 0; index < FullscreenMapLocationMenu.actions().size(); index++) {
            final FullscreenMapLocationMenu.Action action = FullscreenMapLocationMenu.actions().get(index);
            final ButtonWidget button = addDrawableChild(Widgets.button(
                locationMenuBounds.buttonX(),
                locationMenuBounds.buttonY(index),
                locationMenuBounds.buttonWidth(),
                FullscreenMapLocationMenu.BUTTON_HEIGHT,
                Texts.translatable(action.translationKey()),
                ignored -> pendingLocationAction = action
            ));
            button.active = FullscreenMapLocationMenu.actionEnabled(
                action, playerPresent, heightKnown, teleportCommandAvailable
            );
            switch (action) {
                case SET_WAYPOINT -> setWaypointLocationButton = button;
                case SHARE_LOCATION -> shareLocationButton = button;
                case TELEPORT -> teleportLocationButton = button;
            }
        }
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
        if (loadStateMode() != (loadStateDetailButton != null)) {
            rebuildWaypointControls();
            return;
        }
        refreshDisplayModeButton();
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (sharedAvailability == null || availability.visible() != sharedAvailability.visible()) {
            rebuildWaypointControls();
            return;
        }
        sharedAvailability = availability;
        if (sharedVisibilityButton != null) {
            sharedVisibilityButton.active = availability.ready();
        }
        if (locationMenuTarget != null && locationMenuTarget.blockY().isEmpty()) {
            final OptionalInt surfaceY = surfaceYAt(
                locationMenuTarget.blockX(), locationMenuTarget.blockZ()
            );
            if (surfaceY.isPresent()) {
                locationMenuTarget = FullscreenMapLocationMenu.targetAt(
                    locationMenuTarget.blockX(), surfaceY, locationMenuTarget.blockZ()
                );
                rebuildWaypointControls();
            }
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

    /** Keep the world (and this session's capture pipeline) running while the map is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or M below). */
    @Override
    public void onClose() {
        viewState.rememberScale(gameBridge.session().dimension(), scale);
        chunkLoadStates.deactivate();
        tiles.clearViewport();
        predictionTiles.clearViewport();
        ConfluxMapClient.get().mapSyncClient().clearViewport();
        structureMarkers.flush();
        super.onClose();
    }

    @Override
    public void removed() {
        chunkLoadStates.deactivate();
        ConfluxMapClient.get().mapSyncClient().clearViewport();
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
        final boolean controlDown = (modifiers & (GLFW.GLFW_MOD_CONTROL | GLFW.GLFW_MOD_SUPER)) != 0;
        final boolean shiftDown = (modifiers & GLFW.GLFW_MOD_SHIFT) != 0;
        if (controlDown && keyCode == GLFW.GLFW_KEY_Z) {
            if (shiftDown) {
                redoAnnotationChange();
            } else {
                undoAnnotationChange();
            }
            return true;
        }
        if (controlDown && keyCode == GLFW.GLFW_KEY_Y) {
            redoAnnotationChange();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_F9) {
            ConfluxMapClient.get().reloadPredictionTiles();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && annotationColorMenuOpen) {
            annotationColorMenuOpen = false;
            rebuildWaypointControls();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE && locationMenuBounds != null) {
            dismissLocationMenu();
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
        if (locationMenuBounds != null) {
            if (locationMenuBounds.contains(mouseX, mouseY)) {
                //#if MC>=12109
                //$$ super.mouseClicked(click, doubledClick);
                //#else
                super.mouseClicked(mouseX, mouseY, button);
                //#endif
                mapPointerPress = false;
                performPendingLocationAction();
                return true;
            }
            if (button == 1 && !isOverMapControls(mouseX, mouseY)) {
                openLocationMenu(mouseX, mouseY);
                return true;
            }
            dismissLocationMenu();
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
        if (annotationColorMenuOpen) {
            annotationColorMenuOpen = false;
            rebuildWaypointControls();
            mapPointerPress = false;
            return true;
        }
        if (isOverMapControls(mouseX, mouseY)) {
            mapPointerPress = false;
            return true;
        }
        if (button == 0 && beginAnnotationPointer(mouseX, mouseY)) {
            return true;
        }
        if (button == 1) {
            openLocationMenu(mouseX, mouseY);
            return true;
        }
        if (button == 0) {
            leftPressX = mouseX;
            leftPressY = mouseY;
            mapPointerPress = true;
            return true;
        }
        return false;
    }

    private boolean beginAnnotationPointer(final double mouseX, final double mouseY) {
        final AnnotationStore store = annotationService.current();
        if (store == null) {
            return false;
        }
        final AnnotationPoint worldPoint = annotationWorldPoint(mouseX, mouseY);
        if (annotationTool == AnnotationTool.SELECT) {
            final Optional<Annotation> hit = store.hit(
                gameBridge.session().dimension(), worldPoint, ANNOTATION_HIT_TOLERANCE_PX * scale
            );
            if (hit.isEmpty()) {
                selectedAnnotationId = null;
                refreshAnnotationControls();
                return false;
            }
            selectedAnnotationId = hit.get().id();
            movingAnnotation = hit.get();
            annotationMoveDx = 0.0;
            annotationMoveDz = 0.0;
            annotationPointerPress = true;
            leftPressX = mouseX;
            leftPressY = mouseY;
            mapPointerPress = false;
            refreshAnnotationControls();
            return true;
        }
        if (annotationTool == AnnotationTool.ERASER) {
            annotationPointerPress = true;
            annotationDraft = null;
            movingAnnotation = null;
            erasingAnnotationIds.clear();
            collectEraserHits(store, worldPoint);
            mapPointerPress = false;
            return true;
        }
        annotationDraft = new AnnotationDraft(annotationTool, worldPoint);
        annotationPointerPress = true;
        movingAnnotation = null;
        leftPressX = mouseX;
        leftPressY = mouseY;
        mapPointerPress = false;
        return true;
    }

    private void collectEraserHits(
        final AnnotationStore store,
        final AnnotationPoint worldPoint
    ) {
        final double radius = config.annotationEraserSize / 2.0 * scale;
        for (final Annotation annotation : store.hits(
            gameBridge.session().dimension(), worldPoint, radius
        )) {
            erasingAnnotationIds.add(annotation.id());
        }
    }

    private void collectEraserStroke(
        final AnnotationStore store,
        final double mouseX,
        final double mouseY,
        final double deltaX,
        final double deltaY
    ) {
        final double distance = Math.hypot(deltaX, deltaY);
        final double sampleSpacing = Math.max(1.0, config.annotationEraserSize / 4.0);
        final int samples = Math.max(1, (int) Math.ceil(distance / sampleSpacing));
        for (int index = 0; index <= samples; index++) {
            final double progress = index / (double) samples;
            collectEraserHits(store, annotationWorldPoint(
                mouseX - deltaX + deltaX * progress,
                mouseY - deltaY + deltaY * progress
            ));
        }
    }

    private AnnotationPoint annotationWorldPoint(final double mouseX, final double mouseY) {
        return new AnnotationPoint(
            centerX + (mouseX - width / 2.0) * scale,
            centerZ + (mouseY - height / 2.0) * scale
        );
    }

    private void openLocationMenu(final double mouseX, final double mouseY) {
        annotationColorMenuOpen = false;
        final double worldX = centerX + (mouseX - width / 2.0) * scale;
        final double worldZ = centerZ + (mouseY - height / 2.0) * scale;
        final int blockX = (int) Math.floor(worldX);
        final int blockZ = (int) Math.floor(worldZ);
        final int menuViewportWidth = width - MARGIN - CONTROL_SIZE - CONTROL_GAP;
        locationMenuBounds = FullscreenMapLocationMenu.place(
            (int) Math.floor(mouseX),
            (int) Math.floor(mouseY),
            menuViewportWidth,
            height
        );
        locationMenuTarget = FullscreenMapLocationMenu.targetAt(
            worldX, surfaceYAt(blockX, blockZ), worldZ
        );
        pendingLocationAction = null;
        mapPointerPress = false;
        rebuildWaypointControls();
    }

    private OptionalInt surfaceYAt(final int blockX, final int blockZ) {
        final MapLayer visibleLayer = layerSelector.current().layer();
        final ClientWorld world = this.client.world;
        final MapLayer surfaceLayer = world == null
            ? visibleLayer
            : FullscreenMapLocationMenu.topSurfaceLayer(LayerSelector.classify(world.getDimension()));
        final MapWorld mapWorld = mapWorlds.current();
        if (mapWorld != null) {
            final OptionalInt captured = mapWorld.store(surfaceLayer).surfaceYAt(blockX, blockZ);
            if (captured.isPresent()) {
                return captured;
            }
        }
        final SessionGuard.Session session = gameBridge.session();
        return surfaceLayer.equals(visibleLayer) && predictionActive(surfaceLayer, session)
            ? predictionTiles.predictedSurfaceYAt(session.dimension(), currentLod(), blockX, blockZ)
            : OptionalInt.empty();
    }

    private void performPendingLocationAction() {
        final FullscreenMapLocationMenu.Action action = pendingLocationAction;
        final FullscreenMapLocationMenu.Target target = locationMenuTarget;
        if (action == null || target == null) {
            return;
        }
        dismissLocationMenu();
        switch (action) {
            case SET_WAYPOINT -> target.blockY().ifPresent(y -> MinecraftAccess.setScreen(MinecraftClient.getInstance(),
                WaypointEditScreen.forCreate(
                    this, gameBridge.session().dimension(), target.blockX(), y, target.blockZ()
                )
            ));
            case SHARE_LOCATION -> {
                if (target.blockY().isPresent()) {
                    shareTemporaryLocation(target);
                }
            }
            case TELEPORT -> groundTeleport.teleport(target.blockX(), target.blockZ(), target.blockY());
        }
    }

    private void shareTemporaryLocation(final FullscreenMapLocationMenu.Target target) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null || target.blockY().isEmpty()) {
            return;
        }
        final String name = Texts.translatable("confluxmap.map.location_menu.temporary_name").getString();
        try {
            final String confluxMessage = WaypointChatCodec.format(
                name,
                gameBridge.session().dimension(),
                target.blockX(),
                target.blockY().getAsInt(),
                target.blockZ()
            );
            final String xaeroMessage = WaypointChatCodec.formatXaero(
                name,
                gameBridge.session().dimension(),
                target.blockX(),
                target.blockY().getAsInt(),
                target.blockZ(),
                TEMPORARY_LOCATION_COLOR
            );
            MinecraftAccess.sendChatMessage(client, confluxMessage);
            MinecraftAccess.sendChatMessage(client, xaeroMessage);
        } catch (final IllegalArgumentException e) {
            //#if MC>=260100
            //$$ client.player.sendSystemMessage(Texts.translatable("confluxmap.screen.waypoint.invalid_share"));
            //#else
            client.player.sendMessage(Texts.translatable("confluxmap.screen.waypoint.invalid_share"), false);
            //#endif
        }
    }

    private void dismissLocationMenu() {
        locationMenuBounds = null;
        locationMenuTarget = null;
        pendingLocationAction = null;
        rebuildWaypointControls();
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
        if (button == 0 && annotationPointerPress) {
            commitAnnotationPointer(mouseX, mouseY);
            return true;
        }
        if (button != 0 || !mapPointerPress) {
            //#if MC>=12109
            //$$ return super.mouseReleased(click);
            //#else
            return super.mouseReleased(mouseX, mouseY, button);
            //#endif
        }
        mapPointerPress = false;
        if (hoveredWaypoint != null
            && !isOverMapControls(mouseX, mouseY)
            && Math.hypot(mouseX - leftPressX, mouseY - leftPressY) < CLICK_DRAG_TOLERANCE_PX) {
            openWaypoint(hoveredWaypoint);
            return true;
        }
        return true;
    }

    private void commitAnnotationPointer(final double mouseX, final double mouseY) {
        annotationPointerPress = false;
        final AnnotationStore store = annotationService.current();
        if (store == null) {
            movingAnnotation = null;
            annotationDraft = null;
            erasingAnnotationIds.clear();
            return;
        }
        if (annotationTool == AnnotationTool.ERASER) {
            collectEraserHits(store, annotationWorldPoint(mouseX, mouseY));
            if (store.removeAll(erasingAnnotationIds) > 0) {
                if (selectedAnnotationId != null && erasingAnnotationIds.contains(selectedAnnotationId)) {
                    selectedAnnotationId = null;
                }
                refreshAnnotationControls();
            }
            erasingAnnotationIds.clear();
            return;
        }
        if (movingAnnotation != null) {
            if (annotationMoveDx != 0.0 || annotationMoveDz != 0.0) {
                store.update(movingAnnotation.withGeometry(
                    movingAnnotation.geometry().translate(annotationMoveDx, annotationMoveDz)
                ));
            }
            movingAnnotation = null;
            annotationMoveDx = 0.0;
            annotationMoveDz = 0.0;
            return;
        }
        if (annotationDraft != null) {
            annotationDraft.dragTo(annotationWorldPoint(mouseX, mouseY), scale);
            final Optional<AnnotationGeometry> geometry = annotationDraft.geometry(scale, true);
            annotationDraft = null;
            if (geometry.isPresent()) {
                final Annotation created = new Annotation(
                    UUID.randomUUID(),
                    gameBridge.session().dimension(),
                    geometry.get(),
                    new AnnotationStyle(newAnnotationColor),
                    "",
                    newAnnotationPersistence,
                    System.currentTimeMillis()
                );
                if (store.add(created)) {
                    selectedAnnotationId = created.id();
                    refreshAnnotationControls();
                }
            }
        }
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
        if (button == 0 && annotationPointerPress) {
            if (annotationTool == AnnotationTool.ERASER) {
                final AnnotationStore store = annotationService.current();
                if (store != null) {
                    collectEraserStroke(store, mouseX, mouseY, deltaX, deltaY);
                }
            } else if (movingAnnotation != null) {
                annotationMoveDx = (mouseX - leftPressX) * scale;
                annotationMoveDz = (mouseY - leftPressY) * scale;
            } else if (annotationDraft != null) {
                annotationDraft.dragTo(annotationWorldPoint(mouseX, mouseY), scale);
            }
            return true;
        }
        if (button == 0 && mapPointerPress) {
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

    private boolean isOverMapControls(final double mouseX, final double mouseY) {
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
                this,
                waypoint.locked() ? WaypointListScreen.Tab.LOCKED : WaypointListScreen.Tab.PUBLIC
            ));
            return;
        }
        for (final Waypoint local : waypointService.list()) {
            if (local.id.equals(waypoint.id())) {
                MinecraftAccess.setScreen(MinecraftClient.getInstance(), WaypointEditScreen.forEdit(this, local));
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
        if (locationMenuBounds != null) {
            dismissLocationMenu();
            return true;
        }
        if (amount == 0) {
            return false;
        }
        final double oldScale = scale;
        final double newScale = MathHelper.clamp(oldScale * (amount > 0 ? 1.0 / ZOOM_STEP : ZOOM_STEP), MIN_SCALE, MAX_SCALE);
        if (newScale != oldScale) {
            // Cursor-anchored: keep the world point under the cursor fixed on screen.
            final double cursorWorldX = centerX + (mouseX - width / 2.0) * oldScale;
            final double cursorWorldZ = centerZ + (mouseY - height / 2.0) * oldScale;
            centerX = cursorWorldX - (mouseX - width / 2.0) * newScale;
            centerZ = cursorWorldZ - (mouseY - height / 2.0) * newScale;
            scale = newScale;
        }
        return true;
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        final MatrixStack matrices = draw.matrices();
        tiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));
        predictionTiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));
        // This screen owns radarViewRange while it's open (MinimapHudRenderer stops writing it -
        // see its render() javadoc).
        final Optional<PlayerView> radarObserver = gameBridge.player(tickDelta);
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
            drawChunkLoadStateOverlay(draw);
        }
        drawChunkGrid(matrices, mouseX, mouseY);
        drawAnnotations(draw, mouseX, mouseY);
        drawStructures(draw, mouseX, mouseY);
        drawRadar(draw, tickDelta);

        drawWaypoints(draw, mouseX, mouseY, radarObserver);
        drawPlayerMarker(matrices, tickDelta);
        drawDimensionLabel(draw);
        drawLayerLabel(draw);
        drawPredictionLabel(draw);
        drawServerSyncLabel(draw);
        if (loadStateMode()) {
            drawChunkLoadStateLegend(draw);
        }
        drawScaleLabel(draw);
        drawCursorCoords(draw, mouseX, mouseY);
        drawUpdateBadge(draw);
        drawLocationMenu(draw);
    }

    private void drawAnnotations(final GuiDraw draw, final int mouseX, final int mouseY) {
        final AnnotationStore store = annotationService.current();
        if (store == null) {
            return;
        }
        final List<Annotation> visible = new ArrayList<>(store.list(gameBridge.session().dimension()));
        visible.removeIf(annotation -> erasingAnnotationIds.contains(annotation.id()));
        if (movingAnnotation != null) {
            for (int index = 0; index < visible.size(); index++) {
                if (visible.get(index).id().equals(movingAnnotation.id())) {
                    visible.set(index, movingAnnotation.withGeometry(
                        movingAnnotation.geometry().translate(annotationMoveDx, annotationMoveDz)
                    ));
                    break;
                }
            }
        }
        if (annotationDraft != null) {
            annotationDraft.geometry(scale, false).ifPresent(geometry -> visible.add(new Annotation(
                ANNOTATION_DRAFT_ID,
                gameBridge.session().dimension(),
                geometry,
                new AnnotationStyle(newAnnotationColor),
                "",
                newAnnotationPersistence,
                System.currentTimeMillis()
            )));
        }
        final AnnotationProjection projection = new AnnotationProjection(
            centerX,
            centerZ,
            width / 2.0,
            height / 2.0,
            scale,
            0.0,
            width,
            height
        );
        AnnotationRenderer.drawGeometry(
            draw.matrices(),
            visible,
            projection,
            annotationDraft == null ? selectedAnnotationId : ANNOTATION_DRAFT_ID
        );
        AnnotationRenderer.drawLabels(
            draw,
            this.textRenderer,
            visible,
            projection,
            0,
            0,
            width,
            height,
            AnnotationRenderer.ClipShape.RECTANGLE
        );
        if (annotationTool == AnnotationTool.ERASER
            && locationMenuBounds == null
            && !isOverMapControls(mouseX, mouseY)) {
            RenderUtil.drawRing(
                draw.matrices(),
                mouseX,
                mouseY,
                config.annotationEraserSize / 2.0f,
                1.5f,
                0xE6FFFFFF
            );
        }
    }

    private FullscreenDisplayMode displayMode() {
        if (config.fullscreenDisplayMode == FullscreenDisplayMode.CHUNK_LOAD_STATE
            && !chunkLoadStates.available()) {
            return FullscreenDisplayMode.TERRAIN;
        }
        if (config.fullscreenDisplayMode == FullscreenDisplayMode.BIOME
            && !companion.biomeMapAllowed()) {
            return FullscreenDisplayMode.TERRAIN;
        }
        return config.fullscreenDisplayMode;
    }

    private boolean loadStateMode() {
        return displayMode() == FullscreenDisplayMode.CHUNK_LOAD_STATE;
    }

    private boolean biomeMode() {
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
        displayModeButton.active = displayMode().next(
            chunkLoadStates.available(), companion.biomeMapAllowed()
        ) != displayMode();
    }

    private Text loadStateDetailLabel() {
        final String valueKey = config.chunkLoadDetailMode == ChunkLoadDetailMode.EXACT
            ? "confluxmap.map.load_state.detail.exact"
            : "confluxmap.map.load_state.detail.bands";
        return Texts.translatable(
            "confluxmap.map.load_state.detail",
            Texts.translatable(valueKey).getString()
        );
    }

    private void drawChunkLoadStateOverlay(final GuiDraw draw) {
        final MatrixStack matrices = draw.matrices();
        final int minChunkX = TileMath.blockToChunk(
            (int) Math.floor(centerX - width / 2.0 * scale)
        );
        final int maxChunkX = TileMath.blockToChunk(
            (int) Math.ceil(centerX + width / 2.0 * scale)
        );
        final int minChunkZ = TileMath.blockToChunk(
            (int) Math.floor(centerZ - height / 2.0 * scale)
        );
        final int maxChunkZ = TileMath.blockToChunk(
            (int) Math.ceil(centerZ + height / 2.0 * scale)
        );
        chunkLoadStates.reportViewport(
            gameBridge.session().dimension(), minChunkX, maxChunkX, minChunkZ, maxChunkZ
        );

        final float chunkSize = (float) ChunkScreenRect.chunkSize(scale);
        final ChunkLoadOverlayStyle style = ChunkLoadOverlayStyle.forChunkWidth(
            chunkSize, config.chunkLoadDetailMode
        );
        for (final LoadStateDeltaS2C.Entry entry : chunkLoadStates.snapshot().entries()) {
            if (entry.chunkX() < minChunkX || entry.chunkX() > maxChunkX
                || entry.chunkZ() < minChunkZ || entry.chunkZ() > maxChunkZ) {
                continue;
            }
            final ChunkScreenRect rect = ChunkScreenRect.forChunk(
                entry.chunkX(), entry.chunkZ(), centerX, centerZ, width, height, scale
            );
            final float screenX = (float) rect.x();
            final float screenY = (float) rect.y();
            RenderUtil.fillRect(
                matrices, screenX, screenY, chunkSize, chunkSize, loadStateColor(entry.band())
            );
            if (style.drawOutline()) {
                drawChunkLoadOutline(matrices, screenX, screenY, chunkSize);
            }
            if (style.drawLevelLabel()) {
                final String level = "L" + entry.level();
                final int textWidth = this.textRenderer.getWidth(level);
                draw.drawTextWithShadow(
                    this.textRenderer,
                    level,
                    screenX + (chunkSize - textWidth) / 2f,
                    screenY + (chunkSize - this.textRenderer.fontHeight) / 2f,
                    TEXT_COLOR
                );
            }
        }
    }

    private static int loadStateColor(final ChunkLoadBand band) {
        return switch (band) {
            case ENTITY_TICKING -> LOAD_STATE_ENTITY_COLOR;
            case BLOCK_TICKING -> LOAD_STATE_BLOCK_COLOR;
            case BORDER -> LOAD_STATE_BORDER_COLOR;
            case UNLOADED -> LOAD_STATE_UNLOADED_COLOR;
        };
    }

    private static void drawChunkLoadOutline(
        final MatrixStack matrices,
        final float x,
        final float y,
        final float size
    ) {
        RenderUtil.fillRect(matrices, x, y, size, 1f, LOAD_STATE_OUTLINE_COLOR);
        RenderUtil.fillRect(matrices, x, y + size - 1f, size, 1f, LOAD_STATE_OUTLINE_COLOR);
        RenderUtil.fillRect(matrices, x, y, 1f, size, LOAD_STATE_OUTLINE_COLOR);
        RenderUtil.fillRect(matrices, x + size - 1f, y, 1f, size, LOAD_STATE_OUTLINE_COLOR);
    }

    private void drawChunkLoadStateLegend(final GuiDraw draw) {
        final int rowHeight = this.textRenderer.fontHeight + 3;
        final int legendWidth = Math.min(180, Math.max(130, width / 3));
        final int legendHeight = rowHeight * 5 + 8;
        final int x = MARGIN;
        final int y = height - MARGIN - legendHeight;
        draw.fill(x, y, x + legendWidth, y + legendHeight, LOAD_STATE_LEGEND_BACKGROUND);
        final String status = Texts.translatable(
            chunkLoadStates.snapshot().complete()
                ? "confluxmap.map.load_state.ready"
                : "confluxmap.map.load_state.loading"
        ).getString();
        draw.drawTextWithShadow(this.textRenderer, status, x + 5, y + 4, TEXT_COLOR);
        drawLegendRow(draw, x, y + 4 + rowHeight, ChunkLoadBand.ENTITY_TICKING);
        drawLegendRow(draw, x, y + 4 + rowHeight * 2, ChunkLoadBand.BLOCK_TICKING);
        drawLegendRow(draw, x, y + 4 + rowHeight * 3, ChunkLoadBand.BORDER);
        drawLegendRow(draw, x, y + 4 + rowHeight * 4, ChunkLoadBand.UNLOADED);
    }

    private void drawLegendRow(
        final GuiDraw draw,
        final int x,
        final int y,
        final ChunkLoadBand band
    ) {
        draw.fill(x + 5, y + 1, x + 13, y + 9, LOAD_STATE_OUTLINE_COLOR);
        draw.fill(x + 6, y + 2, x + 12, y + 8, loadStateColor(band));
        draw.drawTextWithShadow(
            this.textRenderer,
            loadStateBandName(band),
            x + 17,
            y,
            TEXT_COLOR
        );
    }

    private static String loadStateBandName(final ChunkLoadBand band) {
        return Texts.translatable(
            "confluxmap.map.load_state.band." + band.name().toLowerCase(java.util.Locale.ROOT)
        ).getString();
    }

    private void drawLocationMenu(final GuiDraw draw) {
        if (locationMenuBounds == null) {
            return;
        }
        final int x = locationMenuBounds.x();
        final int y = locationMenuBounds.y();
        final int right = x + locationMenuBounds.width();
        final int bottom = y + locationMenuBounds.height();
        draw.fill(x, y, right, bottom, LOCATION_MENU_BACKGROUND);
        draw.fill(x, y, right, y + 1, LOCATION_MENU_BORDER);
        draw.fill(x, bottom - 1, right, bottom, LOCATION_MENU_BORDER);
        draw.fill(x, y, x + 1, bottom, LOCATION_MENU_BORDER);
        draw.fill(right - 1, y, right, bottom, LOCATION_MENU_BORDER);
    }

    @Override
    protected void renderAfterWidgets(
        final GuiDraw draw,
        final int mouseX,
        final int mouseY,
        final float tickDelta
    ) {
        final Text annotationTooltip = hoveredAnnotationTooltip();
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
        } else if (locationActionHeightUnavailable()) {
            tooltip = Texts.translatable("confluxmap.map.location_menu.height_unavailable");
        } else if (teleportLocationButton != null
            && teleportLocationButton.isHovered()
            && !teleportLocationButton.active) {
            tooltip = Texts.translatable("confluxmap.map.location_menu.teleport_unavailable");
        } else if (structureSearchButton != null && structureSearchButton.isHovered()) {
            tooltip = Texts.translatable(
                !companion.structureSearchAllowed()
                    ? "confluxmap.map.structure_search.disabled_by_server"
                    : structureSearchButton.active
                        ? "confluxmap.map.structure_search.tooltip"
                        : "confluxmap.map.structure_search.unavailable"
            );
        } else if (annotationTooltip != null) {
            tooltip = annotationTooltip;
        } else if (hoveredStructure != null) {
            tooltip = Texts.translatable(
                hoveredStructure.state() == StructureIndex.State.VERIFIED
                    ? "confluxmap.map.structure.verified_tooltip"
                    : "confluxmap.map.structure.candidate_tooltip",
                Texts.translatable(hoveredStructure.type().translationKey()).getString(),
                hoveredStructure.blockX(),
                hoveredStructure.blockZ()
            );
        } else {
            return;
        }
        draw.drawTooltip(this, this.textRenderer, tooltip, mouseX, mouseY);
    }

    private Text hoveredAnnotationTooltip() {
        for (final Map.Entry<ButtonWidget, String> entry : annotationTooltips.entrySet()) {
            if (!entry.getKey().isHovered()) {
                continue;
            }
            if (entry.getKey() == annotationPersistenceButton) {
                return Texts.translatable(
                    entry.getValue(),
                    Texts.translatable(
                        selectedAnnotationPersistence() == AnnotationPersistence.PERSISTENT
                            ? "confluxmap.map.annotation.persistent"
                            : "confluxmap.map.annotation.transient"
                    ).getString()
                );
            }
            if (entry.getKey() == annotationEraserButton) {
                return Texts.translatable(entry.getValue(), config.annotationEraserSize);
            }
            return Texts.translatable(entry.getValue());
        }
        return null;
    }

    private boolean locationActionHeightUnavailable() {
        if (locationMenuTarget == null || locationMenuTarget.blockY().isPresent()) {
            return false;
        }
        return (setWaypointLocationButton != null && setWaypointLocationButton.isHovered())
            || (shareLocationButton != null && shareLocationButton.isHovered());
    }

    private record AnnotationToolbarBounds(int x, int y, int width, int height) {
        boolean contains(final double mouseX, final double mouseY) {
            return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height;
        }
    }

    private static final class MapIconButton extends ButtonWidget {
        private static final int ENABLED_ICON_TINT = 0xFFFFFFFF;
        private static final int DISABLED_ICON_TINT = 0xFF777777;
        private final Identifier icon;
        private final int selectedAccent;
        private boolean selected;

        MapIconButton(
            final int x,
            final int y,
            final Identifier icon,
            final int selectedAccent,
            final PressAction onPress
        ) {
            this(x, y, CONTROL_SIZE, icon, Texts.literal(""), selectedAccent, onPress);
        }

        MapIconButton(
            final int x,
            final int y,
            final Identifier icon,
            final net.minecraft.text.Text message,
            final int selectedAccent,
            final PressAction onPress
        ) {
            this(x, y, CONTROL_SIZE, icon, message, selectedAccent, onPress);
        }

        MapIconButton(
            final int x,
            final int y,
            final int size,
            final Identifier icon,
            final net.minecraft.text.Text message,
            final int selectedAccent,
            final PressAction onPress
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
            this.selectedAccent = selectedAccent;
        }

        void setSelected(final boolean selected) {
            this.selected = selected;
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
        //#elseif MC>=12000
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
            if (selected && selectedAccent != 0) {
                RenderUtil.fillRect(matrices, x + 1, y + 1, getWidth() - 2, 1, selectedAccent);
                RenderUtil.fillRect(matrices, x + 1, y + getHeight() - 2, getWidth() - 2, 1, selectedAccent);
                RenderUtil.fillRect(matrices, x + 1, y + 1, 1, getHeight() - 2, selectedAccent);
                RenderUtil.fillRect(matrices, x + getWidth() - 2, y + 1, 1, getHeight() - 2, selectedAccent);
            }
            final int iconX = x + (getWidth() - CONTROL_ICON_SIZE) / 2;
            final int iconY = y + (getHeight() - CONTROL_ICON_SIZE) / 2;
            final boolean enabled = active && (selected || selectedAccent == 0);
            RenderUtil.bindTexture(MinecraftClient.getInstance(), icon);
            RenderUtil.drawTintedQuad(
                matrices,
                iconX,
                iconY,
                CONTROL_ICON_SIZE,
                CONTROL_ICON_SIZE,
                0f,
                0f,
                1f,
                1f,
                enabled ? ENABLED_ICON_TINT : DISABLED_ICON_TINT
            );
        }
    }

    private int currentLod() {
        return TileMath.lodForScale(scale);
    }

    /**
     * Draws the real tile grid, and - when a seed-predicted underlay is available for the
     * current dimension+layer (only {@link MapLayer.Type#SURFACE} in the Overworld and {@link
     * MapLayer.Type#END_SURFACE} in the End; never a cave/nether layer, which cubiomes can't
     * predict at all) - the matching predicted tile drawn first underneath each real one. Real
     * tiles already render {@code UNKNOWN}/unexplored pixels as fully transparent (see {@code
     * TileService#composeRegion}), and both texture passes enable alpha blending, so the predicted
     * layer simply shows through wherever the real tile has nothing yet.
     */
    private void drawTiles(final MatrixStack matrices) {
        final int lod = currentLod();
        final double pxPerBlock = 1.0 / scale;
        final double blocksPerTile = TileMath.blocksPerTile(lod);
        final TileViewport viewport = TileViewport.covering(centerX, centerZ, width, height, scale, lod);
        final int firstTileX = viewport.minTileX();
        final int lastTileX = viewport.maxTileX();
        final int firstTileZ = viewport.minTileZ();
        final int lastTileZ = viewport.maxTileZ();

        final SessionGuard.Session session = gameBridge.session();
        final MapLayer layer = layerSelector.current().layer();
        final String layerId = layer.cacheId();
        final boolean biomeMode = biomeMode();
        final boolean predictionActive = predictionActive(layer, session);
        tiles.setViewport(layer, lod, firstTileX, lastTileX, firstTileZ, lastTileZ);
        if (predictionActive) {
            predictionTiles.setViewport(session.dimension(), lod, firstTileX, lastTileX, firstTileZ, lastTileZ);
            ConfluxMapClient.get().mapSyncClient().reportViewport(
                session.dimension(), lod, firstTileX, lastTileX, firstTileZ, lastTileZ,
                ChunkViewport.covering(centerX, centerZ, width, height, scale)
            );
        } else {
            predictionTiles.clearViewport();
            ConfluxMapClient.get().mapSyncClient().clearViewport();
        }

        if (predictionActive) {
            final int predictionTint = biomeMode ? 0xFFFFFFFF : predictionTint(layer);
            for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
                for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                    final TileKey terrainKey = new TileKey(
                        session.world(), session.dimension(), layerId, lod, tileX, tileZ
                    );
                    final TileKey key = biomeMode ? BiomeTileKeys.toBiome(terrainKey) : terrainKey;
                    if (textures.bind(PredictedTileKeys.toPredicted(key))) {
                        final float screenX = (float) (width / 2.0 + (key.originBlockX() - centerX) * pxPerBlock);
                        final float screenY = (float) (height / 2.0 + (key.originBlockZ() - centerZ) * pxPerBlock);
                        final float quadSize = (float) (blocksPerTile * pxPerBlock);
                        RenderUtil.drawTintedQuad(
                            matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f, predictionTint
                        );
                    }
                }
            }
        }

        RenderUtil.beginTexturedQuads();
        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey terrainKey = new TileKey(
                    session.world(), session.dimension(), layerId, lod, tileX, tileZ
                );
                final TileKey key = biomeMode ? BiomeTileKeys.toBiome(terrainKey) : terrainKey;
                if (textures.bind(key)) {
                    final float screenX = (float) (width / 2.0 + (key.originBlockX() - centerX) * pxPerBlock);
                    final float screenY = (float) (height / 2.0 + (key.originBlockZ() - centerZ) * pxPerBlock);
                    final float quadSize = (float) (blocksPerTile * pxPerBlock);
                    RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
                }
            }
        }
    }

    /** Only Overworld SURFACE / End END_SURFACE layers have a seed-predicted underlay (see {@link #drawTiles}). */
    private boolean predictionActive(final MapLayer layer, final SessionGuard.Session session) {
        final boolean eligibleLayer = layer.type() == MapLayer.Type.SURFACE || layer.type() == MapLayer.Type.END_SURFACE;
        return config.predictionEnabled && eligibleLayer && predictionState.predictable(session.dimension());
    }

    private int predictionTint(final MapLayer layer) {
        return PredictionLighting.renderTint(
            config.dynamicLighting && layer.type() == MapLayer.Type.SURFACE,
            daylightModel.factor()
        );
    }

    /**
     * Radar entries above the map tiles but below waypoint markers (see {@link #render}), reusing
     * {@link RadarMarkerRenderer} - the exact same icon/dot drawing {@code MinimapHudRenderer} uses,
     * so both surfaces look identical. Always north-up (this screen never rotates), and positions
     * use the same live-interpolated-position-over-scan-snapshot preference as the minimap (see
     * {@code MinimapHudRenderer#drawRadar}'s javadoc). Category toggles and {@code radarEnabled}
     * already apply upstream in the scanner, so no extra filtering happens here beyond viewport culling.
     */
    private void drawRadar(final GuiDraw draw, final float tickDelta) {
        if (this.client.world == null) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final double pxPerBlock = 1.0 / scale;
        final SessionGuard.Session session = gameBridge.session();
        final MapLayer layer = layerSelector.current().layer();
        final boolean predictionActive = predictionActive(layer, session);
        final boolean biomeMode = biomeMode();
        final String layerId = biomeMode
            ? layer.cacheId() + BiomeTileKeys.SUFFIX
            : layer.cacheId();
        final RadarBackdrop backdrop = new RadarBackdrop(
            textures, session.world(), session.dimension(), layerId, currentLod(),
            predictionActive,
            predictionActive ? (biomeMode ? 0xFFFFFFFF : predictionTint(layer)) : 0,
            BACKGROUND_COLOR
        );

        final List<RadarMarkerRenderer.Marker> markers = new ArrayList<>();
        for (final RadarEntry entry : radarScanner.snapshot()) {
            double ex = entry.x();
            double ez = entry.z();
            int yDelta = entry.yDelta();
            final Entity live = this.client.world.getEntityById(entry.entityId());
            if (live != null) {
                ex = MathHelper.lerp(tickDelta, live.prevX, live.getX());
                ez = MathHelper.lerp(tickDelta, live.prevZ, live.getZ());
                yDelta = (int) Math.round(live.getY() - player.y());
            }

            final float screenX = (float) (width / 2.0 + (ex - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (ez - centerZ) * pxPerBlock);
            if (screenX < -RADAR_CULL_MARGIN || screenX > width + RADAR_CULL_MARGIN
                || screenY < -RADAR_CULL_MARGIN || screenY > height + RADAR_CULL_MARGIN) {
                continue;
            }
            markers.add(new RadarMarkerRenderer.Marker(
                entry, screenX, screenY, ex, ez, yDelta, live
            ));
        }
        RadarMarkerRenderer.drawAll(
            draw, this.client, config, radarIconManager, backdrop, markers, (float) scale
        );
    }

    private void drawStructures(final GuiDraw draw, final int mouseX, final int mouseY) {
        hoveredStructure = null;
        if (!companion.structureSearchAllowed() || !config.predictionShowStructures
            || currentLod() > 2 || !predictionState.seedKnown()) {
            return;
        }
        final int minX = (int) Math.floor(centerX - width / 2.0 * scale);
        final int maxX = (int) Math.ceil(centerX + width / 2.0 * scale);
        final int minZ = (int) Math.floor(centerZ - height / 2.0 * scale);
        final int maxZ = (int) Math.ceil(centerZ + height / 2.0 * scale);
        final double pxPerBlock = 1.0 / scale;
        final DimensionId dimension = gameBridge.session().dimension();
        final java.util.EnumSet<StructureIndex.StructureType> visibleTypes =
            config.predictionStructureVisibility.visibleTypes(
                predictionState.mcVersion(),
                dimension,
                structureMarkers.availableTypes(dimension)
            );
        final List<StructureIndex.Marker> markers = structureMarkers.query(
            minX, maxX, minZ, maxZ, scale, visibleTypes
        );
        markers.sort(java.util.Comparator.comparingLong(marker -> {
            final long dx = marker.blockX() - (long) centerX;
            final long dz = marker.blockZ() - (long) centerZ;
            return dx * dx + dz * dz;
        }));
        final List<StructureIndex.Marker> visibleMarkers = limitStructureMarkers(markers);
        double bestHoverDistance = 8.0;
        for (final StructureIndex.Marker marker : visibleMarkers) {
            final float screenX = (float) (width / 2.0 + (marker.blockX() - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.blockZ() - centerZ) * pxPerBlock);
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
            StructureMarkerRenderer.draw(draw, marker, screenX, screenY, hovered);
            if (hovered) {
                draw.drawTextWithShadow(
                    this.textRenderer,
                    Texts.translatable(marker.type().translationKey()),
                    screenX + 10f,
                    screenY - 4f,
                    TEXT_COLOR
                );
            }
        }
    }

    private static List<StructureIndex.Marker> limitStructureMarkers(
        final List<StructureIndex.Marker> nearestFirst
    ) {
        final Map<StructureIndex.StructureType, Integer> counts =
            new EnumMap<>(StructureIndex.StructureType.class);
        final List<StructureIndex.Marker> visible = new ArrayList<>();
        for (final StructureIndex.Marker marker : nearestFirst) {
            final int count = counts.getOrDefault(marker.type(), 0);
            if (count >= MAX_VISIBLE_MARKERS_PER_STRUCTURE) {
                continue;
            }
            counts.put(marker.type(), count + 1);
            visible.add(marker);
        }
        return visible;
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

    /** Always north-locked, so only the arrow itself rotates with the player's facing (mirrors {@code MinimapHudRenderer}'s north-locked mode). */
    private void drawPlayerMarker(final MatrixStack matrices, final float tickDelta) {
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final double pxPerBlock = 1.0 / scale;
        final float screenX = (float) (width / 2.0 + (player.x() - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (player.z() - centerZ) * pxPerBlock);
        matrices.push();
        matrices.translate(screenX, screenY, 0);
        RenderUtil.rotateZ(matrices, player.yawDegrees() + 180f);
        RenderUtil.fillTriangle(matrices, 0f, -7f, -5.5f, 6f, 5.5f, 6f, ARROW_OUTLINE);
        RenderUtil.fillTriangle(matrices, 0f, -5.5f, -4f, 4.5f, 4f, 4.5f, ARROW_FILL);
        matrices.pop();
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
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double pxPerBlock = 1.0 / scale;
        final List<ScreenMarker> markers = new ArrayList<>();
        for (final WaypointRenderEntry waypoint : waypointRenderCatalog.snapshot(currentDimension)) {
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

        for (final ScreenMarker marker : markers) {
            final WaypointRenderEntry waypoint = marker.waypoint();
            final boolean isHovered = waypoint == hoveredWaypoint;
            final WaypointVerticalRelation relation = playerView
                .map(player -> WaypointVerticalRelation.between(waypoint.y(), player.y()))
                .orElse(WaypointVerticalRelation.NONE);
            WaypointMarkerRenderer.draw(
                draw, this.client.textRenderer, waypoint, marker.screenX(), marker.screenY(),
                MARKER_HALF_SIZE, 1f, isHovered, relation
            );
            if (scale <= NAME_LABEL_MAX_SCALE || isHovered) {
                draw.drawTextWithShadow(
                    this.textRenderer, waypoint.name(), marker.screenX() + MARKER_HALF_SIZE + 2, marker.screenY() - 4, TEXT_COLOR
                );
            }
        }
    }

    /** One waypoint's already-converted, already-viewport-culled screen position for this frame's {@link #drawWaypoints} pass. */
    private record ScreenMarker(WaypointRenderEntry waypoint, float screenX, float screenY) {
    }

    private void drawDimensionLabel(final GuiDraw draw) {
        final String text = dimensionDisplayName(gameBridge.session().dimension());
        draw.drawTextWithShadow(this.textRenderer, text, MARGIN, MARGIN, TEXT_COLOR);
    }

    /** Deliverable D: the fullscreen map shows the active layer for the current dimension. */
    private void drawLayerLabel(final GuiDraw draw) {
        final String text = Texts.translatable(
            "confluxmap.layer." + layerSelector.current().layer().type().id()
        ).getString();
        draw.drawTextWithShadow(this.textRenderer, text, MARGIN, MARGIN + this.textRenderer.fontHeight + 2, TEXT_COLOR);
    }

    private void drawPredictionLabel(final GuiDraw draw) {
        if (!predictionLabelVisible()) {
            return;
        }
        final DimensionId dimension = gameBridge.session().dimension();
        final WorldPreset preset = predictionState.preset(dimension);
        final String text;
        if (PredictionDimensions.supported(dimension) && !predictionState.predictable(dimension)) {
            // Seed known but this dimension can't compose an underlay (debug/custom worldgen, or
            // superflat without surface info): say so instead of silently dropping the label.
            text = Texts.translatable(
                "confluxmap.map.prediction_unavailable", presetDisplayName(preset)
            ).getString();
        } else {
            final String mode = Texts.translatable(
                "confluxmap.config.prediction.mode." + config.predictionViewMode.name().toLowerCase(java.util.Locale.ROOT)
            ).getString();
            final String modeLine = Texts.translatable("confluxmap.config.prediction.view_mode", mode).getString();
            text = preset.terrainApproximate()
                ? modeLine + Texts.translatable("confluxmap.map.prediction_approx_suffix", presetDisplayName(preset)).getString()
                : modeLine;
        }
        draw.drawTextWithShadow(
            this.textRenderer, text, MARGIN, MARGIN + this.textRenderer.fontHeight * 2 + 4, TEXT_COLOR
        );
    }

    /** A seedless superflat session still predicts, so the label keys off either signal. */
    private boolean predictionLabelVisible() {
        return config.predictionEnabled
            && (predictionState.seedKnown() || predictionState.predictable(gameBridge.session().dimension()));
    }

    private static String presetDisplayName(final WorldPreset preset) {
        return Texts.translatable(
            "confluxmap.world_preset." + preset.name().toLowerCase(java.util.Locale.ROOT)
        ).getString();
    }

    private void drawServerSyncLabel(final GuiDraw draw) {
        final MapSyncProgress.Snapshot status = ConfluxMapClient.get().mapSyncClient().status();
        if (status.state() == MapSyncProgress.State.IDLE) {
            return;
        }
        final String text;
        final int color;
        switch (status.state()) {
            case SYNCING -> {
                text = Texts.translatable(
                    "confluxmap.map.server_sync.syncing",
                    status.completedTiles(), status.totalTiles()
                ).getString();
                color = SYNCING_TEXT_COLOR;
            }
            case COMPLETED -> {
                text = Texts.translatable(
                    "confluxmap.map.server_sync.completed",
                    formatSyncDuration(status.durationNanos()), formatSyncTraffic(status.trafficBytes())
                ).getString();
                color = SYNCED_TEXT_COLOR;
            }
            case FAILED -> {
                text = Texts.translatable("confluxmap.map.server_sync.failed").getString();
                color = SYNC_FAILED_TEXT_COLOR;
            }
            default -> {
                return;
            }
        }
        final int row = predictionLabelVisible() ? 3 : 2;
        draw.drawTextWithShadow(this.textRenderer, text, MARGIN, MARGIN + row * (this.textRenderer.fontHeight + 2), color);
    }

    private static String formatSyncDuration(final long durationNanos) {
        final long durationMillis = durationNanos / 1_000_000L;
        if (durationMillis < 1_000L) {
            return durationMillis + " ms";
        }
        return String.format(java.util.Locale.ROOT, "%.2f s", durationNanos / 1_000_000_000.0);
    }

    private static String formatSyncTraffic(final long trafficBytes) {
        if (trafficBytes < 1_024L) {
            return trafficBytes + " B";
        }
        if (trafficBytes < 1_048_576L) {
            return String.format(java.util.Locale.ROOT, "%.1f KiB", trafficBytes / 1_024.0);
        }
        return String.format(java.util.Locale.ROOT, "%.2f MiB", trafficBytes / 1_048_576.0);
    }

    private void drawScaleLabel(final GuiDraw draw) {
        final String text = Texts.translatable(
            "confluxmap.map.scale", FullscreenZoomLabel.format(scale)
        ).getString();
        final int textWidth = this.textRenderer.getWidth(text);
        draw.drawTextWithShadow(this.textRenderer, text, width - MARGIN - textWidth, MARGIN, TEXT_COLOR);
    }

    /**
     * Bottom-center footer: the raw cursor position, or - while hovering a marker
     * (deliverable C) - that waypoint's own stored X/Y/Z instead. When not hovering a marker, the cursor's biome name
     * (see {@link #cursorBiomeName}) is appended if it can be resolved.
     */
    private void drawCursorCoords(final GuiDraw draw, final int mouseX, final int mouseY) {
        final String text;
        if (hoveredWaypoint != null) {
            text = (int) Math.floor(hoveredWaypoint.x()) + ", "
                + (int) Math.floor(hoveredWaypoint.y()) + ", "
                + (int) Math.floor(hoveredWaypoint.z());
        } else if (hoveredStructure != null) {
            text = Texts.translatable(hoveredStructure.type().translationKey()).getString()
                + " · " + hoveredStructure.blockX() + ", " + hoveredStructure.blockZ();
        } else if (loadStateMode()) {
            text = chunkLoadStateCursorText(mouseX, mouseY);
        } else {
            final int blockX = (int) Math.floor(centerX + (mouseX - width / 2.0) * scale);
            final int blockZ = (int) Math.floor(centerZ + (mouseY - height / 2.0) * scale);
            final String biomeName = cursorBiomeName(blockX, blockZ);
            text = blockX + ", " + blockZ + (biomeName == null ? "" : " · " + biomeName);
        }
        final int textWidth = this.textRenderer.getWidth(text);
        draw.drawTextWithShadow(this.textRenderer, text, width / 2f - textWidth / 2f, height - MARGIN - 10, TEXT_COLOR);
    }

    private String chunkLoadStateCursorText(final int mouseX, final int mouseY) {
        final int blockX = (int) Math.floor(centerX + (mouseX - width / 2.0) * scale);
        final int blockZ = (int) Math.floor(centerZ + (mouseY - height / 2.0) * scale);
        final int chunkX = TileMath.blockToChunk(blockX);
        final int chunkZ = TileMath.blockToChunk(blockZ);
        final Optional<LoadStateDeltaS2C.Entry> state = chunkLoadStates.snapshot().get(chunkX, chunkZ);
        final String stateText;
        if (state.isPresent()) {
            final LoadStateDeltaS2C.Entry entry = state.get();
            stateText = config.chunkLoadDetailMode == ChunkLoadDetailMode.EXACT
                ? loadStateBandName(entry.band()) + " · L" + entry.level()
                : loadStateBandName(entry.band());
        } else {
            stateText = Texts.translatable(
                chunkLoadStates.snapshot().complete()
                    ? "confluxmap.map.load_state.unloaded"
                    : "confluxmap.map.load_state.unknown"
            ).getString();
        }
        return blockX + ", " + blockZ + " · "
            + Texts.translatable("confluxmap.map.load_state.chunk", chunkX, chunkZ).getString()
            + " · " + stateText;
    }

    /**
     * Best-effort biome name at the given column, for the footer readout. Loaded real chunks win;
     * unexplored columns fall back to the same predicted biome sample and LOD as the rendered
     * underlay. Returns null when neither source has a resolvable biome identifier.
     */
    private String cursorBiomeName(final int blockX, final int blockZ) {
        final ClientWorld world = this.client.world;
        if (world != null) {
            final int playerY = gameBridge.player().map(p -> p.blockY()).orElse(world.getBottomY());
            final BlockPos pos = new BlockPos(
                blockX, MathHelper.clamp(playerY, world.getBottomY(), world.getTopY() - 1), blockZ
            );
            if (ClientChunkLookup.isLoaded(world, blockX, blockZ)) {
                final Identifier biomeId = Regs.biomeIdAt(world, pos);
                if (biomeId != null) {
                    return translatedBiomeName(biomeId);
                }
            }
        }
        final OptionalInt predicted = predictionTiles.predictedBiomeAt(
            gameBridge.session().dimension(), currentLod(), blockX, blockZ
        );
        if (predicted.isEmpty()) {
            return null;
        }
        return predictedBiomeName(predicted.getAsInt());
    }

    /**
     * A predicted biome only knows its cubiomes id, whose canonical vanilla name is the 1.17-era
     * one; biomes renamed since (snowy_tundra -> snowy_plains, ...) have no translation under the
     * old key on modern versions, which surfaced as a raw {@code biome.minecraft.snowy_tundra}
     * footer. Try every spelling the id has carried and keep the first one the loaded language
     * actually translates, falling back to the canonical raw key when none do.
     */
    private static String predictedBiomeName(final int cubiomesId) {
        String fallback = null;
        for (final String name : CubiomesBiomeIds.namesForId(cubiomesId)) {
            final String key = Util.createTranslationKey("biome", Ids.of("minecraft", name));
            final String translated = Texts.translatable(key).getString();
            if (!translated.equals(key)) {
                return translated;
            }
            if (fallback == null) {
                fallback = translated;
            }
        }
        return fallback;
    }

    /** Bottom-right corner: a passive notice while a newer release is known (the chat line carries the clickable link). */
    private void drawUpdateBadge(final GuiDraw draw) {
        final Optional<UpdateCheckService.UpdateInfo> info = updateCheck.available();
        if (info.isEmpty()) {
            return;
        }
        final String text = Texts.translatable("confluxmap.map.update_badge", info.get().latestVersion()).getString();
        final int textWidth = this.textRenderer.getWidth(text);
        draw.drawTextWithShadow(this.textRenderer, text, width - MARGIN - textWidth, height - MARGIN - 10, UPDATE_TEXT_COLOR);
    }

    private static String translatedBiomeName(final Identifier biomeId) {
        return Texts.translatable(Util.createTranslationKey("biome", biomeId)).getString();
    }

    private static String dimensionDisplayName(final DimensionId dimension) {
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
        final DimensionId dimension = gameBridge.session().dimension();
        structureSearchButton.active = companion.structureSearchAllowed()
            && predictionState.structuresCubiomesBacked(dimension)
            && !structureMarkers.availableTypes(dimension).isEmpty();
    }

    private void openStructureSearch() {
        if (!companion.structureSearchAllowed()) {
            return;
        }
        final DimensionId dimension = gameBridge.session().dimension();
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
}
