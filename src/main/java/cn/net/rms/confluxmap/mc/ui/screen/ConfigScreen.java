package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.compat.Widgets;
import cn.net.rms.confluxmap.core.config.ConfigIo;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.color.MapColorStyle;
import cn.net.rms.confluxmap.core.net.shared.SharedWaypointAvailability;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionViewMode;
import cn.net.rms.confluxmap.mc.net.CompanionSession;
import cn.net.rms.confluxmap.mc.net.shared.SharedWaypointClient;
import cn.net.rms.confluxmap.mc.predict.ManualSeedService;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
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
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.StringVisitable;
import net.minecraft.text.Text;

/**
 * Settings screen exposing every {@link ConfluxConfig} field, grouped into category
 * tabs (Minimap/Layers/Radar/Waypoints/Performance). Built entirely from vanilla
 * widgets, no external config-lib dependency, matching {@link WaypointListScreen}/
 * {@link WaypointEditScreen}'s style: plain {@link ButtonWidget}s that cycle through
 * boolean/enum values on click, and paired sliders/numeric fields for int ranges.
 *
 * <p>Every change mutates and saves the shared {@link ConfluxConfig} immediately, so
 * every other system observes the same value without a separate apply step.
 *
 * <p>Opened via the {@code key.confluxmap.open_config} keybind (comma by default,
 * see {@code mc.input.Keybinds}) or the fullscreen map's settings button. The latter
 * supplies a parent screen so closing settings returns to the map.
 */
public final class ConfigScreen extends ConfluxScreen {
    private enum Category {
        MINIMAP("confluxmap.screen.config.category.minimap"),
        LAYERS("confluxmap.screen.config.category.layers"),
        RADAR("confluxmap.screen.config.category.radar"),
        WAYPOINTS("confluxmap.screen.config.category.waypoints"),
        PERFORMANCE("confluxmap.screen.config.category.performance"),
        PREDICTION("confluxmap.screen.config.category.prediction");

        private final String labelKey;

        Category(final String labelKey) {
            this.labelKey = labelKey;
        }
    }

    enum RadarSettingsAccess {
        ALLOWED(true, null),
        FORBIDDEN_BY_SERVER(false, "confluxmap.screen.config.radar.disabled_by_server");

        private final boolean controlsActive;
        private final String noticeKey;

        RadarSettingsAccess(final boolean controlsActive, final String noticeKey) {
            this.controlsActive = controlsActive;
            this.noticeKey = noticeKey;
        }

        static RadarSettingsAccess from(final boolean serverAllowsRadar) {
            return serverAllowsRadar ? ALLOWED : FORBIDDEN_BY_SERVER;
        }

        boolean controlsActive() {
            return controlsActive;
        }

        String noticeKey() {
            return noticeKey;
        }

        String tooltipKey() {
            return noticeKey;
        }
    }

    enum PredictionControl { UNDERLAY, NETWORK_SYNC, STRUCTURES }

    record PlayerMarkerSettingsAccess(boolean resourceOverride) {
        static PlayerMarkerSettingsAccess from(final boolean resourceOverride) {
            return new PlayerMarkerSettingsAccess(resourceOverride);
        }

        boolean controlsActive() {
            return !resourceOverride;
        }

        String tooltipKey() {
            return resourceOverride
                ? "confluxmap.config.player_marker.disabled_by_resource_pack"
                : null;
        }
    }

