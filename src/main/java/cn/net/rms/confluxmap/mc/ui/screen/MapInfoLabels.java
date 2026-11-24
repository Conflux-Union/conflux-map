package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.net.MapSyncProgress;
import cn.net.rms.confluxmap.core.predict.PredictionDimensions;
import cn.net.rms.confluxmap.core.predict.WorldPreset;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.update.UpdateCheckService;
import cn.net.rms.confluxmap.mc.ui.BiomeLabels;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.MapLayerText;
import cn.net.rms.confluxmap.mc.world.ClientChunkLookup;
import cn.net.rms.confluxmap.mc.world.FullscreenMapLayerPolicy;
import java.util.Optional;
import java.util.OptionalInt;
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
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import static cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen.*;

/** The informational text readouts around the map edge: dimension, layer, prediction, sync, scale, cursor, update. */
final class MapInfoLabels {
    private static final int SYNCING_TEXT_COLOR = 0xFFFFE066;
    private static final int SYNCED_TEXT_COLOR = 0xFF80E080;
    private static final int SYNC_FAILED_TEXT_COLOR = 0xFFFF7777;
    private static final int UPDATE_TEXT_COLOR = 0xFFFFE066;

    private final FullscreenMapScreen screen;
    MapInfoLabels(final FullscreenMapScreen screen) {
        this.screen = screen;
    }

        void drawDimensionLabel(final GuiDraw draw) {
        final SessionGuard.Session session = screen.viewSession();
        final String text = screen.mapBrowser.browsing()
            ? shortWorldId(session.world().worldId()) + " · "
                + dimensionDisplayName(session.dimension())
            : dimensionDisplayName(session.dimension());
        draw.drawTextWithShadow(screen.font(), text, MARGIN, MARGIN, TEXT_COLOR);
    }

    static String shortWorldId(final String worldId) {
        return worldId.length() <= 16 ? worldId : worldId.substring(0, 8) + "…";
    }
    /** Deliverable D: the fullscreen map shows the active layer for the current dimension. */
    void drawLayerLabel(final GuiDraw draw) {
        final MapLayer layer = screen.viewLayer();
        final int currentY = screen.viewingLiveSession()
            ? screen.layerSelector.current().pivotY()
            : layer.param();
        final String text = MapLayerText.label(layer, currentY);
        draw.drawTextWithShadow(screen.font(), text, MARGIN, MARGIN + screen.font().fontHeight + 2, TEXT_COLOR);
    }

    void drawPredictionLabel(final GuiDraw draw) {
        if (!predictionLabelVisible()) {
            return;
        }
        final DimensionId dimension = screen.viewSession().dimension();
        final WorldPreset preset = screen.predictionState.preset(dimension);
        final String text;
        if (PredictionDimensions.supported(dimension) && !screen.predictionState.predictable(dimension)) {
            // Seed known but this dimension can't compose an underlay (debug/custom worldgen, or
            // superflat without surface info): say so instead of silently dropping the label.
            text = Texts.translatable(
                "confluxmap.map.prediction_unavailable", presetDisplayName(preset)
            ).getString();
        } else {
            final String mode = Texts.translatable(
                "confluxmap.config.prediction.mode." + screen.config.predictionViewMode.name().toLowerCase(java.util.Locale.ROOT)
            ).getString();
            final String modeLine = Texts.translatable("confluxmap.config.prediction.view_mode", mode).getString();
            text = preset.terrainApproximate()
                ? modeLine + Texts.translatable("confluxmap.map.prediction_approx_suffix", presetDisplayName(preset)).getString()
                : modeLine;
        }
        draw.drawTextWithShadow(
            screen.font(), text, MARGIN, MARGIN + screen.font().fontHeight * 2 + 4, TEXT_COLOR
        );
    }

    /** A seedless superflat session still predicts, so the label keys off either signal. */
    private boolean predictionLabelVisible() {
        return screen.config.predictionEnabled
            && screen.viewingLiveWorld()
            && (screen.predictionState.seedKnown() || screen.predictionState.predictable(screen.viewSession().dimension()));
    }

    private static String presetDisplayName(final WorldPreset preset) {
        return Texts.translatable(
            "confluxmap.world_preset." + preset.name().toLowerCase(java.util.Locale.ROOT)
        ).getString();
    }

