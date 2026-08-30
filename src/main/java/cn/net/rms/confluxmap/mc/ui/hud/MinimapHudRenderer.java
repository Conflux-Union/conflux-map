package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.compat.Regs;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapInformationLayout;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.trail.PlayerTrailProjection;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
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
import cn.net.rms.confluxmap.mc.ui.MapLayerText;
import cn.net.rms.confluxmap.mc.ui.PlayerMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.PlayerTrailRenderer;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.UiTextureRegion;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.world.ClientChunkLookup;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.BooleanSupplier;
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
    private static final int PLAYER_MARKER_COLOR = 0xFFFFFFFF;
    private static final float[] BLOCKS_PER_PIXEL = {0.5f, 1f, 2f, 4f};
    private static final float PLAYER_MARKER_BOUNDING_RADIUS = (float) Math.hypot(8f, 8f);
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
    private final UiResourceTheme uiTheme;
    private final Consumer<ChunkViewport> captureViewportPublisher;
    private final BooleanSupplier liveTerrainPaused;

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
        final RadarViewRange radarViewRange,
        final UiResourceTheme uiTheme,
        final Consumer<ChunkViewport> captureViewportPublisher,
        final BooleanSupplier liveTerrainPaused
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
        this.uiTheme = uiTheme;
        this.captureViewportPublisher = captureViewportPublisher;
        this.liveTerrainPaused = liveTerrainPaused;
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
            config.minimapEnabled,
            gameBridge.session().active(),
            fullscreenOpen,
            containerOpen,
            MinecraftAccess.isFullDebugOverlayVisible(client)
        )) {
            captureViewportPublisher.accept(null);
            // FullscreenMapScreen owns radarViewRange while it's open; otherwise the minimap
            // isn't rendering at all, so there's no visible map surface for the radar to scan.
            if (!fullscreenOpen) {
                tiles.clearViewport();
                radarViewRange.set(0);
            }
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.viewpoint(tickDelta);
        if (playerView.isEmpty()) {
            tiles.clearViewport();
            radarViewRange.set(0);
            captureViewportPublisher.accept(null);
            return;
        }
        final PlayerView player = playerView.get();

        tiles.setViewpoint(player.blockX(), player.blockZ());

        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        final MinimapPlacement.Layout placement = MinimapPlacement.resolve(
            screenWidth,
            screenHeight,
            config.minimapSize,
            config.minimapPositionX,
            config.minimapPositionY
        );
        final int size = placement.size();
        final int x0 = placement.x();
        final int y0 = placement.y();
        final boolean circle = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final Optional<UiResourceTheme.MinimapFrame> minimapFrame = uiTheme.minimapFrame(circle);
        final int contentInset = minimapFrame
            .map(UiResourceTheme.MinimapFrame::contentInset)
            .orElse(0);
        final MinimapContentViewport viewport = MinimapContentViewport.resolve(
            x0, y0, size, contentInset
        );
        final int contentSize = viewport.size();
        final float centerX = viewport.centerX();
        final float centerY = viewport.centerY();
        final boolean rotate = config.minimapRotate;
        final float mapAngle = rotate ? 180f - player.yawDegrees() : 0f;
        final List<Annotation> visibleAnnotations = config.annotationsOnHud && annotations.current() != null
            ? annotations.current().list(gameBridge.session().dimension())
            : List.of();

        // Radar scans exactly what this frame's minimap will show: the circle's radius, or
        // the square's half-diagonal (so a corner-cropped mob is still caught by the scan).
        final float minimapBlocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final double visibleRadius = contentSize / 2.0
            * minimapBlocksPerPixel
            * (circle ? 1.0 : Math.sqrt(2));
        radarViewRange.set(visibleRadius);

        if (circle) {
            // Real geometric clipping: render the square map into an off-screen canvas,
            // then sample it back as a textured disk. Unlike destination-alpha masking
            // this cannot leak outside the circle regardless of framebuffer state.
            final int canvasPx = Math.max(
                64,
                (int) Math.round(contentSize * client.getWindow().getScaleFactor())
            );
            canvas.begin(canvasPx);
            final MatrixStack fbo = new MatrixStack();
            final float unit = canvasPx / (float) contentSize;
            fbo.scale(unit, unit, 1f);
            RenderUtil.fillRect(fbo, 0, 0, contentSize, contentSize, BACKGROUND_COLOR);
            fbo.push();
            fbo.translate(contentSize / 2f, contentSize / 2f, 0);
            if (rotate) {
                RenderUtil.rotateZ(fbo, mapAngle);
            }
            RenderUtil.beginTexturedQuads();
            drawTiles(fbo, contentSize, circle, mapAngle, player);
            fbo.pop();
            drawPlayerTrail(
                fbo, player,
                contentSize / 2f, contentSize / 2f, contentSize, mapAngle
            );
            if (!visibleAnnotations.isEmpty()) {
                AnnotationRenderer.drawGeometry(
                    fbo,
                    visibleAnnotations,
                    annotationProjection(
                        player,
                        contentSize / 2f, contentSize / 2f, contentSize, mapAngle
                    ),
                    null
                );
            }
            canvas.end(client);

            RenderUtil.beginTexturedQuads();
            canvas.bindTexture();
            RenderUtil.drawTexturedDisk(matrices, centerX, centerY, contentSize / 2f);
            drawFrame(matrices, x0, y0, size, true, minimapFrame);
            AnnotationRenderer.drawLabels(
                draw,
                client.textRenderer,
                visibleAnnotations,
                annotationProjection(player, centerX, centerY, contentSize, mapAngle),
                viewport.x(),
                viewport.y(),
                contentSize,
                contentSize,
                AnnotationRenderer.ClipShape.CIRCLE
            );
        } else {
            RenderUtil.fillRect(
                matrices, viewport.x(), viewport.y(), contentSize, contentSize, BACKGROUND_COLOR
            );
            RenderUtil.enableScissor(
                client, viewport.x(), viewport.y(), contentSize, contentSize
            );
            RenderUtil.beginTexturedQuads();
            matrices.push();
            matrices.translate(centerX, centerY, 0);
            if (rotate) {
                RenderUtil.rotateZ(matrices, mapAngle);
            }
            drawTiles(matrices, contentSize, circle, mapAngle, player);
            matrices.pop();
            drawPlayerTrail(matrices, player, centerX, centerY, contentSize, mapAngle);
            if (!visibleAnnotations.isEmpty()) {
                final AnnotationProjection annotationProjection = annotationProjection(
                    player, centerX, centerY, contentSize, mapAngle
                );
                AnnotationRenderer.drawGeometry(matrices, visibleAnnotations, annotationProjection, null);
                AnnotationRenderer.drawLabels(
                    draw,
                    client.textRenderer,
                    visibleAnnotations,
                    annotationProjection,
                    viewport.x(),
                    viewport.y(),
                    contentSize,
                    contentSize,
                    AnnotationRenderer.ClipShape.RECTANGLE
                );
            }
            RenderUtil.disableScissor();
            drawFrame(matrices, x0, y0, size, false, minimapFrame);
        }

        drawRadar(draw, centerX, centerY, contentSize, mapAngle, player, tickDelta);
        drawCardinals(draw, centerX, centerY, contentSize, mapAngle);
        drawWaypointMarkers(draw, centerX, centerY, contentSize, mapAngle, player);
        drawLocalPlayerMarker(matrices, player, centerX, centerY, contentSize, rotate, tickDelta);
        drawInfoText(draw, player, x0, y0, size);
    }

    /** Draws the real player's position relative to the active map viewpoint. */
    private void drawLocalPlayerMarker(
        final MatrixStack matrices,
        final PlayerView viewpoint,
        final float centerX,
        final float centerY,
        final int size,
        final boolean rotate,
        final float tickDelta
    ) {
        final Optional<PlayerView> localPlayer = gameBridge.player(tickDelta);
        if (localPlayer.isEmpty()) {
            return;
        }
        final PlayerView player = localPlayer.get();
        final float blocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final float dx = (float) ((player.x() - viewpoint.x()) / blocksPerPixel);
        final float dz = (float) ((player.z() - viewpoint.z()) / blocksPerPixel);
        final float mapAngle = rotate ? 180f - viewpoint.yawDegrees() : 0f;
        final double radians = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(radians);
        final float sin = (float) Math.sin(radians);
        float screenDx = dx * cos - dz * sin;
        float screenDy = dx * sin + dz * cos;
        final float limit = playerMarkerEdgeLimit(size);
        if (config.minimapShape == ConfluxConfig.Shape.CIRCLE) {
            final float distance = (float) Math.sqrt(screenDx * screenDx + screenDy * screenDy);
            if (distance > limit) {
                screenDx *= limit / distance;
                screenDy *= limit / distance;
            }
        } else {
            screenDx = MathHelper.clamp(screenDx, -limit, limit);
            screenDy = MathHelper.clamp(screenDy, -limit, limit);
        }
        PlayerMarkerRenderer.draw(
            client,
            matrices,
            uiTheme,
            config.playerMarkerStyle,
            centerX + screenDx,
            centerY + screenDy,
            rotate
                ? player.yawDegrees() - viewpoint.yawDegrees()
                : player.yawDegrees() + 180f,
            PLAYER_MARKER_COLOR
        );
    }

    static float playerMarkerEdgeLimit(final int size) {
        return size / 2f - PLAYER_MARKER_BOUNDING_RADIUS;
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
    private void drawTiles(
        final MatrixStack matrices,
        final int size,
        final boolean circle,
        final float mapAngle,
        final PlayerView player
    ) {
        final float blocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        final float pxPerBlock = 1f / blocksPerPixel;
        final double coverageFactor = captureCoverageFactor(
            circle ? ConfluxConfig.Shape.CIRCLE : ConfluxConfig.Shape.SQUARE,
            mapAngle
        );
        final float coverRadius = (float) (size / 2f * blocksPerPixel * coverageFactor + 8f);
        captureViewportPublisher.accept(captureViewport(
            player.x(),
            player.z(),
            size,
            blocksPerPixel,
            circle ? ConfluxConfig.Shape.CIRCLE : ConfluxConfig.Shape.SQUARE,
            mapAngle
        ));

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

    static ChunkViewport captureViewport(
        final double centerX,
        final double centerZ,
        final int size,
        final double blocksPerPixel,
        final ConfluxConfig.Shape shape,
        final double mapAngleDegrees
    ) {
        final double diameter = size * blocksPerPixel
            * captureCoverageFactor(shape, mapAngleDegrees)
            + 16.0;
        return ChunkViewport.covering(centerX, centerZ, 1, 1, diameter);
    }

    private static double captureCoverageFactor(
        final ConfluxConfig.Shape shape, final double mapAngleDegrees
    ) {
        if (shape == ConfluxConfig.Shape.CIRCLE) {
            return 1.0;
        }
        final double radians = Math.toRadians(mapAngleDegrees);
        return Math.abs(Math.cos(radians)) + Math.abs(Math.sin(radians));
    }

    /**
     * Draws each {@link RadarEntry} in {@link #radarScanner}'s latest snapshot. Players keep
     * their portraits; holding the player-list key expands the other colored diamond markers
     * into portraits or item icons. Positions use the same world-delta * pxPerBlock projection
     * as {@link #drawTiles}, but — like
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
        final boolean playerListPressed = MinecraftAccess.isPlayerListKeyPressed(client);
        final RadarMarkerRenderer.Presentation presentation = playerListPressed
            ? RadarMarkerRenderer.Presentation.detailed(config.radarShowPlayerNames)
            : RadarMarkerRenderer.Presentation.compact();
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
        RadarMarkerRenderer.drawAll(
            draw, client, config, iconManager, markers, presentation
        );
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
        final boolean terrainPaused = liveTerrainPaused.getAsBoolean();
        if (!config.showCoordinates && !config.showBiome && !config.showLayerIndicator
            && !terrainPaused) {
            return;
        }
        final int lineHeight = MinimapInformationLayout.LINE_HEIGHT;
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
        if (terrainPaused) {
            lines++;
        }
        final float belowY = y0 + size + MinimapInformationLayout.GAP;
        final float yAfterBelowLines = belowY + lines * lineHeight;
        float y = yAfterBelowLines <= client.getWindow().getScaledHeight()
            ? belowY
            : Math.max(0, y0 - lines * lineHeight - MinimapInformationLayout.GAP);
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
            y += lineHeight;
        }
        if (terrainPaused) {
            drawCenteredLine(
                draw,
                Texts.translatable("confluxmap.live_terrain.paused").getString(),
                centerX,
                y
            );
        }
    }

    /** cave-nether-layers.md-driven layer name, keyed off {@link MapLayer.Type#id()} (e.g. "confluxmap.layer.cave"). */
    private String layerIndicatorText() {
        final LayerSelector.Decision decision = layerSelector.current();
        return MapLayerText.label(decision.layer(), decision.pivotY());
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
        if (client.world == null
            || !ClientChunkLookup.isLoaded(client.world, player.blockX(), player.blockZ())) {
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

    private void drawFrame(
        final MatrixStack matrices,
        final int x0,
        final int y0,
        final int size,
        final boolean circle,
        final Optional<UiResourceTheme.MinimapFrame> selected
    ) {
        if (selected.isEmpty()) {
            if (circle) {
                RenderUtil.drawRing(
                    matrices, x0 + size / 2f, y0 + size / 2f,
                    size / 2f, BORDER_THICKNESS, BORDER_COLOR
                );
            } else {
                drawBorder(matrices, x0, y0, size);
            }
            return;
        }

        final UiResourceTheme.MinimapFrame frame = selected.get();
        final UiTextureRegion texture = frame.texture();
        RenderUtil.bindTexture(client, texture.texture());
        if (frame.layout() == UiResourceTheme.Layout.OVERLAY) {
            RenderUtil.drawTintedQuad(
                matrices, x0, y0, size, size,
                texture.u0(), texture.v0(), texture.u1(), texture.v1(), 0xFFFFFFFF
            );
        } else if (frame.layout() == UiResourceTheme.Layout.XAERO_CIRCLE) {
            RenderUtil.drawTexturedRing(
                matrices, x0 + size / 2f, y0 + size / 2f, size / 2f,
                4f, texture.u0(), texture.v0(), texture.u1(), texture.v1(), 0xFFFFFFFF
            );
        } else {
            XaeroMinimapFrameRenderer.drawSquare(matrices, x0, y0, size);
        }
    }

}
