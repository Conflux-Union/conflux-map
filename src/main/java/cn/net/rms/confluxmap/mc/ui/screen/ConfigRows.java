package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.portal.PortalKind;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleFunction;
import java.util.function.DoubleSupplier;
import java.util.function.IntConsumer;
import java.util.function.IntFunction;
import java.util.function.IntSupplier;
import java.util.function.Supplier;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

/**
 * Builds the settings screen's widget rows: the generic toggle/enum/slider/action
 * row factories, the non-interactive section headers that visually group long
 * category lists, and the per-category row wiring for every {@link ConfluxConfig}
 * field. Owns the per-rebuild input widget lists ({@code tick}ing sliders and
 * text fields, and the static labels drawn beside portal-icon text fields) so
 * {@link ConfigScreen} only has to orchestrate tabs, scrolling and policy state.
 * Every row mutates and saves the shared config immediately.
 */
final class ConfigRows {
    private static final int SECTION_TEXT_COLOR = 0xFFA0A0A0;
    private static final int SECTION_LINE_COLOR = 0xFF3A3A3A;

    private final ConfigScreen screen;
    private final ConfluxConfig config;
    private final ConfigIo configIo;

    private final List<IntSliderInput> sliderInputs = new ArrayList<>();
    private final List<DecimalSliderInput> decimalSliderInputs = new ArrayList<>();
    private final List<TextFieldWidget> textFields = new ArrayList<>();
    private final List<IconTextureLabel> iconTextureLabels = new ArrayList<>();
    private final List<SectionLabel> sectionLabels = new ArrayList<>();

    /** Static label drawn beside a portal-icon text-field row; the row widget is the field itself. */
    private record IconTextureLabel(int y, String key) {
    }

    /** Non-interactive section header row drawn as a label above a divider line. */
    private record SectionLabel(int y, String key) {
    }

    ConfigRows(final ConfigScreen screen, final ConfluxConfig config, final ConfigIo configIo) {
        this.screen = screen;
        this.config = config;
        this.configIo = configIo;
    }

    /** Drops every per-rebuild input widget; called before the screen clears its children. */
    void clearInputs() {
        sliderInputs.clear();
        decimalSliderInputs.clear();
        textFields.clear();
        iconTextureLabels.clear();
        sectionLabels.clear();
    }

    void tickInputs() {
        for (final IntSliderInput sliderInput : sliderInputs) {
            sliderInput.tick();
        }
        for (final DecimalSliderInput sliderInput : decimalSliderInputs) {
            sliderInput.tick();
        }
        for (final TextFieldWidget field : textFields) {
            Widgets.tick(field);
        }
    }

    void drawIconTextureLabels(final GuiDraw draw) {
        for (final IconTextureLabel rowLabel : iconTextureLabels) {
            final Text label = Texts.translatable(rowLabel.key());
            draw.drawTextWithShadow(
                screen.textRenderer(), label, screen.rowX(),
                rowLabel.y() + (ConfigScreen.ROW_HEIGHT - 2 - screen.textRenderer().fontHeight) / 2f,
                0xFFE0E0E0
            );
        }
    }

    void drawSectionLabels(final GuiDraw draw) {
        for (final SectionLabel section : sectionLabels) {
            final Text label = Texts.translatable(section.key());
            final int textY = section.y() + 1;
            draw.drawTextWithShadow(
                screen.textRenderer(), label, screen.rowX(), textY, SECTION_TEXT_COLOR
            );
            final int lineY = textY + screen.textRenderer().fontHeight + 2;
            draw.fill(
                screen.rowX(), lineY, screen.rowX() + screen.rowWidth, lineY + 1, SECTION_LINE_COLOR
            );
        }
    }

