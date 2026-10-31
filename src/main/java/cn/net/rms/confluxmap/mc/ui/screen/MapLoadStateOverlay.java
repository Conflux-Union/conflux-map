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

/** The chunk load-state display mode: overlay cells, legend, and detail toggle. */
final class MapLoadStateOverlay {
    private static final int LOAD_STATE_ENTITY_COLOR = 0x7048B85E;
    private static final int LOAD_STATE_BLOCK_COLOR = 0x70D8A83E;
    private static final int LOAD_STATE_BORDER_COLOR = 0x704E78C4;
    private static final int LOAD_STATE_UNLOADED_COLOR = 0x00000000;
    private static final int LOAD_STATE_OUTLINE_COLOR = 0xA0101018;
    private static final int LOAD_STATE_LEGEND_BACKGROUND = 0xD0181822;

    private final FullscreenMapScreen screen;
    MapLoadStateOverlay(final FullscreenMapScreen screen) {
        this.screen = screen;
    }

    private ButtonWidget loadStateDetailButton;

    boolean hasDetailButton() {
        return loadStateDetailButton != null;
    }

        void addLoadStateDetailControl() {
        if (!screen.loadStateMode()) {
            return;
        }
        final int buttonWidth = Math.min(180, Math.max(90, screen.width - 2 * (MARGIN + 80)));
        final int x = (screen.width - buttonWidth) / 2;
        loadStateDetailButton = screen.addWidget(Widgets.button(
            x,
            MARGIN,
            buttonWidth,
            20,
            loadStateDetailLabel(),
            ignored -> {
                screen.config.chunkLoadDetailMode = screen.config.chunkLoadDetailMode == ChunkLoadDetailMode.BANDS
                    ? ChunkLoadDetailMode.EXACT
                    : ChunkLoadDetailMode.BANDS;
                ConfluxMapClient.get().configIo().save(screen.config);
                loadStateDetailButton.setMessage(loadStateDetailLabel());
            }
        ));
    }
    private Text loadStateDetailLabel() {
        final String valueKey = screen.config.chunkLoadDetailMode == ChunkLoadDetailMode.EXACT
            ? "confluxmap.map.load_state.detail.exact"
            : "confluxmap.map.load_state.detail.bands";
        return Texts.translatable(
            "confluxmap.map.load_state.detail",
            Texts.translatable(valueKey).getString()
        );
    }
    void drawChunkLoadStateOverlay(final GuiDraw draw) {
        final MatrixStack matrices = draw.matrices();
        final int minChunkX = TileMath.blockToChunk(
            (int) Math.floor(screen.centerX - screen.width / 2.0 * screen.scale)
        );
        final int maxChunkX = TileMath.blockToChunk(
            (int) Math.ceil(screen.centerX + screen.width / 2.0 * screen.scale)
        );
        final int minChunkZ = TileMath.blockToChunk(
            (int) Math.floor(screen.centerZ - screen.height / 2.0 * screen.scale)
        );
        final int maxChunkZ = TileMath.blockToChunk(
            (int) Math.ceil(screen.centerZ + screen.height / 2.0 * screen.scale)
        );
        screen.chunkLoadStates.reportViewport(
            screen.gameBridge.session().dimension(), minChunkX, maxChunkX, minChunkZ, maxChunkZ
        );

        final float chunkSize = (float) ChunkScreenRect.chunkSize(screen.scale);
        final ChunkLoadOverlayStyle style = ChunkLoadOverlayStyle.forChunkWidth(
            chunkSize, screen.config.chunkLoadDetailMode
        );
        for (final LoadStateDeltaS2C.Entry entry : screen.chunkLoadStates.snapshot().entries()) {
            if (entry.chunkX() < minChunkX || entry.chunkX() > maxChunkX
                || entry.chunkZ() < minChunkZ || entry.chunkZ() > maxChunkZ) {
                continue;
            }
            final ChunkScreenRect rect = ChunkScreenRect.forChunk(
                entry.chunkX(), entry.chunkZ(), screen.centerX, screen.centerZ, screen.width, screen.height, screen.scale
            );
            final float screenX = (float) rect.x();
            final float screenY = (float) rect.y();
            GuiShapes.fillRect(
                matrices, screenX, screenY, chunkSize, chunkSize, loadStateColor(entry.band())
            );
            if (style.drawOutline()) {
                drawChunkLoadOutline(matrices, screenX, screenY, chunkSize);
            }
            if (style.drawLevelLabel()) {
                final String level = "L" + entry.level();
                final int textWidth = screen.font().getWidth(level);
                draw.drawTextWithShadow(
                    screen.font(),
                    level,
                    screenX + (chunkSize - textWidth) / 2f,
                    screenY + (chunkSize - screen.font().fontHeight) / 2f,
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
        GuiShapes.fillRect(matrices, x, y, size, 1f, LOAD_STATE_OUTLINE_COLOR);
        GuiShapes.fillRect(matrices, x, y + size - 1f, size, 1f, LOAD_STATE_OUTLINE_COLOR);
        GuiShapes.fillRect(matrices, x, y, 1f, size, LOAD_STATE_OUTLINE_COLOR);
        GuiShapes.fillRect(matrices, x + size - 1f, y, 1f, size, LOAD_STATE_OUTLINE_COLOR);
    }

    void drawChunkLoadStateLegend(final GuiDraw draw) {
        final int rowHeight = screen.font().fontHeight + 3;
        final int legendWidth = Math.min(180, Math.max(130, screen.width / 3));
        final int legendHeight = rowHeight * 5 + 8;
        final int x = MARGIN;
        final int y = screen.height - MARGIN - legendHeight;
        draw.fill(x, y, x + legendWidth, y + legendHeight, LOAD_STATE_LEGEND_BACKGROUND);
        final String status = Texts.translatable(
            screen.chunkLoadStates.snapshot().complete()
                ? "confluxmap.map.load_state.ready"
                : "confluxmap.map.load_state.loading"
        ).getString();
        draw.drawTextWithShadow(screen.font(), status, x + 5, y + 4, TEXT_COLOR);
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
            screen.font(),
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
    String chunkLoadStateCursorText(final int mouseX, final int mouseY) {
        final int blockX = (int) Math.floor(screen.centerX + (mouseX - screen.width / 2.0) * screen.scale);
        final int blockZ = (int) Math.floor(screen.centerZ + (mouseY - screen.height / 2.0) * screen.scale);
        final int chunkX = TileMath.blockToChunk(blockX);
        final int chunkZ = TileMath.blockToChunk(blockZ);
        final Optional<LoadStateDeltaS2C.Entry> state = screen.chunkLoadStates.snapshot().get(chunkX, chunkZ);
        final String stateText;
        if (state.isPresent()) {
            final LoadStateDeltaS2C.Entry entry = state.get();
            stateText = screen.config.chunkLoadDetailMode == ChunkLoadDetailMode.EXACT
                ? loadStateBandName(entry.band()) + " · L" + entry.level()
                : loadStateBandName(entry.band());
        } else {
            stateText = Texts.translatable(
                screen.chunkLoadStates.snapshot().complete()
                    ? "confluxmap.map.load_state.unloaded"
                    : "confluxmap.map.load_state.unknown"
            ).getString();
        }
        return blockX + ", " + blockZ + " · "
            + Texts.translatable("confluxmap.map.load_state.chunk", chunkX, chunkZ).getString()
            + " · " + stateText;
    }
}
