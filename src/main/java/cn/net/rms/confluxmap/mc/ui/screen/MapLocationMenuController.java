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
import static cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen.*;

/**
 * The right-click location menu: target capture, button specs, action dispatch
 * (waypoint edit/share/delete, highlight, teleport), and its panel/hotkey drawing.
 */
final class MapLocationMenuController {
    private static final int TEMPORARY_LOCATION_COLOR = 0xFF3498DB;

    private final FullscreenMapScreen screen;
    MapLocationMenuController(final FullscreenMapScreen screen) {
        this.screen = screen;
    }

    private FullscreenMapLocationMenu.Bounds locationMenuBounds;
    private FullscreenMapLocationMenu.Target locationMenuTarget;
    private WaypointRenderEntry locationMenuWaypoint;
    private UUID locationMenuPlayerId;
    private List<FullscreenMapLocationMenu.ButtonSpec> locationMenuSpecs = List.of();
    private FullscreenMapLocationMenu.Action pendingLocationAction;
    private boolean locationMenuDeletePending;
    private ButtonWidget setWaypointLocationButton;
    private ButtonWidget editWaypointLocationButton;
    private ButtonWidget shareLocationButton;
    private ButtonWidget teleportLocationButton;
    private String teleportLocationUnavailableKey;
    private final Map<ButtonWidget, String> locationActionTooltips = new LinkedHashMap<>();

    boolean ownsTooltipWidget(final ClickableWidget widget) {
        return locationActionTooltips.containsKey(widget);
    }

    boolean hoverContains(final double mouseX, final double mouseY) {
        return locationMenuBounds != null && locationMenuBounds.contains(mouseX, mouseY);
    }

    boolean hotkeyPressed(final int keyCode) {
        if (locationMenuBounds == null) {
            return false;
        }
        final FullscreenMapLocationMenu.Action action =
            FullscreenMapLocationMenu.actionForHotkey(locationMenuSpecs, keyCode);
        if (action == null) {
            return false;
        }
        pendingLocationAction = action;
        performPendingLocationAction();
        return true;
    }

    boolean isOpen() {
        return locationMenuBounds != null;
    }

    void reset() {
        locationMenuBounds = null;
        locationMenuTarget = null;
        locationMenuWaypoint = null;
        locationMenuPlayerId = null;
        pendingLocationAction = null;
        locationMenuDeletePending = false;
    }

    void clearButtons() {
        setWaypointLocationButton = null;
        editWaypointLocationButton = null;
        shareLocationButton = null;
        teleportLocationButton = null;
        teleportLocationUnavailableKey = null;
        locationActionTooltips.clear();
    }

    boolean refreshGroundTarget() {
        if (locationMenuTarget == null || locationMenuTarget.groundKnown()) {
            return false;
        }
        final ColumnStore.SurfaceLookup ground = screen.groundAt(
            locationMenuTarget.blockX(), locationMenuTarget.blockZ()
        );
        if (!ground.known()) {
            return false;
        }
        locationMenuTarget = FullscreenMapLocationMenu.targetAt(
            locationMenuTarget.blockX(), ground, locationMenuTarget.blockZ()
        );
        return true;
    }

    void drawHotkeyHints(final GuiDraw draw) {
        if (locationMenuBounds != null) {
            FullscreenMapLocationMenu.drawHotkeyHints(
                draw, screen.font(), locationMenuBounds, locationMenuSpecs
            );
        }
    }

    Text teleportUnavailableTooltip() {
        if (teleportLocationButton == null
            || !teleportLocationButton.isHovered()
            || teleportLocationButton.active) {
            return null;
        }
        return Texts.translatable(
            teleportLocationUnavailableKey == null
                ? "confluxmap.map.location_menu.teleport_unavailable"
                : teleportLocationUnavailableKey
        );
    }