    record PredictionSettingsAccess(
        boolean underlayDisabledByServer,
        String networkSyncDisabledReasonKey,
        boolean structuresDisabledByServer,
        boolean structureSearchAllowed
    ) {
        static PredictionSettingsAccess from(
            final boolean singleplayer,
            final boolean seedIndependentUnderlay,
            final boolean seedSharingDisabledByServer,
            final String networkSyncDisabledReasonKey,
            final boolean structureSearchAllowed
        ) {
            return from(
                singleplayer,
                seedIndependentUnderlay,
                seedSharingDisabledByServer,
                false,
                networkSyncDisabledReasonKey,
                structureSearchAllowed
            );
        }

        static PredictionSettingsAccess from(
            final boolean singleplayer,
            final boolean seedIndependentUnderlay,
            final boolean seedSharingDisabledByServer,
            final boolean manualSeedAvailable,
            final String networkSyncDisabledReasonKey,
            final boolean structureSearchAllowed
        ) {
            final boolean remoteSeedDisabled = !singleplayer
                && seedSharingDisabledByServer
                && !manualSeedAvailable;
            return new PredictionSettingsAccess(
                remoteSeedDisabled && !seedIndependentUnderlay,
                networkSyncDisabledReasonKey,
                remoteSeedDisabled,
                structureSearchAllowed
            );
        }

        String disabledReasonKey(final PredictionControl control) {
            return switch (control) {
                case UNDERLAY -> underlayDisabledByServer
                    ? "confluxmap.screen.config.prediction.seed_disabled_by_server"
                    : null;
                case NETWORK_SYNC -> networkSyncDisabledReasonKey;
                case STRUCTURES -> !structureSearchAllowed
                    ? "confluxmap.map.structure_search.disabled_by_server"
                    : structuresDisabledByServer
                        ? "confluxmap.screen.config.prediction.seed_disabled_by_server"
                        : null;
            };
        }
    }

    private static final int MARGIN = 8;
    private static final int TAB_Y = 24;
    private static final int TAB_HEIGHT = 20;
    private static final int TAB_GAP = 4;
    private static final int ROW_HEIGHT = 22;
    private static final int MAX_ROW_WIDTH = 280;
    private static final int BOTTOM_MARGIN = 30;
    private static final int RADAR_NOTICE_PADDING = 4;
    private static final int NOTICE_TEXT_COLOR = 0xFFFFAA00;

    private static final String[] ZOOM_VALUE_KEYS = {
        "confluxmap.value.zoom_0_5",
        "confluxmap.value.zoom_1",
        "confluxmap.value.zoom_2",
        "confluxmap.value.zoom_4"
    };

    private final Screen parent;
    private final ConfluxConfig config;
    private final ConfigIo configIo;
    private final CompanionSession companionSession;
    private final SharedWaypointClient sharedWaypoints;
    private final GameBridge gameBridge;
    private final PredictionState predictionState;
    private final ManualSeedService manualSeedService;
    private final UiResourceTheme uiTheme;
    private final List<IntSliderInput> sliderInputs = new ArrayList<>();
    private final List<DecimalSliderInput> decimalSliderInputs = new ArrayList<>();

    private Category category = Category.MINIMAP;
    private int rowWidth = MAX_ROW_WIDTH;
    /** Rows scroll in ROW_HEIGHT steps; widgets outside the viewport are simply not built. */
    private int scrollOffset;
    private int contentHeight;
    private SharedWaypointAvailability sharedAvailability;
    private RadarSettingsAccess radarAccess = RadarSettingsAccess.ALLOWED;
    private PredictionSettingsAccess predictionAccess;
    private PlayerMarkerSettingsAccess playerMarkerAccess =
        PlayerMarkerSettingsAccess.from(false);
    private boolean manualSeedAvailable;

    public ConfigScreen() {
        this(null);
    }

    public ConfigScreen(final Screen parent) {
        super(Texts.translatable("confluxmap.screen.config.title"));
        this.parent = parent;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.config = app.config();
        this.configIo = app.configIo();
        this.companionSession = app.companionSession();
        this.sharedWaypoints = app.sharedWaypoints();
        this.gameBridge = app.gameBridge();
        this.predictionState = app.predictionState();
        this.manualSeedService = app.manualSeedService();
        this.uiTheme = app.uiResourceTheme();
    }

