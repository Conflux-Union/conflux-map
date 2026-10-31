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
import cn.net.rms.confluxmap.mc.render.GuiShapes;
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
 * The drawing-tool slice of the fullscreen map: annotation tools, the measure tool,
 * their toolbar and color menu, pointer gestures, and overlay drawing.
 */
final class MapAnnotationController {
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
    private static final double ANNOTATION_HIT_TOLERANCE_PX = 5.0;
    private static final Identifier ANNOTATION_SELECT_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_select.png"
    );
    private static final Identifier ANNOTATION_LINE_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_line.png"
    );
    private static final Identifier ANNOTATION_CIRCLE_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_circle.png"
    );
    private static final Identifier ANNOTATION_RECTANGLE_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_rectangle.png"
    );
    private static final Identifier ANNOTATION_FREEHAND_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_freehand.png"
    );
    private static final Identifier ANNOTATION_ERASER_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_eraser.png"
    );
    private static final Identifier ANNOTATION_PERSISTENCE_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_persistence.png"
    );
    private static final Identifier ANNOTATION_PERSISTENCE_TRANSIENT_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_persistence_transient.png"
    );
    private static final Identifier ANNOTATION_LABEL_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_label.png"
    );
    private static final Identifier ANNOTATION_UNDO_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_undo.png"
    );
    private static final Identifier ANNOTATION_REDO_ICON = Ids.of(
        "confluxmap", "textures/gui/annotation_redo.png"
    );
    private static final Identifier MEASURE_ICON = Ids.of(
        "confluxmap", "textures/gui/measure.png"
    );
    /** Measure overlay: teal line reads over green/brown terrain without clashing with the amber annotation palette. */
    private static final int MEASURE_LINE_COLOR = 0xFF3FD1C7;
    private static final int MEASURE_PREVIEW_COLOR = 0x993FD1C7;
    private static final int MEASURE_VERTEX_COLOR = 0xFFFFFFFF;
    private static final int MEASURE_TOTAL_COLOR = 0xFFFFE066;
    private static final float MEASURE_STROKE_WIDTH = 2.0f;
    private static final float MEASURE_PREVIEW_STROKE_WIDTH = 1.5f;
    private static final float MEASURE_VERTEX_RADIUS = 3.0f;
    private static final double MEASURE_LABEL_OFFSET_PX = 9.0;

    private final FullscreenMapScreen screen;
    private final MeasureState measureState;

    MapAnnotationController(final FullscreenMapScreen screen, final MeasureState measureState) {
        this.screen = screen;
        this.measureState = measureState;
    }

    private final Map<AnnotationTool, ButtonWidget> annotationToolButtons = new EnumMap<>(AnnotationTool.class);
    private FullscreenMapScreen.MapIconButton annotationPersistenceButton;
    private FullscreenMapScreen.MapIconButton annotationEraserButton;
    private ButtonWidget annotationLabelButton;
    private ButtonWidget annotationUndoButton;
    private ButtonWidget annotationRedoButton;
    private ButtonWidget measureButton;
    private boolean annotationColorMenuOpen;
    private AnnotationTool annotationTool = AnnotationTool.SELECT;
    /** Exclusive with every {@link AnnotationTool}: while on, left-click places measure points instead of drawing. */
    private boolean measureMode;
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

    boolean isMeasureMode() {
        return measureMode;
    }

    boolean isPointerDown() {
        return annotationPointerPress;
    }

    boolean isColorMenuOpen() {
        return annotationColorMenuOpen;
    }

    void closeColorMenu() {
        annotationColorMenuOpen = false;
    }

    void cancelPointer() {
        annotationPointerPress = false;
    }

    void clearControls() {
        screen.annotationTooltips.clear();
        annotationToolButtons.clear();
        annotationPersistenceButton = null;
        annotationEraserButton = null;
        annotationLabelButton = null;
        annotationUndoButton = null;
        annotationRedoButton = null;
        measureButton = null;
        screen.annotationToolbarBounds = null;
        screen.annotationColorMenuBounds = null;
    }

    void resetForTargetChange() {
        annotationTool = AnnotationTool.SELECT;
        annotationDraft = null;
        movingAnnotation = null;
        selectedAnnotationId = null;
        annotationPointerPress = false;
        erasingAnnotationIds.clear();
    }

    boolean dragPointer(
        final double mouseX,
        final double mouseY,
        final double deltaX,
        final double deltaY
    ) {
        if (annotationTool == AnnotationTool.ERASER) {
            final AnnotationStore store = screen.viewAnnotationStore();
            if (store != null) {
                collectEraserStroke(store, mouseX, mouseY, deltaX, deltaY);
            }
        } else if (movingAnnotation != null) {
            annotationMoveDx = (mouseX - screen.leftPressX) * screen.scale;
            annotationMoveDz = (mouseY - screen.leftPressY) * screen.scale;
        } else if (annotationDraft != null) {
            annotationDraft.dragTo(annotationWorldPoint(mouseX, mouseY), screen.scale);
        }
        return true;
    }

    boolean placeMeasurePoint(final double mouseX, final double mouseY) {
        if (measureMode
            && !screen.isOverMapControls(mouseX, mouseY)
            && Math.hypot(mouseX - screen.leftPressX, mouseY - screen.leftPressY) < CLICK_DRAG_TOLERANCE_PX) {
            final AnnotationPoint raw = annotationWorldPoint(mouseX, mouseY);
            measurePath().add(new AnnotationPoint(
                Math.floor(raw.x()) + 0.5, Math.floor(raw.z()) + 0.5
            ));
            return true;
        }
        return false;
    }

        void rebuildAnnotationControls(final int desiredTop) {

        final int controlCount = AnnotationTool.values().length + 6;
        final int stride = ANNOTATION_CONTROL_SIZE + ANNOTATION_CONTROL_GAP;
        final int availableToolbarWidth = screen.width - MARGIN * 2 - CONTROL_GAP - CONTROL_SIZE;
        final int maxToolColumns = Math.max(
            1, (availableToolbarWidth + ANNOTATION_CONTROL_GAP) / stride
        );
        final int minimumRows = (controlCount + maxToolColumns - 1) / maxToolColumns;
        final int availableRows = Math.max(
            1, (screen.height - MARGIN - desiredTop + ANNOTATION_CONTROL_GAP) / stride
        );
        final int rows = Math.min(
            ANNOTATION_MAX_ROWS, Math.max(minimumRows, Math.min(ANNOTATION_MAX_ROWS, availableRows))
        );
        final int columns = (controlCount + rows - 1) / rows;
        final int toolGridWidth = columns * stride - ANNOTATION_CONTROL_GAP;
        final int toolGridHeight = Math.min(rows, controlCount) * stride - ANNOTATION_CONTROL_GAP;
        final int toolbarWidth = toolGridWidth;
        final int toolbarHeight = toolGridHeight;
        final boolean fitsBelowWaypoints = desiredTop + toolbarHeight <= screen.height - MARGIN;
        final int top = fitsBelowWaypoints
            ? desiredTop
            : Math.max(MARGIN, screen.height - MARGIN - toolbarHeight);
        // Anchored to the tool-group column, not to secondaryControlsX(): the drawing group is the
        // one group that puts nothing in that secondary column, so reserving it leaves a visibly
        // empty button-wide gap between the tool grid and the group buttons.
        final int left = fitsBelowWaypoints
            ? Math.max(MARGIN, screen.width - MARGIN - CONTROL_SIZE - CONTROL_GAP - toolbarWidth)
            : MARGIN;
        screen.annotationToolbarBounds = new AnnotationToolbarBounds(left, top, toolbarWidth, toolbarHeight);

        int index = 0;
        for (final AnnotationTool tool : AnnotationTool.values()) {
            final int buttonX = controlX(left, stride, rows, index);
            final int buttonY = controlY(top, stride, rows, index);
            final ButtonWidget button;
            if (tool == AnnotationTool.ERASER) {
                annotationEraserButton = screen.addWidget(screen.iconButton(
                    buttonX,
                    buttonY,
                    ANNOTATION_CONTROL_SIZE,
                    ANNOTATION_ERASER_ICON,
                    Texts.literal(""),
                    ignored -> selectAnnotationTool(tool)
                ));
                screen.annotationTooltips.put(annotationEraserButton, toolTooltip(tool));
                button = annotationEraserButton;
            } else {
                button = addAnnotationIconButton(
                    buttonX,
                    buttonY,
                    annotationToolIcon(tool),
                    toolTooltip(tool),
                    ignored -> selectAnnotationTool(tool)
                );
            }
            annotationToolButtons.put(tool, button);
            index++;
        }
        measureButton = addAnnotationIconButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            MEASURE_ICON,
            "confluxmap.map.measure.tooltip",
            ignored -> selectMeasureTool()
        );
        index++;
        addColorButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            selectedAnnotationColor(),
            true
        );
        index++;
        annotationPersistenceButton = screen.addWidget(screen.iconButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            ANNOTATION_CONTROL_SIZE,
            ANNOTATION_PERSISTENCE_ICON,
            Texts.literal(""),
            ignored -> toggleAnnotationPersistence()
        ));
        screen.annotationTooltips.put(
            annotationPersistenceButton, "confluxmap.map.annotation.persistence.tooltip"
        );
        index++;
        annotationLabelButton = addAnnotationIconButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            ANNOTATION_LABEL_ICON,
            "confluxmap.map.annotation.label.tooltip",
            ignored -> editSelectedAnnotationLabel()
        );
        index++;
        annotationUndoButton = addAnnotationIconButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            ANNOTATION_UNDO_ICON,
            "confluxmap.map.annotation.undo.tooltip",
            ignored -> undoAnnotationChange()
        );
        index++;
        annotationRedoButton = addAnnotationIconButton(
            controlX(left, stride, rows, index),
            controlY(top, stride, rows, index),
            ANNOTATION_REDO_ICON,
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
        final int leftCandidate = screen.annotationToolbarBounds.x() - ANNOTATION_CONTROL_GAP - menuWidth;
        final int rightCandidate = screen.annotationToolbarBounds.x()
            + screen.annotationToolbarBounds.width() + ANNOTATION_CONTROL_GAP;
        final int left = leftCandidate >= MARGIN
            ? leftCandidate
            : rightCandidate + menuWidth <= screen.width - MARGIN ? rightCandidate : MARGIN;
        final int top = Math.max(
            MARGIN, Math.min(screen.annotationToolbarBounds.y(), screen.height - MARGIN - menuHeight)
        );
        screen.annotationColorMenuBounds = new AnnotationToolbarBounds(left, top, menuWidth, menuHeight);
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

    private ButtonWidget addAnnotationIconButton(
        final int x,
        final int y,
        final Identifier icon,
        final String tooltipKey,
        final ButtonWidget.PressAction action
    ) {
        final ButtonWidget button = screen.addWidget(screen.iconButton(
            x, y, ANNOTATION_CONTROL_SIZE, icon, Texts.translatable(tooltipKey), action
        ));
        screen.annotationTooltips.put(button, tooltipKey);
        return button;
    }

    private void addColorButton(
        final int x,
        final int y,
        final int color,
        final boolean opensMenu
    ) {
        //#if MC>=260100
        //$$ final var button = screen.addWidget(new Button(
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
        //$$         if (screen.uiTheme.useVanillaButtonStyle()) {
        //$$             extractDefaultSprite(context);
        //$$         }
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#elseif MC>=12111
        //$$ final ButtonWidget button = screen.addWidget(new ButtonWidget(
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
        //$$         if (screen.uiTheme.useVanillaButtonStyle()) {
        //$$             drawButton(context);
        //$$         }
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#elseif MC>=12002
        //$$ final ButtonWidget button = screen.addWidget(new ButtonWidget(
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
        //$$         if (screen.uiTheme.useVanillaButtonStyle()) {
        //$$             super.renderWidget(context, mouseX, mouseY, delta);
        //$$         }
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#elseif MC>=12000
        //$$ final ButtonWidget button = screen.addWidget(new ButtonWidget(
        //$$     x, y, ANNOTATION_CONTROL_SIZE, ANNOTATION_CONTROL_SIZE, Texts.literal(""),
        //$$     ignored -> activateAnnotationColorButton(color, opensMenu), narration -> narration.get()
        //$$ ) {
        //$$     @Override
        //$$     protected void renderButton(
        //$$         final DrawContext context,
        //$$         final int mouseX,
        //$$         final int mouseY,
        //$$         final float delta
        //$$     ) {
        //$$         if (screen.uiTheme.useVanillaButtonStyle()) {
        //$$             super.renderButton(context, mouseX, mouseY, delta);
        //$$         }
        //$$         renderAnnotationColorButton(GuiDraw.of(context), this, color, opensMenu);
        //$$     }
        //$$ });
        //#else
        final ButtonWidget button = screen.addWidget(new ButtonWidget(
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
                if (screen.uiTheme.useVanillaButtonStyle()) {
                    super.renderButton(matrices, mouseX, mouseY, delta);
                }
                renderAnnotationColorButton(GuiDraw.of(matrices), this, color, opensMenu);
            }
        });
        //#endif
        screen.annotationTooltips.put(button, "confluxmap.map.annotation.color.tooltip");
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
        final int inset = screen.uiTheme.useVanillaButtonStyle() ? 3 : 0;
        final int swatchSize = button.getWidth() - inset * 2;
        GuiShapes.fillRect(
            draw.matrices(), x + inset, y + inset, swatchSize, swatchSize,
            displayedColor | 0xFF000000
        );
        if (opensMenu ? annotationColorMenuOpen : color == selectedAnnotationColor()) {
            final int border = screen.uiTheme.useVanillaButtonStyle() ? 1 : 2;
            GuiShapes.fillRect(
                draw.matrices(), x + inset, y + inset, swatchSize, border, 0xFFFFFFFF
            );
            GuiShapes.fillRect(
                draw.matrices(), x + inset, y + inset + swatchSize - border,
                swatchSize, border, 0xFFFFFFFF
            );
            GuiShapes.fillRect(
                draw.matrices(), x + inset, y + inset, border, swatchSize, 0xFFFFFFFF
            );
            GuiShapes.fillRect(
                draw.matrices(), x + inset + swatchSize - border, y + inset,
                border, swatchSize, 0xFFFFFFFF
            );
        }
    }

    private void refreshAnnotationControls() {
        for (final Map.Entry<AnnotationTool, ButtonWidget> entry : annotationToolButtons.entrySet()) {
            entry.getValue().active = true;
            if (entry.getValue() instanceof FullscreenMapScreen.MapIconButton iconButton) {
                iconButton.setSelected(entry.getKey() == annotationTool && !measureMode);
            }
        }
        if (measureButton instanceof FullscreenMapScreen.MapIconButton iconButton) {
            iconButton.setSelected(measureMode);
        }
        final boolean selected = selectedAnnotation().isPresent();
        if (annotationLabelButton != null) {
            annotationLabelButton.active = selected;
        }
        if (annotationPersistenceButton != null) {
            final boolean persistent =
                selectedAnnotationPersistence() == AnnotationPersistence.PERSISTENT;
            annotationPersistenceButton.setSelected(persistent);
            annotationPersistenceButton.setIcon(
                persistent ? ANNOTATION_PERSISTENCE_ICON : ANNOTATION_PERSISTENCE_TRANSIENT_ICON
            );
        }
        final AnnotationStore store = screen.viewAnnotationStore();
        if (annotationUndoButton != null) {
            annotationUndoButton.active = store != null && store.canUndo();
        }
        if (annotationRedoButton != null) {
            annotationRedoButton.active = store != null && store.canRedo();
        }
    }

    private static Identifier annotationToolIcon(final AnnotationTool tool) {
        return switch (tool) {
            case SELECT -> ANNOTATION_SELECT_ICON;
            case LINE -> ANNOTATION_LINE_ICON;
            case CIRCLE -> ANNOTATION_CIRCLE_ICON;
            case RECTANGLE -> ANNOTATION_RECTANGLE_ICON;
            case FREEHAND -> ANNOTATION_FREEHAND_ICON;
            case ERASER -> ANNOTATION_ERASER_ICON;
        };
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
        final AnnotationStore store = screen.viewAnnotationStore();
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

    void selectAnnotationTool(final AnnotationTool tool) {
        final long now = Util.getMeasuringTimeMs();
        final boolean openEraserSettings = tool == AnnotationTool.ERASER
            && lastEraserButtonClickMs != Long.MIN_VALUE
            && now - lastEraserButtonClickMs <= DOUBLE_CLICK_INTERVAL_MS;
        lastEraserButtonClickMs = tool == AnnotationTool.ERASER ? now : Long.MIN_VALUE;
        measureMode = false;
        annotationTool = tool;
        annotationDraft = null;
        movingAnnotation = null;
        cancelPointer();
        erasingAnnotationIds.clear();
        annotationColorMenuOpen = false;
        screen.rebuildWaypointControls();
        if (openEraserSettings) {
            lastEraserButtonClickMs = Long.MIN_VALUE;
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), new AnnotationEraserSettingsScreen(screen, screen.config));
        }
    }

    void selectMeasureTool() {
        measureMode = true;
        annotationTool = AnnotationTool.SELECT;
        annotationDraft = null;
        movingAnnotation = null;
        cancelPointer();
        erasingAnnotationIds.clear();
        annotationColorMenuOpen = false;
        screen.rebuildWaypointControls();
    }

    private void activateAnnotationColorButton(final int color, final boolean opensMenu) {
        if (opensMenu) {
            annotationColorMenuOpen = !annotationColorMenuOpen;
        } else {
            selectAnnotationColor(color);
            annotationColorMenuOpen = false;
        }
        screen.rebuildWaypointControls();
    }

    private void selectAnnotationColor(final int color) {
        newAnnotationColor = color;
        final AnnotationStore store = screen.viewAnnotationStore();
        if (store != null) {
            selectedAnnotation().ifPresent(annotation -> store.update(
                annotation.withStyle(new AnnotationStyle(color))
            ));
        }
    }

    void undoAnnotationChange() {
        final AnnotationStore store = screen.viewAnnotationStore();
        if (store != null && store.undo()) {
            clearMissingSelection(store);
            refreshAnnotationControls();
        }
    }

    void redoAnnotationChange() {
        final AnnotationStore store = screen.viewAnnotationStore();
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
        final AnnotationStore store = screen.viewAnnotationStore();
        final Optional<Annotation> selected = selectedAnnotation();
        if (store != null && selected.isPresent()) {
            store.update(selected.get().withPersistence(next));
        } else {
            newAnnotationPersistence = next;
        }
        refreshAnnotationControls();
    }

    private void editSelectedAnnotationLabel() {
        final AnnotationStore store = screen.viewAnnotationStore();
        if (store == null) {
            return;
        }
        selectedAnnotation().ifPresent(annotation -> MinecraftAccess.setScreen(MinecraftClient.getInstance(),
            new AnnotationLabelScreen(screen, store, annotation)
        ));
    }
    boolean beginAnnotationPointer(final double mouseX, final double mouseY) {
        final AnnotationStore store = screen.viewAnnotationStore();
        if (store == null) {
            return false;
        }
        final AnnotationPoint worldPoint = annotationWorldPoint(mouseX, mouseY);
        if (annotationTool == AnnotationTool.SELECT) {
            final Optional<Annotation> hit = store.hit(
                screen.viewSession().dimension(), worldPoint, ANNOTATION_HIT_TOLERANCE_PX * screen.scale
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
            screen.leftPressX = mouseX;
            screen.leftPressY = mouseY;
            screen.mapPointerPress = false;
            refreshAnnotationControls();
            return true;
        }
        if (annotationTool == AnnotationTool.ERASER) {
            annotationPointerPress = true;
            annotationDraft = null;
            movingAnnotation = null;
            erasingAnnotationIds.clear();
            collectEraserHits(store, worldPoint);
            screen.mapPointerPress = false;
            return true;
        }
        annotationDraft = new AnnotationDraft(annotationTool, worldPoint);
        annotationPointerPress = true;
        movingAnnotation = null;
        screen.leftPressX = mouseX;
        screen.leftPressY = mouseY;
        screen.mapPointerPress = false;
        return true;
    }

    private void collectEraserHits(
        final AnnotationStore store,
        final AnnotationPoint worldPoint
    ) {
        final double radius = screen.config.annotationEraserSize / 2.0 * screen.scale;
        for (final Annotation annotation : store.hits(
            screen.viewSession().dimension(), worldPoint, radius
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
        final double sampleSpacing = Math.max(1.0, screen.config.annotationEraserSize / 4.0);
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
            screen.centerX + (mouseX - screen.width / 2.0) * screen.scale,
            screen.centerZ + (mouseY - screen.height / 2.0) * screen.scale
        );
    }

    private MeasurePath measurePath() {
        final SessionGuard.Session session = screen.viewSession();
        return measureState.path(session.world(), session.dimension());
    }

    /** Right-click contract of the measure tool: undo the newest point, exit once the path is empty. */
    void undoMeasurePoint() {
        if (!measurePath().undo()) {
            selectAnnotationTool(AnnotationTool.SELECT);
        }
    }
    void commitAnnotationPointer(final double mouseX, final double mouseY) {
        cancelPointer();
        final AnnotationStore store = screen.viewAnnotationStore();
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
            annotationDraft.dragTo(annotationWorldPoint(mouseX, mouseY), screen.scale);
            final Optional<AnnotationGeometry> geometry = annotationDraft.geometry(screen.scale, true);
            annotationDraft = null;
            if (geometry.isPresent()) {
                final Annotation created = new Annotation(
                    UUID.randomUUID(),
                    screen.viewSession().dimension(),
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
    void drawAnnotations(final GuiDraw draw, final int mouseX, final int mouseY) {
        final AnnotationStore store = screen.viewAnnotationStore();
        if (store == null) {
            return;
        }
        final List<Annotation> visible = new ArrayList<>(store.list(screen.viewSession().dimension()));
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
            annotationDraft.geometry(screen.scale, false).ifPresent(geometry -> visible.add(new Annotation(
                ANNOTATION_DRAFT_ID,
                screen.viewSession().dimension(),
                geometry,
                new AnnotationStyle(newAnnotationColor),
                "",
                newAnnotationPersistence,
                System.currentTimeMillis()
            )));
        }
        final AnnotationProjection projection = new AnnotationProjection(
            screen.centerX,
            screen.centerZ,
            screen.width / 2.0,
            screen.height / 2.0,
            screen.scale,
            0.0,
            screen.width,
            screen.height
        );
        AnnotationRenderer.drawGeometry(
            draw.matrices(),
            visible,
            projection,
            annotationDraft == null ? selectedAnnotationId : ANNOTATION_DRAFT_ID
        );
        AnnotationRenderer.drawLabels(
            draw,
            screen.font(),
            visible,
            projection,
            0,
            0,
            screen.width,
            screen.height,
            AnnotationRenderer.ClipShape.RECTANGLE
        );
        if (annotationTool == AnnotationTool.ERASER
            && !screen.locationMenu.isOpen()
            && !screen.isOverMapControls(mouseX, mouseY)) {
            GuiShapes.drawRing(
                draw.matrices(),
                mouseX,
                mouseY,
                screen.config.annotationEraserSize / 2.0f,
                1.5f,
                0xE6FFFFFF
            );
        }
    }

    /**
     * Measure overlay: committed points and segments stay visible for the viewed dimension even
     * after the tool is deselected, so a finished measurement keeps reading until cleared.
     */
    void drawMeasureOverlay(final GuiDraw draw, final int mouseX, final int mouseY) {
        final MeasurePath path = measurePath();
        if (!measureMode && path.isEmpty()) {
            return;
        }
        final MatrixStack matrices = draw.matrices();
        final AnnotationProjection projection = new AnnotationProjection(
            screen.centerX, screen.centerZ, screen.width / 2.0, screen.height / 2.0, screen.scale, 0.0, screen.width, screen.height
        );
        final List<AnnotationPoint> points = path.points();
        for (int index = 1; index < points.size(); index++) {
            final AnnotationProjection.ScreenPoint start = projection.project(points.get(index - 1));
            final AnnotationProjection.ScreenPoint end = projection.project(points.get(index));
            AnnotationRenderer.stroke(matrices, start, end, MEASURE_STROKE_WIDTH, MEASURE_LINE_COLOR);
            drawMeasureSegmentLabel(draw, start, end, measureDistanceText(path.segmentLength(index - 1)));
        }
        for (final AnnotationPoint point : points) {
            final AnnotationProjection.ScreenPoint at = projection.project(point);
            GuiShapes.fillBeveledDiamond(
                matrices, (float) at.x(), (float) at.y(), MEASURE_VERTEX_RADIUS, MEASURE_VERTEX_COLOR
            );
        }
        if (points.size() >= 2) {
            final AnnotationProjection.ScreenPoint last = projection.project(points.get(points.size() - 1));
            drawMeasureLabel(
                draw,
                last.x(),
                last.y() - MEASURE_VERTEX_RADIUS - 2.0,
                Texts.translatable(
                    "confluxmap.map.measure.total", measureDistanceText(path.totalLength())
                ).getString(),
                MEASURE_TOTAL_COLOR
            );
        }
        final boolean cursorOnMap = !screen.locationMenu.isOpen() && !screen.isOverMapControls(mouseX, mouseY);
        if (measureMode && !path.isEmpty() && cursorOnMap && !screen.exportControls.selecting()) {
            final AnnotationPoint raw = annotationWorldPoint(mouseX, mouseY);
            final AnnotationPoint snapped = new AnnotationPoint(
                Math.floor(raw.x()) + 0.5, Math.floor(raw.z()) + 0.5
            );
            final AnnotationProjection.ScreenPoint from = projection.project(points.get(points.size() - 1));
            final AnnotationProjection.ScreenPoint to = projection.project(snapped);
            AnnotationRenderer.stroke(
                matrices, from, to, MEASURE_PREVIEW_STROKE_WIDTH, MEASURE_PREVIEW_COLOR
            );
            drawMeasureSegmentLabel(
                draw, from, to, measureDistanceText(MeasurePath.distance(points.get(points.size() - 1), snapped))
            );
        }
        if (measureMode && !screen.exportControls.selecting()) {
            final String hint = Texts.translatable("confluxmap.map.measure.hint").getString();
            final int hintWidth = screen.font().getWidth(hint);
            draw.drawTextWithShadow(
                screen.font(),
                hint,
                screen.width / 2f - hintWidth / 2f,
                screen.height - MARGIN - 12 - screen.font().fontHeight,
                TEXT_COLOR
            );
        }
    }

    private static String measureDistanceText(final double blocks) {
        return Texts.translatable("confluxmap.map.measure.segment", MeasureFormat.blocks(blocks)).getString();
    }

    private void drawMeasureSegmentLabel(
        final GuiDraw draw,
        final AnnotationProjection.ScreenPoint start,
        final AnnotationProjection.ScreenPoint end,
        final String text
    ) {
        final double dx = end.x() - start.x();
        final double dy = end.y() - start.y();
        final double length = Math.hypot(dx, dy);
        if (length == 0.0) {
            return;
        }
        final double perpendicularX = -dy / length;
        final double perpendicularY = dx / length;
        // Always lift the label to the segment's upper side so it stays over the map.
        final double side = perpendicularY > 0.0 ? -1.0 : 1.0;
        drawMeasureLabel(
            draw,
            (start.x() + end.x()) / 2.0 + perpendicularX * side * MEASURE_LABEL_OFFSET_PX,
            (start.y() + end.y()) / 2.0 + perpendicularY * side * MEASURE_LABEL_OFFSET_PX,
            text,
            0xFFFFFFFF
        );
    }

    private void drawMeasureLabel(
        final GuiDraw draw,
        final double x,
        final double y,
        final String text,
        final int color
    ) {
        final int textWidth = screen.font().getWidth(text);
        final float left = (float) (x - textWidth / 2.0);
        final float top = (float) (y - screen.font().fontHeight / 2.0);
        if (left < 0.0f || top < 0.0f
            || left + textWidth > screen.width || top + screen.font().fontHeight > screen.height) {
            return;
        }
        if (screen.mapOverlayIntersectsUi(
            left - 1.0f, top - 1.0f,
            left + textWidth + 1.0f, top + screen.font().fontHeight + 1.0f
        )) {
            return;
        }
        draw.drawTextWithShadow(screen.font(), text, left, top, color);
    }
    Text hoveredAnnotationTooltip() {
        for (final Map.Entry<ButtonWidget, String> entry : screen.annotationTooltips.entrySet()) {
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
                return Texts.translatable(entry.getValue(), screen.config.annotationEraserSize);
            }
            if (entry.getKey() == screen.clientWorldButton) {
                return Texts.translatable(
                    entry.getValue(),
                    screen.clientMultiworld.currentProfile()
                        .map(ClientWorldProfile::displayName)
                        .orElse("?")
                );
            }
            return Texts.translatable(entry.getValue());
        }
        return null;
    }
}