        void addLocationMenuButtons() {
        final List<FullscreenMapLocationMenu.ButtonSpec> specs = locationMenuButtonSpecs(
            locationMenuTarget, locationMenuWaypoint, locationMenuPlayerId, locationMenuDeletePending
        );
        locationMenuSpecs = specs;
        for (int index = 0; index < specs.size(); index++) {
            final FullscreenMapLocationMenu.ButtonSpec spec = specs.get(index);
            final ButtonWidget button = screen.addWidget(Widgets.button(
                locationMenuBounds.buttonX(),
                locationMenuBounds.buttonY(index),
                locationMenuBounds.buttonWidth(),
                FullscreenMapLocationMenu.BUTTON_HEIGHT,
                Texts.translatable(spec.labelKey()),
                ignored -> pendingLocationAction = spec.action()
            ));
            locationActionTooltips.put(button, spec.tooltipKey());
            button.active = spec.active();
            switch (spec.action()) {
                case SET_WAYPOINT -> setWaypointLocationButton = button;
                case EDIT_WAYPOINT -> editWaypointLocationButton = button;
                case SHARE_LOCATION -> shareLocationButton = button;
                case TELEPORT -> teleportLocationButton = button;
                case DELETE_WAYPOINT, SHARE_WAYPOINT, HIGHLIGHT, HIGHLIGHT_WAYPOINT, CLEAR_HIGHLIGHT,
                     HIGHLIGHT_PLAYER, CLEAR_PLAYER_HIGHLIGHT -> {
                }
            }
        }
    }