    /** Keep the world (and this session's capture pipeline) running while the screen is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    @Override
    protected void init() {
        rowWidth = Math.min(MAX_ROW_WIDTH, width - MARGIN * 2);
        scrollOffset = 0;
        manualSeedAvailable = manualSeedService.available();
        rebuild();
    }

    @Override
    public void tick() {
        super.tick();
        for (final IntSliderInput sliderInput : sliderInputs) {
            sliderInput.tick();
        }
        for (final DecimalSliderInput sliderInput : decimalSliderInputs) {
            sliderInput.tick();
        }
        final SharedWaypointAvailability availability = sharedWaypoints.availability();
        if (!availability.equals(sharedAvailability)) {
            sharedAvailability = availability;
            if (category == Category.WAYPOINTS) {
                rebuild();
            }
        }
        final RadarSettingsAccess currentRadarAccess = RadarSettingsAccess.from(
            companionSession.entityRadarAllowed()
        );
        if (currentRadarAccess != radarAccess) {
            radarAccess = currentRadarAccess;
            if (category == Category.RADAR) {
                rebuild();
            }
        }
        final PredictionSettingsAccess currentPredictionAccess = predictionSettingsAccess();
        final boolean currentManualSeedAvailable = manualSeedService.available();
        if (!currentPredictionAccess.equals(predictionAccess)
            || currentManualSeedAvailable != manualSeedAvailable) {
            predictionAccess = currentPredictionAccess;
            manualSeedAvailable = currentManualSeedAvailable;
            if (category == Category.PREDICTION) {
                rebuild();
            }
        }
        final PlayerMarkerSettingsAccess currentPlayerMarkerAccess = playerMarkerSettingsAccess();
        if (!currentPlayerMarkerAccess.equals(playerMarkerAccess)) {
            playerMarkerAccess = currentPlayerMarkerAccess;
            if (category == Category.MINIMAP) {
                rebuild();
            }
        }
    }

    private int viewportHeight() {
        return Math.max(ROW_HEIGHT, height - BOTTOM_MARGIN - rowsTop());
    }

    private boolean rowVisible(final int y) {
        return y >= rowsTop() && y + ROW_HEIGHT - 2 <= height - BOTTOM_MARGIN;
    }

    private int rowsTop() {
        final int baseRowsTop = tabLayout().contentTop();
        return category == Category.RADAR && radarAccess.noticeKey() != null
            ? baseRowsTop + radarNoticeHeight()
            : baseRowsTop;
    }

    private int radarNoticeHeight() {
        final String notice = Texts.translatable(radarAccess.noticeKey()).getString();
        final int lines = this.textRenderer.wrapLines(
            StringVisitable.plain(notice), Math.max(40, rowWidth)
        ).size();
        return lines * (this.textRenderer.fontHeight + 1) + RADAR_NOTICE_PADDING;
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
        final boolean overRows = mouseX >= rowX() && mouseX <= rowX() + rowWidth + 8
            && mouseY >= rowsTop() && mouseY <= height - BOTTOM_MARGIN;
        if (amount != 0 && overRows) {
            final int maxScroll = Math.max(0, contentHeight - viewportHeight());
            final int next = Math.max(
                0,
                Math.min(maxScroll, scrollOffset - (int) Math.signum(amount) * ROW_HEIGHT)
            );
            if (next != scrollOffset) {
                scrollOffset = next;
                rebuild();
            }
            return true;
        }
        //#if MC>=12002
        //$$ return super.mouseScrolled(mouseX, mouseY, horizontalAmount, amount);
        //#else
        return super.mouseScrolled(mouseX, mouseY, amount);
        //#endif
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or the Done button below). */
    @Override
    public void onClose() {
        configIo.save(config);
        if (parent == null) {
            super.onClose();
        } else {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), parent);
        }
    }

    private void rebuild() {
        sharedAvailability = sharedWaypoints.availability();
        radarAccess = RadarSettingsAccess.from(companionSession.entityRadarAllowed());
        predictionAccess = predictionSettingsAccess();
        playerMarkerAccess = playerMarkerSettingsAccess();
        manualSeedAvailable = manualSeedService.available();
        sliderInputs.clear();
        decimalSliderInputs.clear();
        clearChildren();
        addTabs();
        addRows();
        final int bottomButtonWidth = Math.min(140, Math.max(80, (width - MARGIN * 2 - TAB_GAP) / 2));
        final int bottomButtonsWidth = bottomButtonWidth * 2 + TAB_GAP;
        final int bottomButtonsX = width / 2 - bottomButtonsWidth / 2;
        addDrawableChild(Widgets.button(
            bottomButtonsX, height - BOTTOM_MARGIN + 4, bottomButtonWidth, 20,
            Texts.translatable("confluxmap.screen.config.hotkeys"),
            b -> ConfluxMapClient.get().keybinds().openHotkeySettings(this)
        ));
        addDrawableChild(Widgets.button(
            bottomButtonsX + bottomButtonWidth + TAB_GAP,
            height - BOTTOM_MARGIN + 4,
            bottomButtonWidth,
            20,
            Texts.translatable("confluxmap.screen.waypoint.done"), b -> onClose()
        ));
    }

    private void addTabs() {
        final Category[] categories = Category.values();
        final ConfigTabLayout layout = tabLayout();
        final int startX = width / 2 - layout.totalWidth() / 2;
        for (int index = 0; index < categories.length; index++) {
            final Category c = categories[index];
            final Text label = c == category
                ? Texts.literal("[" + Texts.translatable(c.labelKey).getString() + "]")
                : Texts.translatable(c.labelKey);
            final ButtonWidget tab = Widgets.button(
                startX + index % layout.columns() * (layout.tabWidth() + TAB_GAP),
                TAB_Y + index / layout.columns() * (TAB_HEIGHT + TAB_GAP),
                layout.tabWidth(),
                TAB_HEIGHT,
                label,
                b -> selectCategory(c)
            );
            addDrawableChild(tab);
        }
    }

    private ConfigTabLayout tabLayout() {
        return ConfigTabLayout.fit(
            width, Category.values().length, MARGIN, TAB_GAP, TAB_Y, TAB_HEIGHT
        );
    }

    private void selectCategory(final Category c) {
        category = c;
        scrollOffset = 0;
        rebuild();
    }

    private void addRows() {
        final int rowsTop = rowsTop();
        int y = rowsTop - scrollOffset;
        switch (category) {
            case MINIMAP:
                y = addToggleRow(y, "confluxmap.config.minimap.enabled", () -> config.minimapEnabled, v -> config.minimapEnabled = v);
                y = addActionRow(
                    y,
                    "confluxmap.config.minimap.position",
                    () -> MinecraftAccess.setScreen(MinecraftClient.getInstance(), new MinimapPositionScreen(this, config, configIo))
                );
                y = addToggleRow(
                    y,
                    "confluxmap.config.minimap.hud_avoidance",
                    () -> config.minimapHudAvoidance,
                    v -> config.minimapHudAvoidance = v
                );
                y = addEnumRow(
                    y, "confluxmap.config.minimap.shape", ConfluxConfig.Shape.values(),
                    () -> config.minimapShape, v -> config.minimapShape = v, ConfigScreen::shapeKey
                );
                y = addEnumRow(
                    y,
                    "confluxmap.config.player_marker.style",
                    ConfluxConfig.PlayerMarkerStyle.values(),
                    () -> config.playerMarkerStyle,
                    v -> config.playerMarkerStyle = v,
                    style -> playerMarkerAccess.resourceOverride()
                        ? "confluxmap.value.player_marker.resource_pack"
                        : playerMarkerStyleKey(style),
                    playerMarkerAccess.controlsActive(),
                    playerMarkerAccess.tooltipKey()
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.minimap.size", 64, 256,
                    () -> config.minimapSize, v -> config.minimapSize = v, ConfigScreen::pxText
                );
                y = addToggleRow(y, "confluxmap.config.minimap.rotate", () -> config.minimapRotate, v -> config.minimapRotate = v);
                y = addZoomRow(y);
                y = addToggleRow(y, "confluxmap.config.minimap.show_coordinates", () -> config.showCoordinates, v -> config.showCoordinates = v);
                y = addToggleRow(y, "confluxmap.config.minimap.show_biome", () -> config.showBiome, v -> config.showBiome = v);
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
                    ConfigScreen::secondsText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.player_trail.dot_size",
                    ConfluxConfig.MIN_PLAYER_TRAIL_DOT_SIZE,
                    ConfluxConfig.MAX_PLAYER_TRAIL_DOT_SIZE,
                    () -> config.playerTrailDotSize,
                    v -> config.playerTrailDotSize = v,
                    ConfigScreen::pxText
                );
                y = addToggleRow(y, "confluxmap.config.fullmap.chunk_grid", () -> config.fullmapChunkGrid, v -> config.fullmapChunkGrid = v);
                y = addToggleRow(
                    y, "confluxmap.config.minimap.annotations",
                    () -> config.annotationsOnHud, v -> config.annotationsOnHud = v
                );
                break;
            case LAYERS:
                y = addEnumRow(
                    y, "confluxmap.config.layers.override", ConfluxConfig.LayerOverride.values(),
                    () -> config.layerOverride, v -> config.layerOverride = v, ConfigScreen::layerOverrideKey
                );
                y = addToggleRow(y, "confluxmap.config.layers.show_indicator", () -> config.showLayerIndicator, v -> config.showLayerIndicator = v);
                y = addIntSliderRow(
                    y, "confluxmap.config.layers.cave_slice_y", 0, 255,
                    () -> config.caveSliceY, v -> config.caveSliceY = v, ConfigScreen::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.layers.nether_slice_y", 0, 127,
                    () -> config.netherSliceY, v -> config.netherSliceY = v, ConfigScreen::plainText
                );
                y = addToggleRow(
                    y, "confluxmap.config.map.dynamic_lighting", () -> config.dynamicLighting, v -> config.dynamicLighting = v
                );
                y = addEnumRow(
                    y, "confluxmap.config.map.color_style", MapColorStyle.values(),
                    () -> config.mapColorStyle,
                    v -> {
                        config.mapColorStyle = v;
                        ConfluxMapClient.get().onMapColorStyleChanged();
                    },
                    ConfigScreen::mapColorStyleKey
                );
                break;
            case RADAR:
                final boolean radarControlsActive = radarAccess.controlsActive();
                final String radarTooltipKey = radarAccess.tooltipKey();
                y = addToggleRow(
                    y, "confluxmap.config.radar.enabled",
                    () -> config.radarEnabled, v -> config.radarEnabled = v,
                    radarControlsActive, radarTooltipKey
                );
                y = addEnumRow(
                    y, "confluxmap.config.radar.display_mode",
                    ConfluxConfig.RadarDisplayMode.values(),
                    () -> config.radarDisplayMode, v -> config.radarDisplayMode = v,
                    ConfigScreen::radarDisplayModeKey,
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
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.max_entities", 1, 500,
                    () -> config.radarMaxEntities, v -> config.radarMaxEntities = v,
                    ConfigScreen::plainText, radarControlsActive, radarTooltipKey
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
                    ConfigScreen::pxText, radarControlsActive, radarTooltipKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.icon_size",
                    ConfluxConfig.MIN_RADAR_ICON_SIZE, ConfluxConfig.MAX_RADAR_ICON_SIZE,
                    () -> config.radarIconSize, v -> config.radarIconSize = v,
                    ConfigScreen::pxText, radarControlsActive, radarTooltipKey
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.radar.player_highlight_ghost_duration",
                    ConfluxConfig.MIN_RADAR_PLAYER_HIGHLIGHT_GHOST_SECONDS,
                    ConfluxConfig.MAX_RADAR_PLAYER_HIGHLIGHT_GHOST_SECONDS,
                    () -> config.radarPlayerHighlightGhostSeconds,
                    v -> config.radarPlayerHighlightGhostSeconds = v,
                    ConfigScreen::secondsText, radarControlsActive, radarTooltipKey
                );
                break;
            case WAYPOINTS:
                y = addActionRow(
                    y,
                    "confluxmap.config.waypoints.teleport_command",
                    () -> MinecraftAccess.setScreen(
                        MinecraftClient.getInstance(),
                        new TeleportCommandScreen(this, config, configIo)
                    )
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.show_local",
                    () -> config.localWaypointsVisible, v -> config.localWaypointsVisible = v
                );
                if (sharedAvailability.visible()) {
                    y = addToggleRow(
                        y, "confluxmap.config.waypoints.show_shared",
                        () -> config.sharedWaypointsVisible, v -> config.sharedWaypointsVisible = v,
                        sharedAvailability.ready(),
                        sharedAvailability.disabledByServer()
                            ? "confluxmap.shared_waypoints.disabled_by_server"
                            : null
                    );
                }
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.render_distance", 0, 100_000,
                    () -> config.waypointRenderDistance, v -> config.waypointRenderDistance = v, ConfigScreen::renderDistanceText
                );
                y = addToggleRow(
                    y, "confluxmap.config.waypoints.edge_indicators",
                    () -> config.waypointEdgeIndicatorsEnabled, v -> config.waypointEdgeIndicatorsEnabled = v
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.death_points_kept", 0, 50,
                    () -> config.deathPointsKept, v -> config.deathPointsKept = v, ConfigScreen::plainText
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
                    y, "confluxmap.config.waypoints.icon_opacity",
                    ConfluxConfig.MIN_WAYPOINT_ICON_OPACITY, ConfluxConfig.MAX_WAYPOINT_ICON_OPACITY,
                    () -> config.waypointIconOpacity, v -> config.waypointIconOpacity = v,
                    ConfigScreen::percentText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.highlight_dim_opacity",
                    ConfluxConfig.MIN_WAYPOINT_HIGHLIGHT_DIM_OPACITY,
                    ConfluxConfig.MAX_WAYPOINT_HIGHLIGHT_DIM_OPACITY,
                    () -> config.waypointHighlightDimOpacity,
                    v -> config.waypointHighlightDimOpacity = v,
                    ConfigScreen::percentText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.waypoints.label_scale",
                    ConfluxConfig.MIN_WAYPOINT_LABEL_SCALE_PERCENT,
                    ConfluxConfig.MAX_WAYPOINT_LABEL_SCALE_PERCENT,
                    () -> config.waypointLabelScalePercent,
                    v -> config.waypointLabelScalePercent = v,
                    ConfigScreen::percentText
                );
                break;
            case PERFORMANCE:
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.snapshot_budget", 1, 64,
                    () -> config.snapshotBudgetPerTick, v -> config.snapshotBudgetPerTick = v, ConfigScreen::plainText
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.performance.gpu_tile_cache_limit", 16, 2048,
                    () -> config.gpuTileCacheLimit, v -> config.gpuTileCacheLimit = v, ConfigScreen::plainText
                );
                y = addToggleRow(
                    y, "confluxmap.config.performance.update_check",
                    () -> config.updateCheckEnabled, v -> config.updateCheckEnabled = v
                );
                break;
            case PREDICTION:
                final String underlayReason = predictionAccess.disabledReasonKey(
                    PredictionControl.UNDERLAY
                );
                final String syncReason = predictionAccess.disabledReasonKey(
                    PredictionControl.NETWORK_SYNC
                );
                final String structureReason = predictionAccess.disabledReasonKey(
                    PredictionControl.STRUCTURES
                );
                y = addActionRow(
                    y,
                    "confluxmap.config.prediction.manual_seed",
                    () -> MinecraftAccess.setScreen(
                        MinecraftClient.getInstance(), new ManualSeedScreen(this)
                    ),
                    manualSeedAvailable,
                    manualSeedAvailable ? null : "confluxmap.screen.config.prediction.seed_disabled_by_server"
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
                    ConfigScreen::structureIconDetailLimitText,
                    structureReason == null, structureReason
                );
                y = addEnumRow(
                    y, "confluxmap.config.prediction.view_mode", PredictionViewMode.values(),
                    () -> config.predictionViewMode,
                    v -> {
                        config.predictionViewMode = v;
                        ConfluxMapClient.get().predictionTileService().setViewMode(v);
                    }, ConfigScreen::predictionViewModeKey,
                    underlayReason == null, underlayReason
                );
                y = addIntSliderRow(
                    y, "confluxmap.config.prediction.debounce", 100, 2000,
                    () -> config.predictionDebounceMs, v -> config.predictionDebounceMs = v,
                    ConfigScreen::plainText, underlayReason == null, underlayReason
                );
                break;
            default:
                break;
        }
        contentHeight = y + scrollOffset - rowsTop;
    }

    private int rowX() {
        return width / 2 - rowWidth / 2;
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
        if (rowVisible(y)) {
            final ButtonWidget button = addDrawableChild(Widgets.button(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, boolLabel(labelKey, getter.getAsBoolean()),
                b -> {
                    final boolean next = !getter.getAsBoolean();
                    setter.accept(next);
                    configIo.save(config);
                    b.setMessage(boolLabel(labelKey, next));
                }
            ));
            button.active = active;
            setDisabledTooltip(button, disabledTooltipKey);
        }
        return y + ROW_HEIGHT;
    }

    private <T> int addEnumRow(
        final int y,
        final String labelKey,
        final T[] values,
        final Supplier<T> getter,
        final Consumer<T> setter,
        final Function<T, String> valueKeyFn
    ) {
        return addEnumRow(y, labelKey, values, getter, setter, valueKeyFn, true, null);
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
        if (rowVisible(y)) {
            final ButtonWidget button = addDrawableChild(Widgets.button(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, enumLabel(labelKey, getter.get(), valueKeyFn),
                b -> {
                    final T next = nextValue(values, getter.get());
                    setter.accept(next);
                    configIo.save(config);
                    b.setMessage(enumLabel(labelKey, next, valueKeyFn));
                }
            ));
            button.active = active;
            setDisabledTooltip(button, disabledTooltipKey);
        }
        return y + ROW_HEIGHT;
    }

    private int addZoomRow(final int y) {
        if (rowVisible(y)) {
            addDrawableChild(Widgets.button(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, zoomLabel(config.minimapZoomIndex),
                b -> {
                    config.cycleMinimapZoom();
                    configIo.save(config);
                    b.setMessage(zoomLabel(config.minimapZoomIndex));
                }
            ));
        }
        return y + ROW_HEIGHT;
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
        if (rowVisible(y)) {
            final ButtonWidget button = addDrawableChild(Widgets.button(
                rowX(), y, rowWidth, ROW_HEIGHT - 2, Texts.translatable(labelKey), ignored -> action.run()
            ));
            button.active = active;
            setDisabledTooltip(button, disabledTooltipKey);
        }
        return y + ROW_HEIGHT;
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
        if (rowVisible(y)) {
            final IntSliderInput sliderInput = new IntSliderInput(
                this.textRenderer,
                rowX(),
                y,
                rowWidth,
                ROW_HEIGHT - 2,
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
            addDrawableChild(sliderInput.slider());
            addDrawableChild(sliderInput.input());
            setDisabledTooltip(sliderInput.slider(), disabledTooltipKey);
            setDisabledTooltip(sliderInput.input(), disabledTooltipKey);
        }
        return y + ROW_HEIGHT;
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
        if (rowVisible(y)) {
            final DecimalSliderInput sliderInput = new DecimalSliderInput(
                this.textRenderer,
                rowX(),
                y,
                rowWidth,
                ROW_HEIGHT - 2,
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
            addDrawableChild(sliderInput.slider());
            addDrawableChild(sliderInput.input());
            setDisabledTooltip(sliderInput.slider(), disabledTooltipKey);
            setDisabledTooltip(sliderInput.input(), disabledTooltipKey);
        }
        return y + ROW_HEIGHT;
    }

    private static <T> T nextValue(final T[] values, final T current) {
        final int index = Arrays.asList(values).indexOf(current);
        return values[(index + 1) % values.length];
    }

    private static Text boolLabel(final String labelKey, final boolean value) {
        return Texts.translatable(labelKey, resolvedText(value ? "confluxmap.value.on" : "confluxmap.value.off"));
    }

    private static <T> Text enumLabel(final String labelKey, final T value, final Function<T, String> valueKeyFn) {
        return Texts.translatable(labelKey, resolvedText(valueKeyFn.apply(value)));
    }

    private static Text zoomLabel(final int zoomIndex) {
        return Texts.translatable("confluxmap.config.minimap.zoom", resolvedText(ZOOM_VALUE_KEYS[zoomIndex]));
    }

    private static String resolvedText(final String key) {
        return Texts.translatable(key).getString();
    }

    private static String plainText(final int value) {
        return String.valueOf(value);
    }

    private static String pxText(final int value) {
        return Texts.translatable("confluxmap.value.px", value).getString();
    }

    private static String percentText(final int value) {
        return Texts.translatable("confluxmap.value.percent", value).getString();
    }

    private static String blocksText(final int value) {
        return Texts.translatable("confluxmap.value.blocks", value).getString();
    }

    private static String secondsText(final int value) {
        return Texts.translatable("confluxmap.value.seconds", value).getString();
    }

    private static String renderDistanceText(final int value) {
        return value == 0 ? resolvedText("confluxmap.value.unlimited") : blocksText(value);
    }

    private static String structureIconDetailLimitText(final double scale) {
        return Texts.translatable(
            "confluxmap.value.zoom_multiplier", DecimalSliderInput.format(scale)
        ).getString();
    }

    private PredictionSettingsAccess predictionSettingsAccess() {
        final boolean singleplayer = MinecraftClient.getInstance().isInSingleplayer();
        final boolean seedIndependentUnderlay = predictionState.flatBaseline(
            gameBridge.session().dimension()
        ) != null;
        return PredictionSettingsAccess.from(
            singleplayer,
            seedIndependentUnderlay,
            companionSession.seedSharingDisabledByServer(),
            manualSeedService.available(),
            companionSession.mapCorrectionDisabledReasonKey(),
            companionSession.structureSearchAllowed()
        );
    }

    private PlayerMarkerSettingsAccess playerMarkerSettingsAccess() {
        return PlayerMarkerSettingsAccess.from(uiTheme.playerMarker().isPresent());
    }

    private static String shapeKey(final ConfluxConfig.Shape shape) {
        return shape == ConfluxConfig.Shape.CIRCLE ? "confluxmap.config.shape.circle" : "confluxmap.config.shape.square";
    }

    private static String playerMarkerStyleKey(final ConfluxConfig.PlayerMarkerStyle style) {
        return style == ConfluxConfig.PlayerMarkerStyle.TRADITIONAL
            ? "confluxmap.value.player_marker.traditional"
            : "confluxmap.value.player_marker.modern";
    }

    private static String radarDisplayModeKey(final ConfluxConfig.RadarDisplayMode mode) {
        return mode == ConfluxConfig.RadarDisplayMode.DOTS
            ? "confluxmap.value.radar_display_mode.dots"
            : "confluxmap.value.radar_display_mode.portraits";
    }

    private static String layerOverrideKey(final ConfluxConfig.LayerOverride override) {
        switch (override) {
            case FORCE_SURFACE:
                return "confluxmap.config.layer_override.force_surface";
            case FORCE_UNDERGROUND:
                return "confluxmap.config.layer_override.force_underground";
            case FORCE_SLICE:
                return "confluxmap.config.layer_override.force_slice";
            default:
                return "confluxmap.config.layer_override.auto";
        }
    }

    private static String mapColorStyleKey(final MapColorStyle style) {
        return style == MapColorStyle.XAERO
            ? "confluxmap.value.map_color_style.xaero"
            : "confluxmap.value.map_color_style.conflux";
    }

    private static String predictionViewModeKey(final PredictionViewMode mode) {
        switch (mode) {
            case GENERATED_ONLY:
                return "confluxmap.config.prediction.mode.generated_only";
            case VISITED_ONLY:
                return "confluxmap.config.prediction.mode.visited_only";
            default:
                return "confluxmap.config.prediction.mode.everywhere";
        }
    }

    @Override
    protected void renderContents(final GuiDraw draw, final int mouseX, final int mouseY, final float tickDelta) {
        draw.renderBackground(this, mouseX, mouseY, tickDelta);
        final String title = getTitle().getString();
        draw.drawTextWithShadow(this.textRenderer, title, width / 2f - this.textRenderer.getWidth(title) / 2f, 8, 0xFFFFFFFF);
        if (category == Category.RADAR && radarAccess.noticeKey() != null) {
            drawRadarPolicyNotice(draw);
        }
        drawScrollbar(draw);
    }

    private void drawScrollbar(final GuiDraw draw) {
        final int viewport = viewportHeight();
        final int maxScroll = Math.max(0, contentHeight - viewport);
        if (maxScroll <= 0) {
            return;
        }
        final int top = rowsTop();
        final int trackHeight = viewport;
        final int thumbHeight = Math.max(12, trackHeight * viewport / contentHeight);
        final int thumbTravel = Math.max(1, trackHeight - thumbHeight);
        final int thumbY = top + (int) Math.round(thumbTravel * (scrollOffset / (double) maxScroll));
        final int x = rowX() + rowWidth + 4;
        draw.fill(x, top, x + 2, top + trackHeight, 0xFF3A3A3A);
        draw.fill(x, thumbY, x + 2, thumbY + thumbHeight, 0xFFFFFFFF);
    }

    private void drawRadarPolicyNotice(final GuiDraw draw) {
        final String notice = Texts.translatable(radarAccess.noticeKey()).getString();
        int y = tabLayout().contentTop() + 2;
        for (final OrderedText line : this.textRenderer.wrapLines(
            StringVisitable.plain(notice), Math.max(40, rowWidth)
        )) {
            draw.drawTextWithShadow(
                this.textRenderer, line, width / 2f - this.textRenderer.getWidth(line) / 2f,
                y, NOTICE_TEXT_COLOR
            );
            y += this.textRenderer.fontHeight + 1;
        }
    }

}
