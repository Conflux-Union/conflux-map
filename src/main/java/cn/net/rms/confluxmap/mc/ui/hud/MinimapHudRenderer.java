package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.HudAvoidanceLayout;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.trail.PlayerTrailProjection;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderEntry;
import cn.net.rms.confluxmap.core.waypoint.WaypointVerticalRelation;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import cn.net.rms.confluxmap.mc.render.OffscreenCanvas;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.ui.AnnotationRenderer;
import cn.net.rms.confluxmap.compat.Texts;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.PlayerTrailRenderer;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
//#if MC>=260100
//$$ import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
//#else
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
//#endif
import net.minecraft.client.MinecraftClient;
//#if MC>=12000
//$$ import net.minecraft.client.gui.DrawContext;
//#endif
//#if MC>=12100
//$$ import net.minecraft.client.render.RenderTickCounter;
//#endif
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;

/**
 * Renders the always-on minimap HUD, showing whichever layer {@link LayerSelector}
 * currently has active for the player's dimension (surface, cave, nether, etc).
 * Supports zoom (0.5/1/2/4 blocks per pixel), player-facing-up rotation
 * (map rotates, arrow stays up) or north-locked mode (arrow rotates),
 * square scissor or circular alpha-mask clipping, a center arrow,
 * upright cardinal letters, and a coordinates/biome info line.
 * Render thread only (it's an {@link HudRenderCallback}).
 */
public final class MinimapHudRenderer {
    private static final int BORDER_THICKNESS = 1;
    private static final int BORDER_COLOR = 0xB0FFFFFF;
    private static final int BACKGROUND_COLOR = 0x80101018;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ARROW_OUTLINE = 0xFF101010;
    private static final int ARROW_FILL = 0xFFFFFFFF;
    private static final float[] BLOCKS_PER_PIXEL = {0.5f, 1f, 2f, 4f};
    /** Half of the ~7px-across VoxelMap-style diamond/cross marker (deliverable B). */
    private static final float WAYPOINT_MARKER_HALF_SIZE = 3.5f;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final TileService tiles;
    private final TileTextureManager textures;
    private final OffscreenCanvas canvas = new OffscreenCanvas();
    private final EntityRadarScanner radarScanner;
    private final EntityIconManager iconManager;
    private final PlayerTrail playerTrail;
    private final AnnotationService annotations;
    private final LayerSelector layerSelector;
    private final WaypointRenderCatalog waypointRenderCatalog;
    private final RadarViewRange radarViewRange;

