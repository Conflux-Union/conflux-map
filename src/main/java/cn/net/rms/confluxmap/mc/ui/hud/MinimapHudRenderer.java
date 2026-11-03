package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.compat.MinecraftAccess;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.annotation.Annotation;
import cn.net.rms.confluxmap.core.annotation.AnnotationProjection;
import cn.net.rms.confluxmap.core.annotation.AnnotationService;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.config.MinimapHudFade;
import cn.net.rms.confluxmap.core.config.MinimapHudVisibility;
import cn.net.rms.confluxmap.core.config.MinimapPlacement;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.trail.PlayerTrail;
import cn.net.rms.confluxmap.core.api.CustomMarkerService;
import cn.net.rms.confluxmap.core.portal.PortalService;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.radar.ServerPlayerRadarState;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.ChunkViewport;
import cn.net.rms.confluxmap.core.waypoint.WaypointRenderCatalog;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.render.OffscreenCanvas;
import cn.net.rms.confluxmap.mc.render.GuiShapes;
import cn.net.rms.confluxmap.mc.render.RenderTargetState;
import cn.net.rms.confluxmap.mc.render.TextureBinds;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.ui.AnnotationRenderer;
import cn.net.rms.confluxmap.mc.ui.GuiDraw;
import cn.net.rms.confluxmap.mc.ui.UiResourceTheme;
import cn.net.rms.confluxmap.mc.ui.anim.TransitionClock;
import cn.net.rms.confluxmap.mc.ui.anim.UiMotion;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
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

/**
 * Renders the always-on minimap HUD, showing whichever layer {@link LayerSelector}
 * currently has active for the player's dimension (surface, cave, nether, etc).
 * Supports zoom (0.5/1/2/4 blocks per pixel), player-facing-up rotation
 * (map rotates, arrow stays up) or north-locked mode (arrow rotates),
 * square scissor or circular alpha-mask clipping, a center arrow,
 * upright cardinal letters, and a coordinates/biome info line.
 * Render thread only (it's an {@link HudRenderCallback}).
 *
 * <p>This class is the per-frame orchestrator: it resolves placement, rotation and
 * the frame's blocks-per-pixel scale, runs the circle/square compositing paths, and
 * delegates the actual drawing to {@link MinimapTilePainter} (tiles and the capture
 * viewport), {@link MinimapMarkerPainter} (waypoint/portal/player markers and the
 * trail), {@link MinimapRadarPainter} (entity radar), and {@link MinimapChromePainter}
 * (frame, cardinals, info text). Show/hide animates through {@link MinimapHudFade}:
 * the pass keeps rendering (everything scaled by the fade alpha) until it settles at 0,
 * so the published viewport stays continuous across the transition.
 */
public final class MinimapHudRenderer {
    private static final int BACKGROUND_RGB = 0x101018;
    private static final float[] BLOCKS_PER_PIXEL = {0.5f, 1f, 2f, 4f};

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final AnnotationService annotations;
    private final OffscreenCanvas canvas = new OffscreenCanvas();
    private final UiResourceTheme uiTheme;
    private final BooleanSupplier serverPolicyAllowsMinimap;
    private final TransitionClock fadeClock = new TransitionClock();
    private final MinimapHudFade fade = new MinimapHudFade();

    private final MinimapTilePainter tilePainter;
    private final MinimapMarkerPainter markerPainter;
    private final MinimapRadarPainter radarPainter;
    private final MinimapChromePainter chromePainter;