    /**
     * Resolves the location menu's buttons for one captured target. Shared with the embedded
     * split-map menu so both host the same actions, enablement, and tooltips.
     */
    List<FullscreenMapLocationMenu.ButtonSpec> locationMenuButtonSpecs(
        final FullscreenMapLocationMenu.Target target,
        final WaypointRenderEntry waypoint,
        final UUID playerId,
        final boolean deletePending
    ) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final boolean playerPresent = client.player != null;
        final SessionGuard.Session viewed = screen.viewSession();
        final SessionGuard.Session live = screen.gameBridge.session();
        final TeleportCommandAccess.Result teleportAccess = TeleportCommandAccess.evaluate(
            client,
            screen.config.teleportCommand,
            live,
            viewed.world(),
            viewed.dimension(),
            // A live session can always resolve the landing after the target chunk loads
            // (ground, or void at the pre-teleport Y); only a browsed session needs a
            // known screen.height up front, and an API landing resolver can supply one.
            teleportPositionKnownForMenu(target)
        );
        final boolean teleportCommandAvailable = teleportAccess.available();
        teleportLocationUnavailableKey = teleportAccess.reasonKey();
        final boolean existingWaypoint = FullscreenMapLocationMenu.isSavedWaypoint(
            playerId == null ? waypoint : null
        );
        final boolean waypointEditable = existingWaypoint && waypointEditable(waypoint);
        final String waypointDeleteUnavailableKey = existingWaypoint
            ? waypointDeleteUnavailableKey(waypoint) : null;
        final boolean waypointDeletable = existingWaypoint && waypointDeleteUnavailableKey == null;
        final List<FullscreenMapLocationMenu.Action> actions = new ArrayList<>(
            FullscreenMapLocationMenu.actions(
                existingWaypoint,
                locationMenuTargetHighlighted(waypoint, target, playerId),
                playerId != null
            )
        );
        // A seed-sibling waypoint belongs to another world namespace: teleport and highlight
        // still make sense (same seed, same terrain), but editing, deleting, and publishing
        // must go through its owning session.
        if (existingWaypoint && waypoint.sibling()) {
            actions.removeIf(action -> action == FullscreenMapLocationMenu.Action.EDIT_WAYPOINT
                || action == FullscreenMapLocationMenu.Action.DELETE_WAYPOINT
                || action == FullscreenMapLocationMenu.Action.SHARE_WAYPOINT);
        }
        final List<FullscreenMapLocationMenu.ButtonSpec> specs = new ArrayList<>(actions.size());
        for (final FullscreenMapLocationMenu.Action action : actions) {
            boolean active = FullscreenMapLocationMenu.actionEnabled(
                action, playerPresent, teleportCommandAvailable,
                waypointEditable, waypointDeletable
            );
            String tooltipKey = actionTooltipKey(action);
            if (action == FullscreenMapLocationMenu.Action.DELETE_WAYPOINT
                && !active && waypointDeleteUnavailableKey != null) {
                tooltipKey = waypointDeleteUnavailableKey;
            }
            if (!screen.viewingLiveWorld()
                && (action == FullscreenMapLocationMenu.Action.SHARE_LOCATION
                    || action == FullscreenMapLocationMenu.Action.SHARE_WAYPOINT)) {
                active = false;
            }
            if (action == FullscreenMapLocationMenu.Action.HIGHLIGHT
                || action == FullscreenMapLocationMenu.Action.HIGHLIGHT_WAYPOINT
                || action == FullscreenMapLocationMenu.Action.HIGHLIGHT_PLAYER) {
                active = (playerId != null
                    ? screen.viewingLiveWorld() : screen.viewingLiveSession())
                    && (waypoint != null || target != null);
            } else if (action == FullscreenMapLocationMenu.Action.CLEAR_HIGHLIGHT) {
                active = screen.waypointHighlightState.active();
            } else if (action == FullscreenMapLocationMenu.Action.CLEAR_PLAYER_HIGHLIGHT) {
                active = playerId != null && screen.serverPlayerRadar.isHighlighted(playerId);
            }
            specs.add(new FullscreenMapLocationMenu.ButtonSpec(
                action,
                action == FullscreenMapLocationMenu.Action.DELETE_WAYPOINT && deletePending
                    ? "confluxmap.screen.waypoints.confirm"
                    : action.translationKey(),
                tooltipKey,
                active
            ));
        }
        return specs;
    }

    private boolean locationMenuTargetHighlighted(
        final WaypointRenderEntry waypoint,
        final FullscreenMapLocationMenu.Target target,
        final UUID playerId
    ) {
        if (!screen.viewingLiveWorld()) {
            return false;
        }
        if (playerId != null) {
            return screen.serverPlayerRadar.isHighlighted(playerId);
        }
        if (waypoint != null) {
            return screen.waypointHighlightState.matchesEntry(waypoint, screen.viewSession().dimension());
        }
        return target != null && screen.waypointHighlightState.matches(
            target.blockX() + 0.5,
            target.blockZ() + 0.5,
            screen.viewSession().dimension()
        );
    }
    void openLocationMenu(final double mouseX, final double mouseY) {
        screen.annotations.closeColorMenu();
        final boolean closingDrawing = screen.toolPanel.isOpen(FullscreenMapToolPanel.Group.DRAWING);
        screen.toolPanel.close();
        if (closingDrawing) {
            screen.annotations.selectAnnotationTool(AnnotationTool.SELECT);
        }
        final FullscreenMapLocationMenu.Capture capture = captureLocationMenu(
            mouseX, mouseY, screen.width / 2.0, screen.height / 2.0,
            screen.width - MARGIN - CONTROL_SIZE - CONTROL_GAP, screen.height
        );
        locationMenuBounds = capture.bounds();
        locationMenuTarget = capture.target();
        locationMenuWaypoint = capture.waypoint();
        locationMenuPlayerId = capture.playerId();
        pendingLocationAction = null;
        locationMenuDeletePending = false;
        screen.mapPointerPress = false;
        screen.rebuildWaypointControls();
    }
    FullscreenMapLocationMenu.Capture captureLocationMenu(
        final double mouseX,
        final double mouseY,
        final double mapCenterX,
        final double mapCenterY,
        final int viewportWidth,
        final int viewportHeight
    ) {
        final ServerPlayerRadarState.PlayerView playerTarget = screen.hoveredRadarPlayer;
        final double worldX = playerTarget == null
            ? screen.centerX + (mouseX - mapCenterX) * screen.scale : playerTarget.x();
        final double worldZ = playerTarget == null
            ? screen.centerZ + (mouseY - mapCenterY) * screen.scale : playerTarget.z();
        final FullscreenMapLocationMenu.Point point = FullscreenMapLocationMenu.pointAt(
            worldX, worldZ, playerTarget == null ? screen.hoveredStructure : null
        );
        final int blockX = point.blockX();
        final int blockZ = point.blockZ();
        final WaypointRenderEntry waypoint = playerTarget == null ? screen.hoveredWaypoint : null;
        final boolean existingWaypoint = FullscreenMapLocationMenu.isSavedWaypoint(waypoint);
        // Sizing only: HIGHLIGHT and CLEAR_HIGHLIGHT swap 1:1, and the real state is resolved
        // when the buttons are built (locationMenuButtonSpecs).
        final FullscreenMapLocationMenu.Bounds bounds = FullscreenMapLocationMenu.place(
            (int) Math.floor(mouseX),
            (int) Math.floor(mouseY),
            viewportWidth,
            viewportHeight,
            FullscreenMapLocationMenu.actions(
                existingWaypoint, false, playerTarget != null
            ).size()
        );
        final FullscreenMapLocationMenu.Target target = FullscreenMapLocationMenu.targetAt(
            blockX,
            playerTarget == null
                ? screen.groundAt(blockX, blockZ)
                : new ColumnStore.SurfaceLookup(
                    true, OptionalInt.of((int) Math.floor(playerTarget.y()) - 1)
                ),
            blockZ
        );
        return new FullscreenMapLocationMenu.Capture(
            bounds, target, waypoint, playerTarget == null ? null : playerTarget.playerId()
        );
    }
    void performPendingLocationAction() {
        final FullscreenMapLocationMenu.Action action = pendingLocationAction;
        final FullscreenMapLocationMenu.Target target = locationMenuTarget;
        final WaypointRenderEntry waypoint = locationMenuWaypoint;
        final UUID playerId = locationMenuPlayerId;
        if (action == null || target == null) {
            return;
        }
        if (action == FullscreenMapLocationMenu.Action.DELETE_WAYPOINT
            && !locationMenuDeletePending) {
            pendingLocationAction = null;
            locationMenuDeletePending = true;
            screen.rebuildWaypointControls();
            return;
        }
        dismissLocationMenu();
        runLocationAction(action, target, waypoint, playerId, screen);
    }
    /**
     * Runs one captured location-menu action. Embedded split-map hosts pass themselves as
     * {@code returnTo} so the edit/share screens they open come back to them.
     */
    void runLocationAction(
        final FullscreenMapLocationMenu.Action action,
        final FullscreenMapLocationMenu.Target target,
        final WaypointRenderEntry waypoint,
        final UUID playerId,
        final Screen returnTo
    ) {
        switch (action) {
            case HIGHLIGHT, HIGHLIGHT_WAYPOINT -> {
                screen.serverPlayerRadar.clearHighlight();
                if (waypoint != null) {
                    if (WaypointHighlightState.SELECTED_LOCATION_ID.equals(waypoint.id())) {
                        screen.waypointHighlightState.target()
                            .filter(existing -> existing.waypointId() == null)
                            .ifPresentOrElse(
                                screen.waypointHighlightState::select,
                                () -> selectLocationHighlight(target)
                            );
                    } else {
                        screen.waypointHighlightState.selectWaypoint(
                            waypoint, screen.viewSession().dimension()
                        );
                    }
                } else if (target != null) {
                    selectLocationHighlight(target);
                }
            }
            case CLEAR_HIGHLIGHT -> screen.waypointHighlightState.clear();
            case HIGHLIGHT_PLAYER -> {
                if (playerId != null && screen.serverPlayerRadar.highlight(playerId)) {
                    screen.waypointHighlightState.clear();
                }
            }
            case CLEAR_PLAYER_HIGHLIGHT -> screen.serverPlayerRadar.clearHighlight();
            case SET_WAYPOINT -> screen.gameBridge.player().ifPresent(player -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(),
                WaypointEditScreen.forCreate(
                    returnTo,
                    screen.viewSession().dimension(),
                    target.blockX(),
                    target.placementY(player.blockY()),
                    target.blockZ(),
                    screen::viewWaypointStore
                )
            ));
            case EDIT_WAYPOINT -> {
                if (waypoint != null) {
                    openWaypointFromLocationMenu(waypoint, returnTo);
                }
            }
            case DELETE_WAYPOINT -> {
                if (waypoint != null) {
                    deleteWaypointFromLocationMenu(waypoint);
                }
            }
            case SHARE_LOCATION -> screen.gameBridge.player().ifPresent(
                player -> shareTemporaryLocation(target, target.placementY(player.blockY()), returnTo)
            );
            case SHARE_WAYPOINT -> {
                if (waypoint != null) {
                    shareWaypoint(waypoint, returnTo);
                }
            }
            case TELEPORT -> {
                final SessionGuard.Session viewed = screen.viewSession();
                screen.groundTeleport.teleport(
                    target.blockX(), target.blockZ(),
                    landingWithExternalOverride(target, viewed),
                    target.ground(),
                    viewed.dimension(), viewed.world()
                );
                if (screen.config.teleportCloseMap) {
                    screen.onClose();
                }
            }
        }
        fireLocationActionPerformed(action, target);
    }

    /**
     * The captured ground for the teleport, upgraded by the API landing resolver when the
     * mod has no landing answer of its own: a browsed (non-live) session whose column is
     * unknown. A resolver answer becomes a synthesized captured surface, so the teleport
     * lands at the supplied screen.height in one command. Live sessions and known columns keep
     * the two-stage policy untouched.
     */
    private ColumnStore.SurfaceLookup landingWithExternalOverride(
        final FullscreenMapLocationMenu.Target target,
        final SessionGuard.Session viewed
    ) {
        final ColumnStore.SurfaceLookup captured = screen.capturedGroundAt(target.blockX(), target.blockZ());
        if (captured.known()
            || FullscreenMapLocationMenu.teleportPositionKnown(target, screen.viewingLiveSession())) {
            return captured;
        }
        final ConfluxMapApiImpl api = ConfluxMapClient.get().api();
        final OptionalInt override = api == null
            ? OptionalInt.empty()
            : api.resolveTeleportLanding(target.blockX(), target.blockZ(), viewed.dimension(), false);
        return override.isPresent()
            ? new ColumnStore.SurfaceLookup(true, OptionalInt.of(override.getAsInt() - 1))
            : captured;
    }

    /** Menu-gating variant of the teleport position answer, including API-supplied landings. */
    private boolean teleportPositionKnownForMenu(final FullscreenMapLocationMenu.Target target) {
        if (FullscreenMapLocationMenu.teleportPositionKnown(target, screen.viewingLiveSession())) {
            return true;
        }
        final ConfluxMapApiImpl api = ConfluxMapClient.get().api();
        return api != null && api.resolveTeleportLanding(
            target.blockX(), target.blockZ(), screen.viewSession().dimension(), false
        ).isPresent();
    }

    /** Reports an executed location action to API listeners; skips coordinate-less calls. */
    private void fireLocationActionPerformed(
        final FullscreenMapLocationMenu.Action action,
        final FullscreenMapLocationMenu.Target target
    ) {
        if (target == null) {
            return;
        }
        final ConfluxMapApiImpl api = ConfluxMapClient.get().api();
        if (api == null) {
            return;
        }
        final ConfluxMapEvents.LocationAction mapped = switch (action) {
            case SET_WAYPOINT -> ConfluxMapEvents.LocationAction.SET_WAYPOINT;
            case EDIT_WAYPOINT -> ConfluxMapEvents.LocationAction.EDIT_WAYPOINT;
            case DELETE_WAYPOINT -> ConfluxMapEvents.LocationAction.DELETE_WAYPOINT;
            case SHARE_LOCATION -> ConfluxMapEvents.LocationAction.SHARE_LOCATION;
            case SHARE_WAYPOINT -> ConfluxMapEvents.LocationAction.SHARE_WAYPOINT;
            case TELEPORT -> ConfluxMapEvents.LocationAction.TELEPORT;
            case HIGHLIGHT -> ConfluxMapEvents.LocationAction.HIGHLIGHT;
            case HIGHLIGHT_WAYPOINT -> ConfluxMapEvents.LocationAction.HIGHLIGHT_WAYPOINT;
            case CLEAR_HIGHLIGHT -> ConfluxMapEvents.LocationAction.CLEAR_HIGHLIGHT;
            case HIGHLIGHT_PLAYER -> ConfluxMapEvents.LocationAction.HIGHLIGHT_PLAYER;
            case CLEAR_PLAYER_HIGHLIGHT -> ConfluxMapEvents.LocationAction.CLEAR_PLAYER_HIGHLIGHT;
        };
        api.fireLocationActionPerformed(
            mapped, target.blockX(), target.blockZ(), screen.viewSession().dimension(), target.groundKnown()
        );
    }

    private void selectLocationHighlight(final FullscreenMapLocationMenu.Target target) {
        final boolean yKnown = target.blockY().isPresent();
        screen.waypointHighlightState.select(WaypointHighlightState.Target.location(
            screen.viewSession().dimension(), target.blockX() + 0.5,
            yKnown ? target.blockY().getAsInt() : WaypointHighlightState.DEFAULT_LOCATION_Y,
            target.blockZ() + 0.5, yKnown
        ));
    }

    private void shareTemporaryLocation(
        final FullscreenMapLocationMenu.Target target,
        final int y,
        final Screen returnTo
    ) {
        final MinecraftClient client = MinecraftClient.getInstance();
        final String name = Texts.translatable("confluxmap.map.location_menu.temporary_name").getString();
        final Waypoint temporary = new Waypoint(
            UUID.randomUUID(),
            name,
            screen.viewSession().dimension(),
            target.blockX(),
            y,
            target.blockZ(),
            TEMPORARY_LOCATION_COLOR,
            "",
            true,
            Waypoint.Type.NORMAL,
            System.currentTimeMillis()
        );
        MinecraftAccess.setScreen(client, new WaypointShareConfirmScreen(
            returnTo, temporary, WaypointShareConfirmScreen.Target.CHAT
        ));
    }

    private void shareWaypoint(final WaypointRenderEntry waypoint, final Screen returnTo) {
        final MinecraftClient client = MinecraftClient.getInstance();
        if (waypoint.local()) {
            screen.viewWaypointService().list().stream()
                .filter(local -> local.id.equals(waypoint.id()))
                .findFirst()
                .ifPresent(local -> MinecraftAccess.setScreen(
                    client,
                    new WaypointShareConfirmScreen(
                        returnTo, local, WaypointShareConfirmScreen.Target.CHAT
                    )
                ));
            return;
        }
        screen.sharedWaypoints.find(waypoint.id()).ifPresent(shared ->
            MinecraftAccess.setScreen(
                client,
                WaypointShareConfirmScreen.forSharedWaypoint(
                    returnTo,
                    shared
                )
            )
        );
    }

    void dismissLocationMenu() {
        locationMenuBounds = null;
        locationMenuTarget = null;
        locationMenuWaypoint = null;
        locationMenuPlayerId = null;
        locationMenuSpecs = List.of();
        pendingLocationAction = null;
        locationMenuDeletePending = false;
        screen.rebuildWaypointControls();
    }

    private boolean waypointEditable(final WaypointRenderEntry waypoint) {
        if (waypoint == null || waypoint.sibling()) {
            return false;
        }
        if (waypoint.local()) {
            final WaypointStore store = screen.viewWaypointStore();
            return store != null && store.persistenceWritable();
        }
        return screen.sharedWaypoints.find(waypoint.id()).map(screen.sharedWaypoints::canUpdate).orElse(false);
    }

    private String waypointDeleteUnavailableKey(final WaypointRenderEntry waypoint) {
        if (waypoint == null) {
            return "confluxmap.screen.waypoint.public_unavailable";
        }
        if (waypoint.sibling()) {
            return "confluxmap.screen.waypoints.cross_world_read_only";
        }
        if (waypoint.local()) {
            final WaypointStore store = screen.viewWaypointStore();
            return store != null && store.persistenceWritable()
                ? null : "confluxmap.screen.waypoints.read_only";
        }
        final var shared = screen.sharedWaypoints.find(waypoint.id());
        return shared.isPresent()
            ? screen.sharedWaypoints.deleteDisabledReasonKey(shared.get())
            : "confluxmap.screen.waypoint.public_unavailable";
    }

    private void openWaypointFromLocationMenu(
        final WaypointRenderEntry waypoint,
        final Screen returnTo
    ) {
        if (waypoint.local()) {
            screen.viewWaypointService().list().stream()
                .filter(local -> local.id.equals(waypoint.id()))
                .findFirst()
                .ifPresent(local -> MinecraftAccess.setScreen(
                    MinecraftClient.getInstance(), WaypointEditScreen.forEdit(
                        returnTo, local, screen::viewWaypointStore
                    )
                ));
            return;
        }
        screen.sharedWaypoints.find(waypoint.id())
            .filter(screen.sharedWaypoints::canUpdate)
            .ifPresent(shared -> MinecraftAccess.setScreen(
                MinecraftClient.getInstance(), WaypointEditScreen.forPublicEdit(returnTo, shared)
            ));
    }

    private void deleteWaypointFromLocationMenu(final WaypointRenderEntry waypoint) {
        if (waypoint.local()) {
            final WaypointStore store = screen.viewWaypointStore();
            if (store != null && store.persistenceWritable()) {
                store.remove(waypoint.id());
            }
            return;
        }
        screen.sharedWaypoints.find(waypoint.id())
            .filter(screen.sharedWaypoints::canDelete)
            .ifPresent(screen.sharedWaypoints::delete);
    }
    void drawLocationMenu(final GuiDraw draw) {
        if (locationMenuBounds == null) {
            return;
        }
        FullscreenMapLocationMenu.drawPanel(draw, locationMenuBounds);
    }
    Text hoveredLocationActionTooltip() {
        for (final Map.Entry<ButtonWidget, String> entry : locationActionTooltips.entrySet()) {
            if (entry.getKey().isHovered()) {
                return Texts.translatable(entry.getValue());
            }
        }
        return null;
    }

    private static String actionTooltipKey(final FullscreenMapLocationMenu.Action action) {
        return switch (action) {
            case SET_WAYPOINT -> "confluxmap.map.location_menu.set_waypoint.tooltip";
            case EDIT_WAYPOINT -> "confluxmap.map.location_menu.edit_waypoint.tooltip";
            case DELETE_WAYPOINT -> "confluxmap.map.location_menu.delete_waypoint.tooltip";
            case SHARE_LOCATION -> "confluxmap.map.location_menu.share_location.tooltip";
            case SHARE_WAYPOINT -> "confluxmap.map.location_menu.share_waypoint.tooltip";
            case TELEPORT -> "confluxmap.map.location_menu.teleport.tooltip";
            case HIGHLIGHT -> "confluxmap.map.location_menu.highlight.tooltip";
            case HIGHLIGHT_WAYPOINT -> "confluxmap.map.location_menu.highlight_waypoint.tooltip";
            case CLEAR_HIGHLIGHT -> "confluxmap.map.location_menu.clear_highlight.tooltip";
            case HIGHLIGHT_PLAYER -> "confluxmap.map.location_menu.highlight_player.tooltip";
            case CLEAR_PLAYER_HIGHLIGHT -> "confluxmap.map.location_menu.clear_player_highlight.tooltip";
        };
    }
    boolean locationActionHeightUnavailable() {
        if (locationMenuTarget == null || locationMenuTarget.blockY().isPresent()) {
            return false;
        }
        return (setWaypointLocationButton != null && setWaypointLocationButton.isHovered())
            || (shareLocationButton != null && shareLocationButton.isHovered());
    }
}