    public MinimapHudRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final TileService tiles,
        final TileTextureManager textures,
        final EntityRadarScanner radarScanner,
        final EntityIconManager iconManager,
        final PlayerTrail playerTrail,
        final AnnotationService annotations,
        final LayerSelector layerSelector,
        final WaypointRenderCatalog waypointRenderCatalog,
        final RadarViewRange radarViewRange
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.tiles = tiles;
        this.textures = textures;
        this.radarScanner = radarScanner;
        this.iconManager = iconManager;
        this.playerTrail = playerTrail;
        this.annotations = annotations;
        this.layerSelector = layerSelector;
        this.waypointRenderCatalog = waypointRenderCatalog;
        this.radarViewRange = radarViewRange;
    }

    public void register() {
        //#if MC>=260100
        //$$ // 26.1 replaced the single HUD callback with an ordered element list; appending last
        //$$ // keeps this drawing over the vanilla HUD exactly as the callback did.
        //$$ HudElementRegistry.addLast(cn.net.rms.confluxmap.compat.Ids.of("confluxmap", "minimap"), this::render);
        //#else
        HudRenderCallback.EVENT.register(this::render);
        //#endif
    }

    /**
     * {@link net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback} fires once
     * per rendered frame regardless of whether a {@link net.minecraft.client.gui.screen.Screen}
     * is open (the HUD layer draws before the screen layer, it just ends up covered).
     * That makes this the single per-frame call site for {@link TileTextureManager#beginFrame()} -
     * {@link FullscreenMapScreen} relies on it having already run this frame and never
     * calls it itself, so it's never invoked twice in one frame.
     */
    //#if MC>=260100
    //$$ private void render(final GuiGraphicsExtractor context, final DeltaTracker tickCounter) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     final PoseStack matrices = draw.matrices();
    //$$     final float tickDelta = tickCounter.getGameTimeDeltaPartialTick(false);
    //#elseif MC>=12100
    //$$ private void render(final DrawContext context, final RenderTickCounter tickCounter) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     final MatrixStack matrices = draw.matrices();
    //$$     final float tickDelta = tickCounter.getTickDelta(false);
    //#elseif MC>=12000
    //$$ private void render(final DrawContext context, final float tickDelta) {
    //$$     final GuiDraw draw = GuiDraw.of(context);
    //$$     final MatrixStack matrices = draw.matrices();
    //#else
    private void render(final MatrixStack matrices, final float tickDelta) {
        final GuiDraw draw = GuiDraw.of(matrices);
    //#endif
        textures.beginFrame();
        final boolean fullscreenOpen = MinecraftAccess.screen(client) instanceof FullscreenMapScreen;
        final boolean containerOpen = MinecraftAccess.isContainerScreen(MinecraftAccess.screen(client));
        if (!MinimapHudVisibility.shouldRender(
            config.minimapEnabled, gameBridge.session().active(), fullscreenOpen, containerOpen
        )) {
            // FullscreenMapScreen owns radarViewRange while it's open; otherwise the minimap
            // isn't rendering at all, so there's no visible map surface for the radar to scan.
            if (!fullscreenOpen) {
                tiles.clearViewport();
                radarViewRange.set(0);
            }
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            tiles.clearViewport();
            radarViewRange.set(0);
            return;
        }
        final PlayerView player = playerView.get();

        tiles.setViewpoint(player.blockX(), player.blockZ());

        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        final MinimapPlacement.Layout configuredPlacement = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        final MinimapPlacement.Layout placement = avoidHudOverlap(
            screenWidth, screenHeight, configuredPlacement
        );
        final int size = placement.size();
        final int x0 = placement.x();
        final int y0 = placement.y();
        final float centerX = x0 + size / 2f;
        final float centerY = y0 + size / 2f;
        final boolean circle = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final boolean rotate = config.minimapRotate;
        final float mapAngle = rotate ? 180f - player.yawDegrees() : 0f;
        final List<Annotation> visibleAnnotations = config.annotationsOnHud && annotations.current() != null
            ? annotations.current().list(gameBridge.session().dimension())
            : List.of();

        // Radar scans exactly what this frame's minimap will show: the circle's radius, or
        // the square's half-diagonal (so a corner-cropped mob is still caught by the scan).
        final float minimapBlocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final double visibleRadius = size / 2.0 * minimapBlocksPerPixel * (circle ? 1.0 : Math.sqrt(2));
        radarViewRange.set(visibleRadius);

        if (circle) {
            // Real geometric clipping: render the square map into an off-screen canvas,
            // then sample it back as a textured disk. Unlike destination-alpha masking
            // this cannot leak outside the circle regardless of framebuffer state.
            final int canvasPx = Math.max(64, (int) Math.round(size * client.getWindow().getScaleFactor()));
            canvas.begin(canvasPx);
            final MatrixStack fbo = new MatrixStack();
            final float unit = canvasPx / (float) size;
            fbo.scale(unit, unit, 1f);
            RenderUtil.fillRect(fbo, 0, 0, size, size, BACKGROUND_COLOR);
            fbo.push();
            fbo.translate(size / 2f, size / 2f, 0);
            if (rotate) {
                RenderUtil.rotateZ(fbo, mapAngle);
            }
            RenderUtil.beginTexturedQuads();
            drawTiles(fbo, size, rotate, player);
            fbo.pop();
            drawPlayerTrail(fbo, player, size / 2f, size / 2f, size, mapAngle);
            if (!visibleAnnotations.isEmpty()) {
                AnnotationRenderer.drawGeometry(
                    fbo,
                    visibleAnnotations,
                    annotationProjection(player, size / 2f, size / 2f, size, mapAngle),
                    null
                );
            }
            canvas.end(client);

            RenderUtil.beginTexturedQuads();
            canvas.bindTexture();
            RenderUtil.drawTexturedDisk(matrices, centerX, centerY, size / 2f);
            RenderUtil.drawRing(matrices, centerX, centerY, size / 2f, BORDER_THICKNESS, BORDER_COLOR);
            AnnotationRenderer.drawLabels(
                draw,
                client.textRenderer,
                visibleAnnotations,
                annotationProjection(player, centerX, centerY, size, mapAngle),
                x0,
                y0,
                size,
                size,
                AnnotationRenderer.ClipShape.CIRCLE
            );
        } else {
            RenderUtil.fillRect(matrices, x0, y0, size, size, BACKGROUND_COLOR);
            RenderUtil.enableScissor(client, x0, y0, size, size);
            RenderUtil.beginTexturedQuads();
            matrices.push();
            matrices.translate(centerX, centerY, 0);
            if (rotate) {
                RenderUtil.rotateZ(matrices, mapAngle);
            }
            drawTiles(matrices, size, rotate, player);
            matrices.pop();
            drawPlayerTrail(matrices, player, centerX, centerY, size, mapAngle);
            if (!visibleAnnotations.isEmpty()) {
                final AnnotationProjection annotationProjection = annotationProjection(
                    player, centerX, centerY, size, mapAngle
                );
                AnnotationRenderer.drawGeometry(matrices, visibleAnnotations, annotationProjection, null);
                AnnotationRenderer.drawLabels(
                    draw,
                    client.textRenderer,
                    visibleAnnotations,
                    annotationProjection,
                    x0,
                    y0,
                    size,
                    size,
                    AnnotationRenderer.ClipShape.RECTANGLE
                );
            }
            RenderUtil.disableScissor();
            drawBorder(matrices, x0, y0, size);
        }

        drawRadar(draw, centerX, centerY, size, mapAngle, player, tickDelta);
        drawCardinals(draw, centerX, centerY, size, mapAngle);
        drawWaypointMarkers(draw, centerX, centerY, size, mapAngle, player);
        drawPlayerArrow(matrices, centerX, centerY, rotate ? 0f : player.yawDegrees() + 180f);
        drawInfoText(draw, player, x0, y0, size);
    }

    /** Uses the same completed scoreboard frame as status effects without changing saved placement. */
    private MinimapPlacement.Layout avoidHudOverlap(
        final int screenWidth,
        final int screenHeight,
        final MinimapPlacement.Layout configuredPlacement
    ) {
        if (client.player == null) {
            return configuredPlacement;
        }

        return HudAvoidanceLayout.resolveMinimap(
            config.minimapHudAvoidance,
            screenWidth,
            screenHeight,
            configuredPlacement,
            HudAvoidanceLayout.informationHeight(
                config.showCoordinates,
                config.showBiome,
                config.showLayerIndicator
            ),
            ScoreboardHudBounds.previousFrame(screenWidth, screenHeight)
        );
    }

    private void drawPlayerTrail(
        final MatrixStack matrices,
        final PlayerView player,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle
    ) {
        if (!config.playerTrailEnabled) {
            return;
        }
        PlayerTrailRenderer.draw(
            matrices,
            playerTrail,
            new PlayerTrailProjection(
                player.x(), player.z(), centerX, centerY,
                BLOCKS_PER_PIXEL[config.minimapZoomIndex], mapAngle, size, size
            ),
            config.playerTrailDurationSeconds,
            config.playerTrailDotSize
        );
    }

    private AnnotationProjection annotationProjection(
        final PlayerView player,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle
    ) {
        return new AnnotationProjection(
            player.x(),
            player.z(),
            centerX,
            centerY,
            BLOCKS_PER_PIXEL[config.minimapZoomIndex],
            mapAngle,
            size,
            size
        );
    }

    /**
     * In-range and edge-clamped icons for waypoints visible in the
     * current dimension. Reuses {@link #drawCardinal}'s exact rotation trick: the
     * marker's screen *position* is computed with the same manual cos/sin rotation
     * as the cardinal letters (this method runs after the tile-drawing push/pop, so
     * the active matrix here is unrotated - see the render() javadoc), while the
     * marker glyph itself is drawn with no rotation applied (upright). Out-of-range
     * waypoints reuse that same icon at the minimap edge so their identity remains
     * visible instead of changing into a generic direction arrow.
     */
    private void drawWaypointMarkers(
        final GuiDraw draw,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final PlayerView player
    ) {
        final float blocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final float pxPerBlock = 1f / blocksPerPixel;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        // Keep the marker plate and its one-pixel outline inside the minimap frame.
        final float limit = size / 2f - WAYPOINT_MARKER_HALF_SIZE - 4f;
        final boolean circleFrame = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final DimensionId currentDimension = gameBridge.session().dimension();

        for (final WaypointRenderEntry waypoint : waypointRenderCatalog.snapshot(currentDimension)) {
            final double dx = waypoint.x() - player.x();
            final double dz = waypoint.z() - player.z();
            if (config.waypointRenderDistance > 0) {
                final double dy = waypoint.y() - player.y();
                if (Math.sqrt(dx * dx + dy * dy + dz * dz) > config.waypointRenderDistance) {
                    continue;
                }
            }

            final float rawX = (float) (dx * pxPerBlock);
            final float rawY = (float) (dz * pxPerBlock);
            final float screenOffX = rawX * cos - rawY * sin;
            final float screenOffY = rawX * sin + rawY * cos;

            final boolean inRange = circleFrame
                ? Math.hypot(screenOffX, screenOffY) <= limit
                : Math.abs(screenOffX) <= limit && Math.abs(screenOffY) <= limit;

            if (inRange) {
                WaypointMarkerRenderer.draw(
                    draw, client.textRenderer, waypoint, centerX + screenOffX, centerY + screenOffY,
                    WAYPOINT_MARKER_HALF_SIZE, 1f, false,
                    WaypointVerticalRelation.between(waypoint.y(), player.y())
                );
            } else if (config.waypointEdgeIndicatorsEnabled) {
                final float k = circleFrame
                    ? limit / (float) Math.hypot(screenOffX, screenOffY)
                    : limit / Math.max(Math.abs(screenOffX), Math.abs(screenOffY));
                final float edgeX = screenOffX * k;
                final float edgeY = screenOffY * k;
                WaypointMarkerRenderer.draw(
                    draw,
                    client.textRenderer,
                    waypoint,
                    centerX + edgeX,
                    centerY + edgeY,
                    WAYPOINT_MARKER_HALF_SIZE,
                    1f,
                    false,
                    WaypointVerticalRelation.between(waypoint.y(), player.y())
                );
            }
        }
    }

    /**
     * Tiles are drawn as full 256-block quads positioned relative to the player,
     * in a coordinate space whose origin is the minimap center (the caller has
     * already translated/rotated the matrix). Clipping crops the excess.
     */
    private void drawTiles(final MatrixStack matrices, final int size, final boolean rotate, final PlayerView player) {
        final float blocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final float pxPerBlock = 1f / blocksPerPixel;
        final float coverRadius = size / 2f * blocksPerPixel * (rotate ? 1.4143f : 1f) + 8f;

        final int firstTileX = TileMath.blockToTile((int) Math.floor(player.x() - coverRadius));
        final int lastTileX = TileMath.blockToTile((int) Math.ceil(player.x() + coverRadius));
        final int firstTileZ = TileMath.blockToTile((int) Math.floor(player.z() - coverRadius));
        final int lastTileZ = TileMath.blockToTile((int) Math.ceil(player.z() + coverRadius));
        final MapLayer layer = layerSelector.current().layer();
        final String layerId = layer.cacheId();
        tiles.setViewport(layer, 0, firstTileX, lastTileX, firstTileZ, lastTileZ);

        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey key = new TileKey(
                    gameBridge.session().world(), gameBridge.session().dimension(),
                    layerId, 0, tileX, tileZ
                );
                if (!textures.bind(key)) {
                    continue;
                }
                final float screenX = (float) ((key.originBlockX() - player.x()) * pxPerBlock);
                final float screenY = (float) ((key.originBlockZ() - player.z()) * pxPerBlock);
                final float quadSize = TileMath.TILE_SIZE * pxPerBlock;
                RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
            }
        }
    }

    /**
     * Draws one dot per {@link RadarEntry} in {@link #radarScanner}'s latest snapshot. Positions
     * use the same world-delta * pxPerBlock projection as {@link #drawTiles}, but — like
     * {@link #drawCardinals}/{@link #drawCardinal} — rotate the offset by {@code mapAngle}
     * explicitly and draw the marker shape unrotated afterward, so markers stay upright
     * regardless of the minimap's current rotation.
     */
    private void drawRadar(
        final GuiDraw draw,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final PlayerView player,
        final float tickDelta
    ) {
        if (!config.radarEnabled || client.world == null) {
            return;
        }
        final float blocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final float pxPerBlock = 1f / blocksPerPixel;
        final float cullRadiusSq = (size / 2f) * (size / 2f);
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        final List<RadarMarkerRenderer.Marker> markers = new ArrayList<>();
        for (final RadarEntry entry : radarScanner.snapshot()) {
            double ex = entry.x();
            double ez = entry.z();
            int yDelta = entry.yDelta();
            // Prefer a live, per-frame interpolated position over the scan-time snapshot so
            // motion is smooth every frame instead of snapping once per scan interval (spec sec 5).
            final Entity live = client.world.getEntityById(entry.entityId());
            if (live != null) {
                ex = MathHelper.lerp(tickDelta, live.prevX, live.getX());
                ez = MathHelper.lerp(tickDelta, live.prevZ, live.getZ());
                yDelta = (int) Math.round(live.getY() - player.y());
            }

            final float dirX = (float) ((ex - player.x()) * pxPerBlock);
            final float dirY = (float) ((ez - player.z()) * pxPerBlock);
            if (dirX * dirX + dirY * dirY > cullRadiusSq) {
                continue;
            }
            final float x = centerX + dirX * cos - dirY * sin;
            final float y = centerY + dirX * sin + dirY * cos;
            markers.add(new RadarMarkerRenderer.Marker(entry, x, y, yDelta, live));
        }
        RadarMarkerRenderer.drawAll(draw, client, config, iconManager, markers);
    }

    private void drawPlayerArrow(final MatrixStack matrices, final float centerX, final float centerY, final float angle) {
        matrices.push();
        matrices.translate(centerX, centerY, 0);
        RenderUtil.rotateZ(matrices, angle);
        RenderUtil.fillTriangle(matrices, 0f, -6.5f, -5f, 5.5f, 5f, 5.5f, ARROW_OUTLINE);
        RenderUtil.fillTriangle(matrices, 0f, -5f, -3.5f, 4f, 3.5f, 4f, ARROW_FILL);
        matrices.pop();
    }

    /** Cardinal letters sit on the (possibly rotated) compass ring but are always drawn upright. */
    private void drawCardinals(final GuiDraw draw, final float centerX, final float centerY, final int size, final float mapAngle) {
        final float radius = size / 2f - 7f;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        drawCardinal(draw, "N", centerX, centerY, 0f, -radius, cos, sin);
        drawCardinal(draw, "E", centerX, centerY, radius, 0f, cos, sin);
        drawCardinal(draw, "S", centerX, centerY, 0f, radius, cos, sin);
        drawCardinal(draw, "W", centerX, centerY, -radius, 0f, cos, sin);
    }

    private void drawCardinal(
        final GuiDraw draw,
        final String letter,
        final float centerX,
        final float centerY,
        final float dirX,
        final float dirY,
        final float cos,
        final float sin
    ) {
        final float x = centerX + dirX * cos - dirY * sin;
        final float y = centerY + dirX * sin + dirY * cos;
        final int width = client.textRenderer.getWidth(letter);
        draw.drawTextWithShadow(client.textRenderer, letter, x - width / 2f, y - 4f, TEXT_COLOR);
    }

    private void drawInfoText(final GuiDraw draw, final PlayerView player, final int x0, final int y0, final int size) {
        if (!config.showCoordinates && !config.showBiome && !config.showLayerIndicator) {
            return;
        }
        final int lineHeight = HudAvoidanceLayout.INFORMATION_LINE_HEIGHT;
        int lines = 0;
        if (config.showCoordinates) {
            lines++;
        }
        if (config.showBiome) {
            lines++;
        }
        if (config.showLayerIndicator) {
            lines++;
        }
        final float belowY = y0 + size + HudAvoidanceLayout.INFORMATION_GAP;
        final float yAfterBelowLines = belowY + lines * lineHeight;
        float y = yAfterBelowLines <= client.getWindow().getScaledHeight()
            ? belowY
            : Math.max(0, y0 - lines * lineHeight - HudAvoidanceLayout.INFORMATION_GAP);
        final float centerX = x0 + size / 2f;

        if (config.showCoordinates) {
            final String coords = player.blockX() + ", " + player.blockY() + ", " + player.blockZ();
            drawCenteredLine(draw, coords, centerX, y);
            y += lineHeight;
        }
        if (config.showBiome) {
            final String biome = biomeName(player);
            if (!biome.isEmpty()) {
                drawCenteredLine(draw, biome, centerX, y);
            }
            y += lineHeight;
        }
        if (config.showLayerIndicator) {
            drawCenteredLine(draw, layerIndicatorText(), centerX, y);
        }
    }

    /** cave-nether-layers.md-driven layer name, keyed off {@link MapLayer.Type#id()} (e.g. "confluxmap.layer.cave"). */
    private String layerIndicatorText() {
        final MapLayer.Type type = layerSelector.current().layer().type();
        return Texts.translatable("confluxmap.layer." + type.id()).getString();
    }

    private void drawCenteredLine(final GuiDraw draw, final String text, final float centerX, final float y) {
        final int width = client.textRenderer.getWidth(text);
        final float x = Math.max(
            0,
            Math.min(client.getWindow().getScaledWidth() - width, centerX - width / 2f)
        );
        draw.drawTextWithShadow(client.textRenderer, text, x, y, TEXT_COLOR);
    }

    private String biomeName(final PlayerView player) {
        if (client.world == null) {
            return "";
        }
        final Identifier id = Regs.biomeIdAt(
            client.world, new BlockPos(player.blockX(), player.blockY(), player.blockZ())
        );
        if (id == null) {
            return "";
        }
        final Text name = Texts.translatable("biome." + id.getNamespace() + "." + id.getPath());
        return name.getString();
    }

    private void drawBorder(final MatrixStack matrices, final int x0, final int y0, final int size) {
        RenderUtil.fillRect(matrices, x0, y0, size, BORDER_THICKNESS, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0, y0 + size - BORDER_THICKNESS, size, BORDER_THICKNESS, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0, y0, BORDER_THICKNESS, size, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0 + size - BORDER_THICKNESS, y0, BORDER_THICKNESS, size, BORDER_COLOR);
    }

}
