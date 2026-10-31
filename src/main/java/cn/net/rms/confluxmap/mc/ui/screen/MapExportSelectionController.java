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

/** The on-map two-corner export selection flow and the request the export screen consumes. */
final class MapExportSelectionController {
    private final FullscreenMapScreen screen;
    MapExportSelectionController(final FullscreenMapScreen screen) {
        this.screen = screen;
    }

    private MapExportScreen exportSelectionScreen;
    private boolean exportSelectionReturnsToForm;
    private final MapExportSelection exportSelection = new MapExportSelection();

    boolean selecting() {
        return exportSelectionScreen != null;
    }

    boolean selectionComplete() {
        return exportSelection.bounds().isPresent();
    }

    void rebuildExportSelectionControls() {
        screen.clearMapWidgets();
        screen.addWidget(Widgets.button(
            screen.width - MARGIN - 80,
            screen.height - 32,
            80,
            20,
            Texts.translatable("confluxmap.screen.map_export.cancel_selection"),
            ignored -> cancelExportSelection()
        ));
        if (exportSelection.bounds().isPresent()) {
            screen.addWidget(Widgets.button(
                screen.width / 2 - 104,
                screen.height - 32,
                100,
                20,
                Texts.translatable("confluxmap.screen.map_export.use_selection"),
                ignored -> finishExportSelection()
            ));
            screen.addWidget(Widgets.button(
                screen.width / 2 + 4,
                screen.height - 32,
                100,
                20,
                Texts.translatable("confluxmap.screen.map_export.reset_selection"),
                ignored -> {
                    exportSelection.reset();
                    rebuildExportSelectionControls();
                }
            ));
        }
    }
    void openMapExport() {
        final int minX = (int) Math.floor(screen.centerX - screen.width / 2.0 * screen.scale);
        final int minZ = (int) Math.floor(screen.centerZ - screen.height / 2.0 * screen.scale);
        final int maxX = (int) Math.ceil(screen.centerX + screen.width / 2.0 * screen.scale) - 1;
        final int maxZ = (int) Math.ceil(screen.centerZ + screen.height / 2.0 * screen.scale) - 1;
        final MapExportScreen form = new MapExportScreen(
            this.screen,
            MapExportBounds.between(minX, minZ, maxX, maxZ),
            MapExportResolution.forLod(screen.currentLod())
        );
        beginExportSelection(form, false);
    }

    void beginExportSelection(final MapExportScreen form) {
        beginExportSelection(form, true);
    }