    public MinimapHudRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final TileService tiles,
        final TileTextureManager textures,
        final EntityRadarScanner radarScanner,
        final EntityIconManager iconManager,
        final ServerPlayerRadarState serverPlayerRadar,
        final PlayerTrail playerTrail,
        final AnnotationService annotations,
        final LayerSelector layerSelector,
        final WaypointRenderCatalog waypointRenderCatalog,
        final PortalService portals,
        final RadarViewRange radarViewRange,
        final UiResourceTheme uiTheme,
        final CustomMarkerService customMarkers,
        final Consumer<ChunkViewport> captureViewportPublisher,
        final BooleanSupplier liveTerrainPaused,
        final BooleanSupplier serverPolicyAllowsMinimap
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.annotations = annotations;
        this.uiTheme = uiTheme;
        this.serverPolicyAllowsMinimap = serverPolicyAllowsMinimap;
        this.tilePainter = new MinimapTilePainter(
            tiles, textures, gameBridge, layerSelector, captureViewportPublisher
        );
        this.markerPainter = new MinimapMarkerPainter(
            client, config, gameBridge, uiTheme, playerTrail,
            waypointRenderCatalog, portals, customMarkers
        );
        this.radarPainter = new MinimapRadarPainter(
            client, config, gameBridge, radarScanner, iconManager,
            serverPlayerRadar, radarViewRange
        );
        this.chromePainter = new MinimapChromePainter(
            client, config, layerSelector, liveTerrainPaused
        );
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
     * Frame orchestration. The per-frame {@link TileTextureManager#beginFrame()}
     * call lives in {@link MinimapTilePainter#beginFrame()} - see its javadoc for
     * why this HUD callback is the single call site.
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
        tilePainter.beginFrame();
        final boolean fullscreenOpen = MinecraftAccess.screen(client) instanceof FullscreenMapScreen;
        final boolean containerOpen = MinecraftAccess.isContainerScreen(MinecraftAccess.screen(client));
        final boolean sessionActive = gameBridge.session().active();
        final boolean targetVisible = MinimapHudVisibility.shouldRender(
            config.minimapEnabled && serverPolicyAllowsMinimap.getAsBoolean(),
            sessionActive,
            fullscreenOpen,
            containerOpen,
            MinecraftAccess.isFullDebugOverlayVisible(client),
            MinecraftAccess.isHudHidden(client)
        );
        // A session switch must never blend one world's map into the next, so it jumps
        // instead of fading. While a fade is in flight the pass below keeps rendering
        // scaled by the alpha - and keeps publishing the real viewport - until the fade
        // settles at 0 and the hard-hide cleanup runs.
        fade.tick(
            targetVisible,
            !config.uiTransitionsEnabled || !sessionActive,
            fadeClock.tick(),
            UiMotion.durationSeconds(config.uiTransitionSpeed)
        );
        if (fade.alpha() <= 0.0f) {
            tilePainter.publishEmptyViewport();
            // FullscreenMapScreen owns radarViewRange while it's open; otherwise the minimap
            // isn't rendering at all, so there's no visible map surface for the radar to scan.
            if (!fullscreenOpen) {
                tilePainter.clearViewport();
                radarPainter.stop();
            }
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.viewpoint(tickDelta);
        if (playerView.isEmpty()) {
            tilePainter.clearViewport();
            radarPainter.stop();
            tilePainter.publishEmptyViewport();
            return;
        }
        final PlayerView player = playerView.get();
        final float fadeAlpha = fade.alpha();

        tilePainter.setViewpoint(player.blockX(), player.blockZ());

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
        final int backgroundColor = Argb.withAlphaPercent(BACKGROUND_RGB, config.minimapBackgroundOpacity);
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

        final float minimapBlocksPerPixel = BLOCKS_PER_PIXEL[config.minimapZoomIndex];
        // While fading out under an open fullscreen map, FullscreenMapScreen owns
        // radarViewRange (same rule as the settled-hidden path), so leave its range alone;
        // the disappearing icons draw from the scanner's last snapshot instead.
        if (!fullscreenOpen) {
            radarPainter.updateViewRange(contentSize, minimapBlocksPerPixel, circle);
        }

        if (circle) {
            // Real geometric clipping: render the square map into an off-screen canvas,
            // then sample it back as a textured disk. Unlike destination-alpha masking
            // this cannot leak outside the circle regardless of framebuffer state.
            // The fade stays out of the canvas draws: the disk tint below already scales
            // the whole composite once.
            final int canvasPx = Math.max(
                64,
                (int) Math.round(contentSize * client.getWindow().getScaleFactor())
            );
            canvas.begin(canvasPx);
            final MatrixStack fbo = new MatrixStack();
            final float unit = canvasPx / (float) contentSize;
            fbo.scale(unit, unit, 1f);
            GuiShapes.fillRect(fbo, 0, 0, contentSize, contentSize, backgroundColor);
            fbo.push();
            fbo.translate(contentSize / 2f, contentSize / 2f, 0);
            if (rotate) {
                GuiShapes.rotateZ(fbo, mapAngle);
            }
            TextureBinds.beginTexturedQuads();
            tilePainter.drawTiles(fbo, contentSize, circle, mapAngle, player, minimapBlocksPerPixel, 1f);
            fbo.pop();
            fbo.push();
            fbo.translate(contentSize / 2f, contentSize / 2f, 0);
            if (rotate) {
                GuiShapes.rotateZ(fbo, mapAngle);
            }
            markerPainter.drawPortalChunkHighlights(fbo, player, contentSize, minimapBlocksPerPixel, 1f);
            fbo.pop();
            markerPainter.drawPlayerTrail(
                fbo, player,
                contentSize / 2f, contentSize / 2f, contentSize, mapAngle, minimapBlocksPerPixel,
                1f
            );
            if (!visibleAnnotations.isEmpty()) {
                AnnotationRenderer.drawGeometry(
                    fbo,
                    visibleAnnotations,
                    annotationProjection(
                        player,
                        contentSize / 2f, contentSize / 2f, contentSize, mapAngle, minimapBlocksPerPixel
                    ),
                    null
                );
            }
            canvas.end(client);

            TextureBinds.beginTexturedQuads();
            canvas.bindTexture();
            GuiShapes.drawTexturedDisk(
                matrices, centerX, centerY, contentSize / 2f, Argb.scaleAlpha(0xFFFFFFFF, fadeAlpha)
            );
            chromePainter.drawFrame(matrices, x0, y0, size, true, minimapFrame, fadeAlpha);
            AnnotationRenderer.drawLabels(
                draw,
                client.textRenderer,
                visibleAnnotations,
                annotationProjection(player, centerX, centerY, contentSize, mapAngle, minimapBlocksPerPixel),
                viewport.x(),
                viewport.y(),
                contentSize,
                contentSize,
                AnnotationRenderer.ClipShape.CIRCLE,
                fadeAlpha
            );
        } else {
            GuiShapes.fillRect(
                matrices, viewport.x(), viewport.y(), contentSize, contentSize,
                Argb.scaleAlpha(backgroundColor, fadeAlpha)
            );
            RenderTargetState.enableScissor(
                client, viewport.x(), viewport.y(), contentSize, contentSize
            );
            TextureBinds.beginTexturedQuads();
            matrices.push();
            matrices.translate(centerX, centerY, 0);
            if (rotate) {
                GuiShapes.rotateZ(matrices, mapAngle);
            }
            tilePainter.drawTiles(matrices, contentSize, circle, mapAngle, player, minimapBlocksPerPixel, fadeAlpha);
            matrices.pop();
            matrices.push();
            matrices.translate(centerX, centerY, 0);
            if (rotate) {
                GuiShapes.rotateZ(matrices, mapAngle);
            }
            markerPainter.drawPortalChunkHighlights(matrices, player, contentSize, minimapBlocksPerPixel, fadeAlpha);
            matrices.pop();
            markerPainter.drawPlayerTrail(matrices, player, centerX, centerY, contentSize, mapAngle, minimapBlocksPerPixel, fadeAlpha);
            if (!visibleAnnotations.isEmpty()) {
                final AnnotationProjection annotationProjection = annotationProjection(
                    player, centerX, centerY, contentSize, mapAngle, minimapBlocksPerPixel
                );
                AnnotationRenderer.drawGeometry(matrices, visibleAnnotations, annotationProjection, null, fadeAlpha);
                AnnotationRenderer.drawLabels(
                    draw,
                    client.textRenderer,
                    visibleAnnotations,
                    annotationProjection,
                    viewport.x(),
                    viewport.y(),
                    contentSize,
                    contentSize,
                    AnnotationRenderer.ClipShape.RECTANGLE,
                    fadeAlpha
                );
            }
            RenderTargetState.disableScissor();
            chromePainter.drawFrame(matrices, x0, y0, size, false, minimapFrame, fadeAlpha);
        }

        radarPainter.drawRadar(draw, centerX, centerY, contentSize, mapAngle, player, tickDelta, minimapBlocksPerPixel, fadeAlpha);
        chromePainter.drawCardinals(draw, centerX, centerY, contentSize, mapAngle, fadeAlpha);
        markerPainter.drawPortalMarkers(draw, centerX, centerY, contentSize, mapAngle, player, minimapBlocksPerPixel, fadeAlpha);
        markerPainter.drawWaypointMarkers(draw, centerX, centerY, contentSize, mapAngle, player, minimapBlocksPerPixel, fadeAlpha);
        markerPainter.drawCustomMarkers(draw, centerX, centerY, contentSize, mapAngle, player, minimapBlocksPerPixel, fadeAlpha);
        markerPainter.drawCameraMarker(matrices, player, centerX, centerY, rotate, fadeAlpha);
        markerPainter.drawLocalPlayerMarker(
            matrices, player, centerX, centerY, contentSize, rotate, tickDelta, minimapBlocksPerPixel, fadeAlpha
        );
        chromePainter.drawInfoText(draw, player, x0, y0, size, fadeAlpha);
    }

    private AnnotationProjection annotationProjection(
        final PlayerView player,
        final float centerX,
        final float centerY,
        final int size,
        final float mapAngle,
        final float blocksPerPixel
    ) {
        return new AnnotationProjection(
            player.x(),
            player.z(),
            centerX,
            centerY,
            blocksPerPixel,
            mapAngle,
            size,
            size
        );
    }
}