    /**
     * Builds the selected category's rows and returns the y coordinate one row
     * past the last built row (the caller derives the scrollable content height
     * from it).
     */
    int addRows() {
        final int rowsTop = screen.rowsTop();
        int y = rowsTop - screen.scrollOffset;
        switch (screen.category) {
            case MINIMAP:
                y = addToggleRow(y, "confluxmap.config.minimap.enabled", () -> config.minimapEnabled, v -> config.minimapEnabled = v);
                y = addActionRow(
                    y,
                    "confluxmap.config.minimap.position",
                    () -> MinecraftAccess.setScreen(MinecraftClient.getInstance(), new MinimapPositionScreen(screen, config, configIo))
                );
                y = addToggleRow(
                    y,
                    "confluxmap.config.minimap.hud_avoidance",
                    () -> config.minimapHudAvoidance,
                    v -> config.minimapHudAvoidance = v
                );
                y = addEnumRow(
                    y, "confluxmap.config.minimap.shape", ConfluxConfig.Shape.values(),
                    () -> config.minimapShape, v -> config.minimapShape = v, ConfigValueLabels::shapeKey
                );
                y = addEnumRow(
                    y,
                    "confluxmap.config.player_marker.style",
                    ConfluxConfig.PlayerMarkerStyle.values(),
                    () -> config.playerMarkerStyle,
                    v -> config.playerMarkerStyle = v,
                    style -> screen.playerMarkerAccess.resourceOverride()
                        ? "confluxmap.value.player_marker.resource_pack"
                        : ConfigValueLabels.playerMarkerStyleKey(style),
                    screen.playerMarkerAccess.controlsActive(),
                    screen.playerMarkerAccess.tooltipKey()
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.minimap.size", 64, 256,
                    () -> config.minimapSize, v -> config.minimapSize = v, ConfigValueLabels::pxText
                );
                y = addToggleRow(y, "confluxmap.config.minimap.rotate", () -> config.minimapRotate, v -> config.minimapRotate = v);
                y = addZoomRow(y);
                y = addToggleRow(y, "confluxmap.config.minimap.show_coordinates", () -> config.showCoordinates, v -> config.showCoordinates = v);
                y = addToggleRow(y, "confluxmap.config.minimap.show_biome", () -> config.showBiome, v -> config.showBiome = v);
                y = addToggleRow(y, "confluxmap.config.minimap.show_layer_label", () -> config.showLayerIndicator, v -> config.showLayerIndicator = v);
                break;
            case MAP:
                y = addSectionRow(y, "confluxmap.screen.config.section.layers");
                y = addEnumRow(
                    y, "confluxmap.config.layers.override", ConfluxConfig.LayerOverride.values(),
                    () -> config.layerOverride, v -> config.layerOverride = v, ConfigValueLabels::layerOverrideKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.layers.cave_slice_y", 0, 255,
                    () -> config.caveSliceY, v -> config.caveSliceY = v, ConfigValueLabels::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.layers.nether_slice_y", 0, 127,
                    () -> config.netherSliceY, v -> config.netherSliceY = v, ConfigValueLabels::plainText
                );
                y = addSectionRow(y, "confluxmap.screen.config.section.map_style");
                y = addToggleRow(
                    y, "confluxmap.config.map.dynamic_lighting", () -> config.dynamicLighting, v -> config.dynamicLighting = v
                );
                y = addToggleRow(
                    y, "confluxmap.config.map.bedrock_thickness_shading",
                    () -> config.showNetherCeilingBedrockThickness,
                    v -> {
                        config.showNetherCeilingBedrockThickness = v;
                        ConfluxMapClient.get().onNetherCeilingBedrockThicknessChanged();
                    }
                );
                y = addEnumRow(
                    y, "confluxmap.config.map.color_style", MapColorStyle.values(),
                    () -> config.mapColorStyle,
                    v -> {
                        config.mapColorStyle = v;
                        ConfluxMapClient.get().onMapColorStyleChanged();
                    },
                    ConfigValueLabels::mapColorStyleKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.map.quality", 0, ConfluxConfig.MapQuality.values().length - 1,
                    () -> config.mapQuality.ordinal(),
                    v -> {
                        config.mapQuality = ConfluxConfig.MapQuality.values()[Math.max(0, Math.min(ConfluxConfig.MapQuality.values().length - 1, v))];
                        final ConfluxMapClient client = ConfluxMapClient.get();
                        if (client != null && client.predictionTileService() != null) {
                            client.predictionTileService().setMapQuality(config.mapQuality);
                        }
                    },
                    ConfigValueLabels::mapQualitySliderText
                );
                y = addToggleRow(y, "confluxmap.config.fullmap.chunk_grid", () -> config.fullmapChunkGrid, v -> config.fullmapChunkGrid = v);
                break;
            case MARKERS:
                y = addToggleRow(
                    y, "confluxmap.config.portals.enabled",
                    () -> config.portalMarkersEnabled,
                    v -> {
                        config.portalMarkersEnabled = v;
                        if (v) {
                            ConfluxMapClient.get().portalScanService().requestLoadedChunkScan();
                        }
                    }
                );
                y = addToggleRow(
                    y, "confluxmap.config.portals.show_icons",
                    () -> config.portalIconsEnabled, v -> config.portalIconsEnabled = v
                );
                y = addIconTextureRow(
                    y, "confluxmap.config.portals.icon_texture.nether", PortalKind.NETHER_PORTAL
                );
                y = addIconTextureRow(
                    y, "confluxmap.config.portals.icon_texture.end", PortalKind.END_PORTAL
                );
                y = addIconTextureRow(
                    y, "confluxmap.config.portals.icon_texture.gateway", PortalKind.END_GATEWAY
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.portals.icon_size",
                    ConfluxConfig.MIN_PORTAL_ICON_SIZE, ConfluxConfig.MAX_PORTAL_ICON_SIZE,
                    () -> config.portalIconSize, v -> config.portalIconSize = v,
                    ConfigValueLabels::pxText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.portals.icon_opacity",
                    ConfluxConfig.MIN_PORTAL_ICON_OPACITY, ConfluxConfig.MAX_PORTAL_ICON_OPACITY,
                    () -> config.portalIconOpacity, v -> config.portalIconOpacity = v,
                    ConfigValueLabels::percentText
                );
                y = addToggleRow(
                    y, "confluxmap.config.portals.chunk_highlight",
                    () -> config.portalChunkHighlightEnabled, v -> config.portalChunkHighlightEnabled = v
                );
                y = addEnumRow(
                    y, "confluxmap.config.portals.highlight_color",
                    ConfluxConfig.PortalHighlightColor.values(),
                    () -> config.portalHighlightColor, v -> config.portalHighlightColor = v,
                    ConfigValueLabels::portalHighlightColorKey
                );
                y = addDecimalSliderRow(
                    y, "confluxmap.config.portals.icon_detail_limit",
                    ConfluxConfig.MIN_PORTAL_ICON_HIDE_ZOOM, ConfluxConfig.MAX_PORTAL_ICON_HIDE_ZOOM,
                    DecimalSliderValue.CONTINUOUS,
                    () -> config.portalIconHideZoom, v -> config.portalIconHideZoom = v,
                    ConfigValueLabels::structureIconDetailLimitText, true, null
                );
                y = addToggleRow(
                    y, "confluxmap.config.player_trail.enabled",
                    () -> config.playerTrailEnabled, v -> config.playerTrailEnabled = v
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.player_trail.duration",
                    ConfluxConfig.MIN_PLAYER_TRAIL_DURATION_SECONDS,
                    ConfluxConfig.MAX_PLAYER_TRAIL_DURATION_SECONDS,
                    () -> config.playerTrailDurationSeconds,
                    v -> config.playerTrailDurationSeconds = v,
                    ConfigValueLabels::secondsText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.player_trail.dot_size",
                    ConfluxConfig.MIN_PLAYER_TRAIL_DOT_SIZE,
                    ConfluxConfig.MAX_PLAYER_TRAIL_DOT_SIZE,
                    () -> config.playerTrailDotSize,
                    v -> config.playerTrailDotSize = v,
                    ConfigValueLabels::pxText
                );
                y = addToggleRow(
                    y, "confluxmap.config.minimap.annotations",
                    () -> config.annotationsOnHud, v -> config.annotationsOnHud = v
                );
                break;
            case RADAR:
                final boolean radarControlsActive = screen.radarAccess.controlsActive();
                final String radarTooltipKey = screen.radarAccess.tooltipKey();
                y = addToggleRow(
                    y, "confluxmap.config.radar.enabled",
                    () -> config.radarEnabled, v -> config.radarEnabled = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addEnumRow(
                    y, "confluxmap.config.radar.display_mode",
                    ConfluxConfig.RadarDisplayMode.values(),
                    () -> config.radarDisplayMode, v -> config.radarDisplayMode = v,
                    ConfigValueLabels::radarDisplayModeKey,
                    radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.show_players",
                    () -> config.radarShowPlayers, v -> config.radarShowPlayers = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.show_hostile",
                    () -> config.radarShowHostile, v -> config.radarShowHostile = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.show_passive",
                    () -> config.radarShowPassive, v -> config.radarShowPassive = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.show_other",
                    () -> config.radarShowOther, v -> config.radarShowOther = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.show_player_names",
                    () -> config.radarShowPlayerNames, v -> config.radarShowPlayerNames = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.always_show_player_names",
                    () -> config.radarAlwaysShowPlayerNames,
                    v -> config.radarAlwaysShowPlayerNames = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.max_entities", 1, 500,
                    () -> config.radarMaxEntities, v -> config.radarMaxEntities = v,
                    ConfigValueLabels::plainText, radarControlsActive, radarTooltipKey
                );
                y = addToggleRow(
                    y, "confluxmap.config.radar.player_icon_outline",
                    () -> config.radarPlayerIconOutlineEnabled,
                    v -> config.radarPlayerIconOutlineEnabled = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.icon_outline_thickness",
                    ConfluxConfig.MIN_RADAR_ICON_OUTLINE_THICKNESS,
                    ConfluxConfig.MAX_RADAR_ICON_OUTLINE_THICKNESS,
                    () -> config.radarIconOutlineThickness,
                    v -> config.radarIconOutlineThickness = v,
                    ConfigValueLabels::pxText, radarControlsActive, radarTooltipKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.icon_size",
                    ConfluxConfig.MIN_RADAR_ICON_SIZE, ConfluxConfig.MAX_RADAR_ICON_SIZE,
                    () -> config.radarIconSize, v -> config.radarIconSize = v,
                    ConfigValueLabels::pxText, radarControlsActive, radarTooltipKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.player_highlight_ghost_duration",
                    ConfluxConfig.MIN_RADAR_PLAYER_HIGHLIGHT_GHOST_SECONDS,
                    ConfluxConfig.MAX_RADAR_PLAYER_HIGHLIGHT_GHOST_SECONDS,
                    () -> config.radarPlayerHighlightGhostSeconds,
                    v -> config.radarPlayerHighlightGhostSeconds = v,
                    ConfigValueLabels::secondsText, radarControlsActive, radarTooltipKey
                );
                break;
            case WAYPOINTS:
                y = addSectionRow(y, "confluxmap.screen.config.section.teleport");
                y = addActionRow(
                    y,
                    "confluxmap.config.waypoints.teleport_command",
                    () -> MinecraftAccess.setScreen(
                        MinecraftClient.getInstance(),
                        new TeleportCommandScreen(screen, config, configIo)
                    )
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.teleport_close_map",
                    () -> config.teleportCloseMap, v -> config.teleportCloseMap = v
                );
                y = addSectionRow(y, "confluxmap.screen.config.section.visibility");
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.show_local",
                    () -> config.localWaypointsVisible, v -> config.localWaypointsVisible = v
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.show_cross_world",
                    () -> config.crossWorldWaypointsVisible, v -> config.crossWorldWaypointsVisible = v
                );
                if (screen.sharedAvailability.visible()) {
                    y = addToggleRow(
                        y, "confluxmap.config.waypoints.show_shared",
                        () -> config.sharedWaypointsVisible, v -> config.sharedWaypointsVisible = v,
                        screen.sharedAvailability.ready(),
                        screen.sharedAvailability.disabledByServer()
                            ? "confluxmap.shared_waypoints.disabled_by_server"
                            : null
                    );
                }
                y = addSectionRow(y, "confluxmap.screen.config.section.waypoint_list");
                y = addEnumRow(
                    y, "confluxmap.config.waypoints.list_default_tab",
                    ConfluxConfig.WaypointListTab.values(),
                    () -> config.waypointListDefaultTab, v -> config.waypointListDefaultTab = v,
                    ConfigValueLabels::waypointListTabKey
                );
                y = addEnumRow(
                    y, "confluxmap.config.waypoints.list_default_dimension",
                    ConfluxConfig.WaypointListDefaultDimension.values(),
                    () -> config.waypointListDefaultDimension,
                    v -> config.waypointListDefaultDimension = v,
                    ConfigValueLabels::waypointListDefaultDimensionKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.death_points_kept", 0, 50,
                    () -> config.deathPointsKept, v -> config.deathPointsKept = v, ConfigValueLabels::plainText
                );
                y = addSectionRow(y, "confluxmap.screen.config.section.in_world_rendering");
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.render_distance", 0, 100_000,
                    () -> config.waypointRenderDistance, v -> config.waypointRenderDistance = v, ConfigValueLabels::renderDistanceText
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.edge_indicators",
                    () -> config.waypointEdgeIndicatorsEnabled, v -> config.waypointEdgeIndicatorsEnabled = v
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.beams_enabled",
                    () -> config.waypointBeamsEnabled, v -> config.waypointBeamsEnabled = v
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.labels_enabled",
                    () -> config.waypointLabelsEnabled, v -> config.waypointLabelsEnabled = v
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.label_opacity",
                    ConfluxConfig.MIN_WAYPOINT_ICON_OPACITY, ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY,
                    () -> config.waypointIconOpacity, v -> config.waypointIconOpacity = v,
                    ConfigValueLabels::percentText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.highlight_dim_opacity",
                    ConfluxConfig.MIN_WAYPOINT_HIGHLIGHT_DIM_OPACITY,
                    ConfluxConfig.MAX_WAYPOINT_HIGHLIGHT_DIM_OPACITY,
                    () -> config.waypointHighlightDimOpacity,
                    v -> config.waypointHighlightDimOpacity = v,
                    ConfigValueLabels::percentText
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.highlight_cross_dimension",
                    () -> config.highlightCrossDimensionVisible,
                    v -> config.highlightCrossDimensionVisible = v
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.label_scale",
                    ConfluxConfig.MIN_WAYPOINT_LABEL_SCALE_PERCENT,
                    ConfluxConfig.MAX_WAYPOINT_LABEL_SCALE_PERCENT,
                    () -> config.waypointLabelScalePercent,
                    v -> config.waypointLabelScalePercent = v,
                    ConfigValueLabels::percentText
                );
                break;
            case PREDICTION:
                final String underlayReason = screen.predictionAccess.disabledReasonKey(
                    PredictionControl.UNDERLAY
                );
                final String syncReason = screen.predictionAccess.disabledReasonKey(
                    PredictionControl.NETWORK_SYNC
                );
                final String structureReason = screen.predictionAccess.disabledReasonKey(
                    PredictionControl.STRUCTURES
                );
                y = addActionRow(
                    y,
                    "confluxmap.config.prediction.manual_seed",
                    () -> MinecraftAccess.setScreen(
                        MinecraftClient.getInstance(), new ManualSeedScreen(screen)
                    ),
                    screen.manualSeedAvailable,
                    screen.manualSeedAvailable ? null : "confluxmap.screen.config.prediction.seed_disabled_by_server"
                );
                y = addToggleRow(
                    y, "confluxmap.config.prediction.enabled",
                    () -> config.predictionEnabled, v -> config.predictionEnabled = v,
                    underlayReason == null, underlayReason
                );
                y = addToggleRow(
                    y, "confluxmap.config.prediction.network_sync",
                    () -> config.predictionNetworkSync, v -> config.predictionNetworkSync = v,
                    syncReason == null, syncReason
                );
                y = addToggleRow(
                    y, "confluxmap.config.prediction.show_structures",
                    () -> config.predictionShowStructures, v -> config.predictionShowStructures = v,
                    structureReason == null, structureReason
                );
                y = addDecimalSliderRow(
                    y, "confluxmap.config.prediction.structure_icon_detail_limit",
                    ConfluxConfig.MIN_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM,
                    ConfluxConfig.MAX_PREDICTION_STRUCTURE_ICON_HIDE_ZOOM,
                    DecimalSliderValue.CONTINUOUS,
                    () -> config.predictionStructureIconHideZoom,
                    v -> config.predictionStructureIconHideZoom = v,
                    ConfigValueLabels::structureIconDetailLimitText,
                    structureReason == null, structureReason
                );
                y = addEnumRow(
                    y, "confluxmap.config.prediction.view_mode", PredictionViewMode.values(),
                    () -> config.predictionViewMode,
                    v -> {
                        config.predictionViewMode = v;
                        ConfluxMapClient.get().predictionTileService().setViewMode(v);
                    }, ConfigValueLabels::predictionViewModeKey,
                    underlayReason == null, underlayReason
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.prediction.debounce", 100, 2000,
                    () -> config.predictionDebounceMs, v -> config.predictionDebounceMs = v,
                    ConfigValueLabels::plainText, underlayReason == null, underlayReason
                );
                break;
            case GENERAL:
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.snapshot_budget", 1, 64,
                    () -> config.snapshotBudgetPerTick, v -> config.snapshotBudgetPerTick = v, ConfigValueLabels::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.recapture_holdoff", 0, 100,
                    () -> config.recaptureHoldoffTicks, v -> config.recaptureHoldoffTicks = v,
                    ConfigValueLabels::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.gpu_tile_cache_limit", 16, 2048,
                    () -> config.gpuTileCacheLimit, v -> config.gpuTileCacheLimit = v, ConfigValueLabels::plainText
                );
                y = addEnumRow(
                    y, "confluxmap.config.performance.worker_threads",
                    ConfluxConfig.WorkerThreadMode.values(),
                    () -> config.workerThreadMode,
                    v -> {
                        config.workerThreadMode = v;
                        configIo.save(config);
                        // The custom-count row only exists in CUSTOM mode; rebuild so it
                        // appears or disappears under the button just clicked.
                        screen.rebuild();
                    },
                    ConfigValueLabels::workerThreadModeKey,
                    "confluxmap.config.performance.worker_threads.tooltip"
                );
                if (config.workerThreadMode == ConfluxConfig.WorkerThreadMode.CUSTOM) {
                    y = addIntSliderRow(
                        y, "confluxmap.config.performance.worker_thread_count",
                        ConfluxConfig.MIN_WORKER_THREADS, ConfluxConfig.MAX_WORKER_THREADS,
                        () -> config.workerThreadCount, v -> config.workerThreadCount = v,
                        ConfigValueLabels::plainText,
                        "confluxmap.config.performance.worker_threads.tooltip"
                    );
                }
                y = addToggleRow(
                    y, "confluxmap.config.performance.update_check",
                    () -> config.updateCheckEnabled, v -> config.updateCheckEnabled = v
                );
                break;
            default:
                break;
        }
        return y;
    }

    /**
     * Non-interactive section header that breaks a long category list into functional
     * groups. Occupies a full {@code ROW_HEIGHT} slot so scrolling stays row-aligned;
     * the label itself is drawn in {@code renderContents} like the icon-texture labels.
     */
    private int addSectionRow(final int y, final String labelKey) {
        if (screen.rowVisible(y)) {
            sectionLabels.add(new SectionLabel(y, labelKey));
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    private int addToggleRow(final int y, final String labelKey, final BooleanSupplier getter, final Consumer<Boolean> setter) {
        return addToggleRow(y, labelKey, getter, setter, true);
    }

    private int addToggleRow(
        final int y,
        final String labelKey,
        final BooleanSupplier getter,
        final Consumer<Boolean> setter,
        final boolean active
    ) {
        return addToggleRow(y, labelKey, getter, setter, active, null);
    }

    private int addToggleRow(
        final int y,
        final String labelKey,
        final BooleanSupplier getter,
        final Consumer<Boolean> setter,
        final boolean active,
        final String disabledTooltipKey
    ) {
        if (screen.rowVisible(y)) {
            final ButtonWidget button = screen.addRowWidget(Widgets.button(
                screen.rowX(), y, screen.rowWidth, ConfigScreen.ROW_HEIGHT - 2,
                ConfigValueLabels.boolLabel(labelKey, getter.getAsBoolean()),
                b -> {
                    final boolean next = !getter.getAsBoolean();
                    setter.accept(next);
                    configIo.save(config);
                    b.setMessage(ConfigValueLabels.boolLabel(labelKey, next));
                }
            ));
            button.active = active;
            screen.setDisabledTooltip(button, disabledTooltipKey);
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    private <T> int addEnumRow(
        final int y,
        final String labelKey,
        final T[] values,
        final Supplier<T> getter,
        final Consumer<T> setter,
        final Function<T, String> valueKeyFn
    ) {
        return addEnumRow(y, labelKey, values, getter, setter, valueKeyFn, true, null, null);
    }

    private <T> int addEnumRow(
        final int y,
        final String labelKey,
        final T[] values,
        final Supplier<T> getter,
        final Consumer<T> setter,
        final Function<T, String> valueKeyFn,
        final String hoverTooltipKey
    ) {
        return addEnumRow(y, labelKey, values, getter, setter, valueKeyFn, true, null, hoverTooltipKey);
    }

    private <T> int addEnumRow(
        final int y,
        final String labelKey,
        final T[] values,
        final Supplier<T> getter,
        final Consumer<T> setter,
        final Function<T, String> valueKeyFn,
        final boolean active,
        final String disabledTooltipKey
    ) {
        return addEnumRow(y, labelKey, values, getter, setter, valueKeyFn, active, disabledTooltipKey, null);
    }

    private <T> int addEnumRow(
        final int y,
        final String labelKey,
        final T[] values,
        final Supplier<T> getter,
        final Consumer<T> setter,
        final Function<T, String> valueKeyFn,
        final boolean active,
        final String disabledTooltipKey,
        final String hoverTooltipKey
    ) {
        if (screen.rowVisible(y)) {
            final ButtonWidget button = screen.addRowWidget(Widgets.button(
                screen.rowX(), y, screen.rowWidth, ConfigScreen.ROW_HEIGHT - 2,
                ConfigValueLabels.enumLabel(labelKey, getter.get(), valueKeyFn),
                b -> {
                    final T next = nextValue(values, getter.get());
                    setter.accept(next);
                    configIo.save(config);
                    b.setMessage(ConfigValueLabels.enumLabel(labelKey, next, valueKeyFn));
                }
            ));
            button.active = active;
            screen.setDisabledTooltip(button, disabledTooltipKey);
            screen.setHoverTooltip(button, hoverTooltipKey);
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    /**
     * Text row for one portal kind's custom icon texture. The field writes through to the
     * shared config on every keystroke; the screen's {@code onClose()} persists it. Input
     * is limited to resource-location characters so nothing invalid can be typed, and a
     * path that no loaded pack provides falls back to the built-in icon at draw time.
     */
    private int addIconTextureRow(final int y, final String labelKey, final PortalKind kind) {
        if (screen.rowVisible(y)) {
            final Text label = Texts.translatable(labelKey);
            final int labelWidth = Math.min(screen.textRenderer().getWidth(label) + 6, screen.rowWidth / 2);
            final TextFieldWidget field = new TextFieldWidget(
                screen.textRenderer(), screen.rowX() + labelWidth, y,
                Math.max(80, screen.rowWidth - labelWidth), ConfigScreen.ROW_HEIGHT - 2, Texts.literal("")
            );
            field.setMaxLength(256);
            //#if MC>=260100
            //$$ final String[] lastValid = {config.portalIconTexture(kind)};
            //$$ field.setResponder(text -> {
            //$$     if (validIconTextureInput(text)) {
            //$$         lastValid[0] = text;
            //$$     } else {
            //$$         field.setValue(lastValid[0]);
            //$$     }
            //$$ });
            //#else
            field.setTextPredicate(ConfigRows::validIconTextureInput);
            //#endif
            Widgets.setText(field, config.portalIconTexture(kind));
            Widgets.setChangedListener(field, text -> config.setPortalIconTexture(kind, text));
            screen.addRowWidget(field);
            screen.setHoverTooltip(field, "confluxmap.config.portals.icon_texture.tooltip");
            textFields.add(field);
            iconTextureLabels.add(new IconTextureLabel(y, labelKey));
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    /** Vanilla resource-location charset with at most one namespace separator. */
    private static boolean validIconTextureInput(final String text) {
        if (text.indexOf(':') != text.lastIndexOf(':')) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            final char c = text.charAt(i);
            final boolean allowed = c == ':' || c == '_' || c == '.' || c == '-' || c == '/'
                || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
            if (!allowed) {
                return false;
            }
        }
        return true;
    }

    private int addZoomRow(final int y) {
        if (screen.rowVisible(y)) {
            screen.addRowWidget(Widgets.button(
                screen.rowX(), y, screen.rowWidth, ConfigScreen.ROW_HEIGHT - 2,
                ConfigValueLabels.zoomLabel(config.minimapZoomIndex),
                b -> {
                    config.cycleMinimapZoom();
                    configIo.save(config);
                    b.setMessage(ConfigValueLabels.zoomLabel(config.minimapZoomIndex));
                }
            ));
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    private int addActionRow(final int y, final String labelKey, final Runnable action) {
        return addActionRow(y, labelKey, action, true, null);
    }

    private int addActionRow(
        final int y,
        final String labelKey,
        final Runnable action,
        final boolean active,
        final String disabledTooltipKey
    ) {
        if (screen.rowVisible(y)) {
            final ButtonWidget button = screen.addRowWidget(Widgets.button(
                screen.rowX(), y, screen.rowWidth, ConfigScreen.ROW_HEIGHT - 2,
                Texts.translatable(labelKey), ignored -> action.run()
            ));
            button.active = active;
            screen.setDisabledTooltip(button, disabledTooltipKey);
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    private int addIntSliderRow(
        final int y,
        final String labelKey,
        final int min,
        final int max,
        final IntSupplier getter,
        final IntConsumer setter,
        final IntFunction<String> valueText
    ) {
        return addIntSliderRow(y, labelKey, min, max, getter, setter, valueText, true);
    }

    private int addIntSliderRow(
        final int y,
        final String labelKey,
        final int min,
        final int max,
        final IntSupplier getter,
        final IntConsumer setter,
        final IntFunction<String> valueText,
        final String hoverTooltipKey
    ) {
        return addIntSliderRow(y, labelKey, min, max, getter, setter, valueText, true, null, hoverTooltipKey);
    }

    private int addIntSliderRow(
        final int y,
        final String labelKey,
        final int min,
        final int max,
        final IntSupplier getter,
        final IntConsumer setter,
        final IntFunction<String> valueText,
        final boolean active
    ) {
        return addIntSliderRow(y, labelKey, min, max, getter, setter, valueText, active, null);
    }

    private int addIntSliderRow(
        final int y,
        final String labelKey,
        final int min,
        final int max,
        final IntSupplier getter,
        final IntConsumer setter,
        final IntFunction<String> valueText,
        final boolean active,
        final String disabledTooltipKey
    ) {
        return addIntSliderRow(y, labelKey, min, max, getter, setter, valueText, active, disabledTooltipKey, null);
    }

    private int addIntSliderRow(
        final int y,
        final String labelKey,
        final int min,
        final int max,
        final IntSupplier getter,
        final IntConsumer setter,
        final IntFunction<String> valueText,
        final boolean active,
        final String disabledTooltipKey,
        final String hoverTooltipKey
    ) {
        if (screen.rowVisible(y)) {
            final IntSliderInput sliderInput = new IntSliderInput(
                screen.textRenderer(),
                screen.rowX(),
                y,
                screen.rowWidth,
                ConfigScreen.ROW_HEIGHT - 2,
                min,
                max,
                getter.getAsInt(),
                value -> {
                    setter.accept(value);
                    configIo.save(config);
                },
                value -> Texts.translatable(labelKey, valueText.apply(value))
            );
            sliderInput.setActive(active);
            sliderInputs.add(sliderInput);
            screen.addRowWidget(sliderInput.slider());
            screen.addRowWidget(sliderInput.input());
            screen.setDisabledTooltip(sliderInput.slider(), disabledTooltipKey);
            screen.setDisabledTooltip(sliderInput.input(), disabledTooltipKey);
            screen.setHoverTooltip(sliderInput.slider(), hoverTooltipKey);
            screen.setHoverTooltip(sliderInput.input(), hoverTooltipKey);
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    private int addDecimalSliderRow(
        final int y,
        final String labelKey,
        final double min,
        final double max,
        final double step,
        final DoubleSupplier getter,
        final DoubleConsumer setter,
        final DoubleFunction<String> valueText,
        final boolean active,
        final String disabledTooltipKey
    ) {
        if (screen.rowVisible(y)) {
            final DecimalSliderInput sliderInput = new DecimalSliderInput(
                screen.textRenderer(),
                screen.rowX(),
                y,
                screen.rowWidth,
                ConfigScreen.ROW_HEIGHT - 2,
                min,
                max,
                step,
                getter.getAsDouble(),
                value -> {
                    setter.accept(value);
                    configIo.save(config);
                },
                value -> Texts.translatable(labelKey, valueText.apply(value))
            );
            sliderInput.setActive(active);
            decimalSliderInputs.add(sliderInput);
            screen.addRowWidget(sliderInput.slider());
            screen.addRowWidget(sliderInput.input());
            screen.setDisabledTooltip(sliderInput.slider(), disabledTooltipKey);
            screen.setDisabledTooltip(sliderInput.input(), disabledTooltipKey);
        }
        return y + ConfigScreen.ROW_HEIGHT;
    }

    private static <T> T nextValue(final T[] values, final T current) {
        final int index = Arrays.asList(values).indexOf(current);
        return values[(index + 1) % values.length];
    }
}