    void beginExportSelection(
        final MapExportScreen form,
        final boolean returnsToForm
    ) {
        exportSelectionScreen = form;
        exportSelectionReturnsToForm = returnsToForm;
        exportSelection.reset();
        this.screen.mapPointerPress = false;
        this.screen.annotations.cancelPointer();
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), this.screen);
    }

    MapExportRequest createExportRequest(
        final MapExportBounds bounds,
        final MapExportResolution resolution
    ) {
        final SessionGuard.Session session = screen.viewSession();
        final MapLayer layer = screen.viewLayer();
        final boolean biome = screen.displayMode() == FullscreenDisplayMode.BIOME;
        final boolean prediction = screen.predictionActive(layer, session, biome);
        final AnnotationStore annotations = screen.viewAnnotationStore();
        return new MapExportRequest(
            session,
            layer,
            bounds,
            resolution,
            screen.displayMode(),
            prediction,
            screen.predictionTiles.viewMode(),
            0xFFFFFFFF,
            BACKGROUND_COLOR,
            screen.config.dynamicLighting,
            screen.daylightModel.factor(),
            screen.displayMode() == FullscreenDisplayMode.CHUNK_LOAD_STATE
                ? new MapExportLoadState(screen.chunkLoadStates.snapshot().entries())
                : MapExportLoadState.empty()
        ).withAnnotations(
            annotations == null ? List.of() : annotations.list(session.dimension())
        );
    }

    void cancelExportSelection() {
        final MapExportScreen form = exportSelectionScreen;
        exportSelectionScreen = null;
        final boolean returnsToForm = exportSelectionReturnsToForm;
        exportSelectionReturnsToForm = false;
        exportSelection.reset();
        screen.mapPointerPress = false;
        if (form != null && returnsToForm) {
            MinecraftAccess.setScreen(MinecraftClient.getInstance(), form);
        } else {
            screen.rebuildWaypointControls();
        }
    }

    void selectExportCorner(final double mouseX, final double mouseY) {
        final int blockX = (int) Math.floor(screen.centerX + (mouseX - screen.width / 2.0) * screen.scale);
        final int blockZ = (int) Math.floor(screen.centerZ + (mouseY - screen.height / 2.0) * screen.scale);
        final Optional<MapExportBounds> completed = exportSelection.select(blockX, blockZ);
        if (completed.isEmpty()) {
            rebuildExportSelectionControls();
            return;
        }
        rebuildExportSelectionControls();
    }

    void finishExportSelection() {
        final MapExportScreen form = exportSelectionScreen;
        final Optional<MapExportBounds> completed = exportSelection.bounds();
        if (form == null || completed.isEmpty()) {
            return;
        }
        exportSelectionScreen = null;
        exportSelectionReturnsToForm = false;
        exportSelection.reset();
        form.applySelection(completed.get());
        MinecraftAccess.setScreen(MinecraftClient.getInstance(), form);
    }
    void drawExportSelection(final GuiDraw draw, final int mouseX, final int mouseY) {
        if (exportSelectionScreen == null) {
            return;
        }
        final String instruction = Texts.translatable(
            exportSelection.bounds().isPresent()
                ? "confluxmap.screen.map_export.selection_ready"
                : exportSelection.first().isEmpty()
                    ? "confluxmap.screen.map_export.select_first"
                    : "confluxmap.screen.map_export.select_second"
        ).getString();
        draw.drawTextWithShadow(
            screen.font(),
            instruction,
            screen.width / 2f - screen.font().getWidth(instruction) / 2f,
            MARGIN,
            TEXT_COLOR
        );
        final Optional<MapExportSelection.Point> first = exportSelection.first();
        if (first.isEmpty()) {
            return;
        }
        final int cursorX = (int) Math.floor(screen.centerX + (mouseX - screen.width / 2.0) * screen.scale);
        final int cursorZ = (int) Math.floor(screen.centerZ + (mouseY - screen.height / 2.0) * screen.scale);
        final MapExportBounds preview = exportSelection.bounds().orElseGet(() -> MapExportBounds.between(
            first.get().x(), first.get().z(), cursorX, cursorZ
        ));
        final int minX = preview.minX();
        final int minZ = preview.minZ();
        final int maxX = preview.maxX();
        final int maxZ = preview.maxZ();
        final float left = (float) (screen.width / 2.0 + (minX - screen.centerX) / screen.scale);
        final float top = (float) (screen.height / 2.0 + (minZ - screen.centerZ) / screen.scale);
        final float right = (float) (screen.width / 2.0 + ((long) maxX + 1L - screen.centerX) / screen.scale);
        final float bottom = (float) (screen.height / 2.0 + ((long) maxZ + 1L - screen.centerZ) / screen.scale);
        final MatrixStack matrices = draw.matrices();
        GuiShapes.fillRect(matrices, left, top, right - left, bottom - top, 0x303399FF);
        GuiShapes.fillRect(matrices, left, top, right - left, 1f, 0xFF66CCFF);
        GuiShapes.fillRect(matrices, left, bottom - 1f, right - left, 1f, 0xFF66CCFF);
        GuiShapes.fillRect(matrices, left, top, 1f, bottom - top, 0xFF66CCFF);
        GuiShapes.fillRect(matrices, right - 1f, top, 1f, bottom - top, 0xFF66CCFF);
    }
}