    void drawServerSyncLabel(final GuiDraw draw) {
        if (!screen.viewingLiveWorld()) {
            return;
        }
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
        draw.drawTextWithShadow(screen.font(), text, MARGIN, MARGIN + row * (screen.font().fontHeight + 2), color);
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

    void drawScaleLabel(final GuiDraw draw) {
        final String text = Texts.translatable(
            "confluxmap.map.scale", FullscreenZoomLabel.format(screen.scale)
        ).getString();
        final int textWidth = screen.font().getWidth(text);
        final int x = screen.embeddedLayout == null
            ? screen.width - MARGIN - textWidth
            : screen.embeddedLayout.mapRightAlignedX(textWidth, MARGIN);
        draw.drawTextWithShadow(screen.font(), text, x, MARGIN, TEXT_COLOR);
    }
    /**
     * Bottom-center footer: the raw cursor position, or - while hovering a marker
     * (deliverable C) - that waypoint's own stored X/Y/Z instead. When not hovering a marker, the cursor's biome name
     * (see {@link #cursorBiomeName}) is appended if it can be resolved.
     */
    void drawCursorCoords(final GuiDraw draw, final int mouseX, final int mouseY) {
        final String text;
        if (screen.hoveredWaypoint != null) {
            text = (int) Math.floor(screen.hoveredWaypoint.x()) + ", "
                + (int) Math.floor(screen.hoveredWaypoint.y()) + ", "
                + (int) Math.floor(screen.hoveredWaypoint.z())
                + (screen.hoveredWaypoint.originWorldLabel().isEmpty()
                    ? ""
                    : " · " + Texts.translatable(
                        "confluxmap.screen.waypoints.cross_world_origin",
                        screen.hoveredWaypoint.originWorldLabel()
                    ).getString());
        } else if (screen.hoveredStructure != null) {
            text = Texts.translatable(screen.hoveredStructure.translationKey()).getString()
                + " · " + screen.hoveredStructure.blockX() + ", " + screen.hoveredStructure.blockZ();
        } else if (screen.loadStateMode()) {
            text = screen.loadStateOverlay.chunkLoadStateCursorText(mouseX, mouseY);
        } else {
            final int blockX = (int) Math.floor(screen.centerX + (mouseX - screen.width / 2.0) * screen.scale);
            final int blockZ = (int) Math.floor(screen.centerZ + (mouseY - screen.height / 2.0) * screen.scale);
            final String biomeName = cursorBiomeName(blockX, blockZ);
            text = blockX + ", " + blockZ + (biomeName == null ? "" : " · " + biomeName);
        }
        final int textWidth = screen.font().getWidth(text);
        draw.drawTextWithShadow(screen.font(), text, screen.width / 2f - textWidth / 2f, screen.height - MARGIN - 10, TEXT_COLOR);
    }
    /**
     * Best-effort biome name at the given column, for the footer readout. Loaded real chunks win;
     * unexplored columns fall back to the same predicted biome sample and LOD as the rendered
     * underlay. Returns null when neither source has a resolvable biome identifier.
     */
    private String cursorBiomeName(final int blockX, final int blockZ) {
        final SessionGuard.Session session = screen.viewSession();
        final MapLayer layer = screen.viewLayer();
        final ClientWorld world = screen.client().world;
        if (screen.viewingLiveSession() && world != null) {
            final int playerY = screen.gameBridge.player().map(p -> p.blockY()).orElse(world.getBottomY());
            final int biomeY = FullscreenMapLayerPolicy.biomeSampleY(
                layer, playerY, screen.layerSelector.current().pivotY()
            );
            final BlockPos pos = new BlockPos(
                blockX, MathHelper.clamp(biomeY, world.getBottomY(), world.getTopY() - 1), blockZ
            );
            if (ClientChunkLookup.isLoaded(world, blockX, blockZ)) {
                final Identifier biomeId = Regs.biomeIdAt(world, pos);
                if (biomeId != null) {
                    return translatedBiomeName(biomeId);
                }
            }
        }
        if (!screen.predictionActive(layer, session, screen.biomeMode())) {
            return null;
        }
        final OptionalInt predicted = screen.predictionTiles.predictedBiomeAt(
            session.dimension(), layer, screen.currentLod(), blockX, blockZ
        );
        if (predicted.isEmpty()) {
            return null;
        }
        return predictedBiomeName(predicted.getAsInt());
    }

    /**
     * A predicted biome only knows its cubiomes id, whose canonical vanilla name is the 1.17-era
     * one; biomes renamed since have no translation under the old key on modern versions, so the
     * chain in {@link BiomeLabels#predictedDisplayName} also tries the datapack set and finally
     * the canonical registry id instead of leaking a raw {@code biome.minecraft.snowy_tundra}.
     */
    private static String predictedBiomeName(final int cubiomesId) {
        return BiomeLabels.predictedDisplayName(cubiomesId);
    }

    /** Bottom-right corner: a passive notice while a newer release is known (the chat line carries the clickable link). */
    void drawUpdateBadge(final GuiDraw draw) {
        final Optional<UpdateCheckService.UpdateInfo> info = screen.updateCheck.available();
        if (info.isEmpty()) {
            return;
        }
        final String text = Texts.translatable("confluxmap.map.update_badge", info.get().latestVersion()).getString();
        final int textWidth = screen.font().getWidth(text);
        draw.drawTextWithShadow(screen.font(), text, screen.width - MARGIN - textWidth, screen.height - MARGIN - 10, UPDATE_TEXT_COLOR);
    }

    private static String translatedBiomeName(final Identifier biomeId) {
        return BiomeLabels.displayName(biomeId);
    }
}
