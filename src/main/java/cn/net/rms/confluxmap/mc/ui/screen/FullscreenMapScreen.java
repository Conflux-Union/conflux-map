package cn.net.rms.confluxmap.mc.ui.screen;

import cn.net.rms.confluxmap.ConfluxMapClient;
import cn.net.rms.confluxmap.ConfluxMapMod;
import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.predict.PredictedTileKeys;
import cn.net.rms.confluxmap.core.predict.PredictionState;
import cn.net.rms.confluxmap.core.predict.PredictionTileService;
import cn.net.rms.confluxmap.core.predict.StructureIndex;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.radar.RadarViewRange;
import cn.net.rms.confluxmap.core.task.SessionGuard;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.radar.RadarMarkerRenderer;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.StructureMarkerRenderer;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.Util;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;
import net.minecraft.util.registry.Registry;

/**
 * Fullscreen, panning/zooming explorable map. Opened and closed by the
 * {@code open_map} keybind (M by default); always north-locked (no rotation).
 * View state is continuous: {@link #centerX}/{@link #centerZ} is the world
 * point at screen center, {@link #scale} is blocks-per-screen-pixel, clamped
 * to {@link #MIN_SCALE}-{@link #MAX_SCALE}. The displayed LOD is derived from
 * scale ({@link #currentLod()}) so zooming out smoothly walks up the tile
 * pyramid {@link TileService} composes in core/.
 *
 * <p>Does not call {@link TileTextureManager#beginFrame()} itself - see the
 * javadoc on {@code MinimapHudRenderer.render} for why that would double it up.
 */
public final class FullscreenMapScreen extends Screen {
    private static final double MIN_SCALE = 0.25;
    private static final double MAX_SCALE = 16.0;
    private static final double DEFAULT_SCALE = 2.0;
    private static final double ZOOM_STEP = 1.26;

    private static final int MARGIN = 6;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int BACKGROUND_COLOR = 0xFF101018;
    private static final int GRID_COLOR = 0x22FFFFFF;
    private static final int ARROW_OUTLINE = 0xFF101010;
    private static final int ARROW_FILL = 0xFFFFE066;
    private static final double MIN_GRID_SPACING_PX = 8.0;
    /** Xaero-style faint dark lattice on chunk borders, understated over both light and dark terrain. */
    private static final int CHUNK_GRID_COLOR = 0x40000000;
    private static final int CHUNK_HIGHLIGHT_FILL = 0x22FFFFFF;
    private static final int CHUNK_HIGHLIGHT_BORDER = 0x66FFFFFF;
    /** Below this on-screen chunk width, skip the chunk grid/highlight entirely to avoid moire noise when zoomed out. */
    private static final double MIN_CHUNK_GRID_SPACING_PX = 6.0;
    /** Half of the ~9px-across VoxelMap-style marker (deliverable C) - slightly larger than the minimap's ~7px. */
    private static final float MARKER_HALF_SIZE = 4.5f;
    /** Blocks-per-pixel threshold below which every marker's name shows continuously, not just on hover (deliverable C). */
    private static final double NAME_LABEL_MAX_SCALE = 2.0;
    private static final double HOVER_RADIUS_PX = 6.0;
    private static final double DEFAULT_CREATE_Y = 64.0;
    /** Cursor travel between left-press and left-release below which a hovered marker click edits it, not pans (see {@link #mouseReleased}). */
    private static final double CLICK_DRAG_TOLERANCE_PX = 4.0;
    /** Radar markers are ~12px across including their contour (see RadarMarkerRenderer); cull with that margin so one straddling the edge doesn't pop. */
    private static final float RADAR_CULL_MARGIN = 8f;

    private final KeyBinding openMapKey;
    private final GameBridge gameBridge;
    private final TileService tiles;
    private final TileTextureManager textures;
    private final PredictionState predictionState;
    private final PredictionTileService predictionTiles;
    private final FullscreenMapViewState viewState;
    private final LayerSelector layerSelector;
    private final WaypointService waypointService;
    private final ConfluxConfig config;
    private final EntityRadarScanner radarScanner;
    private final EntityIconManager radarIconManager;
    private final RadarViewRange radarViewRange;
    private final StructureIndex structureIndex;

    /** World point currently at screen center, and blocks-per-pixel; all mutable, panned/zoomed by input. */
    private double centerX;
    private double centerZ;
    private double scale;

    /** Recomputed every frame by {@link #drawWaypoints} - the marker nearest the cursor within {@link #HOVER_RADIUS_PX}, or none. */
    private Waypoint hoveredWaypoint;

    /** Cursor position at the last left-button press, so {@link #mouseReleased} can tell a click from a pan drag. */
    private double leftPressX;
    private double leftPressY;

    public FullscreenMapScreen(final KeyBinding openMapKey) {
        super(new TranslatableText("confluxmap.screen.map.title"));
        this.openMapKey = openMapKey;
        final ConfluxMapClient app = ConfluxMapClient.get();
        this.gameBridge = app.gameBridge();
        this.tiles = app.tileService();
        this.textures = app.tileTextureManager();
        this.predictionState = app.predictionState();
        this.predictionTiles = app.predictionTileService();
        this.viewState = app.fullscreenMapViewState();
        this.layerSelector = app.layerSelector();
        this.waypointService = app.waypointService();
        this.config = app.config();
        this.radarScanner = app.radarScanner();
        this.radarIconManager = app.entityIconManager();
        this.radarViewRange = app.radarViewRange();
        this.structureIndex = new StructureIndex(
            net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve(ConfluxMapMod.ID).resolve("cache"),
            gameBridge.session().dimension().fileName(),
            (type, regionX, regionZ) -> {
                if (!predictionState.seedKnown() || !cn.net.rms.confluxmap.nativepredict.NativeLib.available()) {
                    return new long[0];
                }
                final int nativeDim = cn.net.rms.confluxmap.core.predict.PredictionDimensions.nativeDim(gameBridge.session().dimension());
                final cn.net.rms.confluxmap.nativepredict.CubiomesContext context =
                    cn.net.rms.confluxmap.nativepredict.CubiomesContexts.get(predictionState.mcVersion(), predictionState.seed(), nativeDim, 0);
                if (context == null) {
                    return new long[0];
                }
                final long[] positions = new long[64];
                final int count = context.structures(type.nativeId(), regionX, regionZ, regionX, regionZ, positions);
                return java.util.Arrays.copyOf(positions, Math.max(0, Math.min(count, positions.length)));
            }
        );

        final DimensionId dimension = gameBridge.session().dimension();
        final FullscreenMapViewState.View remembered = viewState.get(dimension);
        if (remembered != null) {
            centerX = remembered.centerX();
            centerZ = remembered.centerZ();
            scale = remembered.scale();
        } else {
            final Optional<PlayerView> player = gameBridge.player();
            centerX = player.isPresent() ? player.get().x() : 0.0;
            centerZ = player.isPresent() ? player.get().z() : 0.0;
            scale = DEFAULT_SCALE;
        }
    }

    /** Keep the world (and this session's capture pipeline) running while the map is open. */
    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** Funnel point for every close path (ESC via the default {@code keyPressed}, or M below) so the view is always remembered. */
    @Override
    public void onClose() {
        viewState.put(gameBridge.session().dimension(), new FullscreenMapViewState.View(centerX, centerZ, scale));
        super.onClose();
    }

    @Override
    public boolean keyPressed(final int keyCode, final int scanCode, final int modifiers) {
        if (openMapKey.matchesKey(keyCode, scanCode)) {
            onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /**
     * Right-click always creates a new waypoint prefilled with the clicked block
     * position. Left-click on a hovered marker instead opens that waypoint's edit
     * screen - see {@link #mouseReleased}, which fires once the click (as opposed
     * to a pan drag) completes.
     */
    @Override
    public boolean mouseClicked(final double mouseX, final double mouseY, final int button) {
        if (button == 1) {
            final double worldX = Math.floor(centerX + (mouseX - width / 2.0) * scale);
            final double worldZ = Math.floor(centerZ + (mouseY - height / 2.0) * scale);
            final double worldY = gameBridge.player().map(p -> (double) p.blockY()).orElse(DEFAULT_CREATE_Y);
            MinecraftClient.getInstance().setScreen(
                WaypointEditScreen.forCreate(this, gameBridge.session().dimension(), worldX, worldY, worldZ)
            );
            return true;
        }
        if (button == 0) {
            leftPressX = mouseX;
            leftPressY = mouseY;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    /**
     * Completes the left-click-a-marker-to-edit gesture: if the cursor is still on
     * {@link #hoveredWaypoint} and travelled less than {@link #CLICK_DRAG_TOLERANCE_PX}
     * since {@link #mouseClicked}'s press, this was a click rather than a
     * {@link #mouseDragged} pan, so open the same edit flow {@link WaypointListScreen}
     * uses, returning to this screen on save/cancel.
     */
    @Override
    public boolean mouseReleased(final double mouseX, final double mouseY, final int button) {
        if (button == 0 && hoveredWaypoint != null
            && Math.hypot(mouseX - leftPressX, mouseY - leftPressY) < CLICK_DRAG_TOLERANCE_PX) {
            MinecraftClient.getInstance().setScreen(WaypointEditScreen.forEdit(this, hoveredWaypoint));
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(final double mouseX, final double mouseY, final int button, final double deltaX, final double deltaY) {
        if (button == 0) {
            // Opposite the drag direction, 1:1 in world-space at the current scale (§4 pan mechanics).
            centerX -= deltaX * scale;
            centerZ -= deltaY * scale;
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(final double mouseX, final double mouseY, final double amount) {
        if (amount == 0) {
            return false;
        }
        final double oldScale = scale;
        final double newScale = MathHelper.clamp(oldScale * (amount > 0 ? 1.0 / ZOOM_STEP : ZOOM_STEP), MIN_SCALE, MAX_SCALE);
        if (newScale != oldScale) {
            // Cursor-anchored: keep the world point under the cursor fixed on screen.
            final double cursorWorldX = centerX + (mouseX - width / 2.0) * oldScale;
            final double cursorWorldZ = centerZ + (mouseY - height / 2.0) * oldScale;
            centerX = cursorWorldX - (mouseX - width / 2.0) * newScale;
            centerZ = cursorWorldZ - (mouseY - height / 2.0) * newScale;
            scale = newScale;
        }
        return true;
    }

    @Override
    public void render(final MatrixStack matrices, final int mouseX, final int mouseY, final float tickDelta) {
        tiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));
        predictionTiles.setViewpoint((int) Math.floor(centerX), (int) Math.floor(centerZ));
        // This screen owns radarViewRange while it's open (MinimapHudRenderer stops writing it -
        // see its render() javadoc); the viewport half-diagonal is what's actually visible here.
        radarViewRange.set(Math.hypot(width, height) / 2.0 * scale);

        RenderUtil.fillRect(matrices, 0, 0, width, height, BACKGROUND_COLOR);
        drawGrid(matrices);

        RenderUtil.beginTexturedQuads();
        drawTiles(matrices);

        drawChunkGrid(matrices, mouseX, mouseY);
        drawStructures(matrices, mouseX, mouseY);
        drawRadar(matrices, tickDelta);

        drawWaypoints(matrices, mouseX, mouseY);
        drawPlayerMarker(matrices, tickDelta);
        drawDimensionLabel(matrices);
        drawLayerLabel(matrices);
        drawPredictionLabel(matrices);
        drawScaleLabel(matrices);
        drawCursorCoords(matrices, mouseX, mouseY);

        super.render(matrices, mouseX, mouseY, tickDelta);
    }

    private int currentLod() {
        return MathHelper.clamp((int) Math.floor(Math.log(scale) / Math.log(2)), 0, TileMath.MAX_LOD);
    }

    /**
     * Draws the real tile grid, and - when a seed-predicted underlay is available for the
     * current dimension+layer (only {@link MapLayer.Type#SURFACE} in the Overworld and {@link
     * MapLayer.Type#END_SURFACE} in the End; never a cave/nether layer, which cubiomes can't
     * predict at all) - the matching predicted tile drawn first underneath each real one. Real
     * tiles already render {@code UNKNOWN}/unexplored pixels as fully transparent (see {@code
     * TileService#composeRegion}), and blending is already enabled for this whole pass (see
     * {@link #render}'s {@code RenderUtil.beginTexturedQuads()} call), so the predicted layer
     * simply shows through wherever the real tile has nothing yet.
     */
    private void drawTiles(final MatrixStack matrices) {
        final int lod = currentLod();
        final double pxPerBlock = 1.0 / scale;
        final double blocksPerTile = TileMath.blocksPerTile(lod);
        final double halfWidthBlocks = width / 2.0 * scale;
        final double halfHeightBlocks = height / 2.0 * scale;

        final int firstTileX = TileMath.blockToTile((int) Math.floor(centerX - halfWidthBlocks), lod);
        final int lastTileX = TileMath.blockToTile((int) Math.ceil(centerX + halfWidthBlocks), lod);
        final int firstTileZ = TileMath.blockToTile((int) Math.floor(centerZ - halfHeightBlocks), lod);
        final int lastTileZ = TileMath.blockToTile((int) Math.ceil(centerZ + halfHeightBlocks), lod);

        final SessionGuard.Session session = gameBridge.session();
        final MapLayer layer = layerSelector.current().layer();
        final String layerId = layer.cacheId();
        final boolean predictionEligibleLayer = layer.type() == MapLayer.Type.SURFACE || layer.type() == MapLayer.Type.END_SURFACE;
        final boolean predictionActive = config.predictionEnabled
            && predictionEligibleLayer
            && predictionState.predictable(session.dimension());
        if (predictionActive) {
            predictionTiles.setViewport(session.dimension(), lod, firstTileX, lastTileX, firstTileZ, lastTileZ);
            ConfluxMapClient.get().mapSyncClient().reportViewport(
                session.dimension(), lod, firstTileX, lastTileX, firstTileZ, lastTileZ
            );
        }

        for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
            for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
                final TileKey key = new TileKey(session.world(), session.dimension(), layerId, lod, tileX, tileZ);
                final float screenX = (float) (width / 2.0 + (key.originBlockX() - centerX) * pxPerBlock);
                final float screenY = (float) (height / 2.0 + (key.originBlockZ() - centerZ) * pxPerBlock);
                final float quadSize = (float) (blocksPerTile * pxPerBlock);

                if (predictionActive && textures.bind(PredictedTileKeys.toPredicted(key))) {
                    RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
                }
                if (textures.bind(key)) {
                    RenderUtil.drawQuad(matrices, screenX, screenY, quadSize, quadSize, 0f, 0f, 1f, 1f);
                }
            }
        }
    }

    /**
     * Radar entries above the map tiles but below waypoint markers (see {@link #render}), reusing
     * {@link RadarMarkerRenderer} - the exact same icon/dot drawing {@code MinimapHudRenderer} uses,
     * so both surfaces look identical. Always north-up (this screen never rotates), and positions
     * use the same live-interpolated-position-over-scan-snapshot preference as the minimap (see
     * {@code MinimapHudRenderer#drawRadar}'s javadoc). Category toggles and {@code radarEnabled}
     * already apply upstream in the scanner, so no extra filtering happens here beyond viewport culling.
     */
    private void drawRadar(final MatrixStack matrices, final float tickDelta) {
        if (client.world == null) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final double pxPerBlock = 1.0 / scale;

        for (final RadarEntry entry : radarScanner.snapshot()) {
            double ex = entry.x();
            double ez = entry.z();
            int yDelta = entry.yDelta();
            final Entity live = client.world.getEntityById(entry.entityId());
            if (live != null) {
                ex = MathHelper.lerp(tickDelta, live.prevX, live.getX());
                ez = MathHelper.lerp(tickDelta, live.prevZ, live.getZ());
                yDelta = (int) Math.round(live.getY() - player.y());
            }

            final float screenX = (float) (width / 2.0 + (ex - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (ez - centerZ) * pxPerBlock);
            if (screenX < -RADAR_CULL_MARGIN || screenX > width + RADAR_CULL_MARGIN
                || screenY < -RADAR_CULL_MARGIN || screenY > height + RADAR_CULL_MARGIN) {
                continue;
            }
            RadarMarkerRenderer.draw(matrices, client, config, radarIconManager, entry, screenX, screenY, yDelta, live);
        }
    }

    private void drawStructures(final MatrixStack matrices, final int mouseX, final int mouseY) {
        if (!config.predictionShowStructures || currentLod() > 2 || !predictionState.seedKnown()) {
            return;
        }
        final int lod = currentLod();
        final int minX = (int) Math.floor(centerX - width / 2.0 * scale);
        final int maxX = (int) Math.ceil(centerX + width / 2.0 * scale);
        final int minZ = (int) Math.floor(centerZ - height / 2.0 * scale);
        final int maxZ = (int) Math.ceil(centerZ + height / 2.0 * scale);
        final double pxPerBlock = 1.0 / scale;
        final List<StructureIndex.Marker> markers = structureIndex.query(minX, maxX, minZ, maxZ);
        markers.sort(java.util.Comparator.comparingLong(marker -> {
            final long dx = marker.blockX() - (long) centerX;
            final long dz = marker.blockZ() - (long) centerZ;
            return dx * dx + dz * dz;
        }));
        final int limit = Math.min(64, markers.size());
        for (int i = 0; i < limit; i++) {
            final StructureIndex.Marker marker = markers.get(i);
            final float screenX = (float) (width / 2.0 + (marker.blockX() - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (marker.blockZ() - centerZ) * pxPerBlock);
            if (screenX < -8 || screenX > width + 8 || screenY < -8 || screenY > height + 8) {
                continue;
            }
            final boolean hovered = Math.hypot(mouseX - screenX, mouseY - screenY) <= 8;
            StructureMarkerRenderer.draw(matrices, textRenderer, marker, screenX, screenY, hovered);
        }
        structureIndex.save();
    }

    /** Faint lines on LOD-0 tile boundaries (256-block spacing), skipped once they'd be denser than {@link #MIN_GRID_SPACING_PX}. */
    private void drawGrid(final MatrixStack matrices) {
        final double pxPerBlock = 1.0 / scale;
        final double spacingPx = TileMath.TILE_SIZE * pxPerBlock;
        if (spacingPx < MIN_GRID_SPACING_PX) {
            return;
        }
        final int firstLineX = TileMath.blockToTile((int) Math.floor(centerX - width / 2.0 * scale));
        final int lastLineX = TileMath.blockToTile((int) Math.ceil(centerX + width / 2.0 * scale));
        for (int tx = firstLineX; tx <= lastLineX + 1; tx++) {
            final float screenX = (float) (width / 2.0 + (tx * (double) TileMath.TILE_SIZE - centerX) * pxPerBlock);
            RenderUtil.fillRect(matrices, screenX, 0f, 1f, height, GRID_COLOR);
        }
        final int firstLineZ = TileMath.blockToTile((int) Math.floor(centerZ - height / 2.0 * scale));
        final int lastLineZ = TileMath.blockToTile((int) Math.ceil(centerZ + height / 2.0 * scale));
        for (int tz = firstLineZ; tz <= lastLineZ + 1; tz++) {
            final float screenY = (float) (height / 2.0 + (tz * (double) TileMath.TILE_SIZE - centerZ) * pxPerBlock);
            RenderUtil.fillRect(matrices, 0f, screenY, width, 1f, GRID_COLOR);
        }
    }

    /**
     * Faint chunk-border lattice (Xaero-World-Map-style: thin, low-alpha, dark - readable over
     * both light and dark terrain) plus a highlight on the chunk under the cursor. Drawn after
     * the map tiles but before waypoint markers. Guarded by {@link ConfluxConfig#fullmapChunkGrid}
     * and skipped once a chunk would render under {@link #MIN_CHUNK_GRID_SPACING_PX} wide, to
     * avoid moire noise when zoomed far out.
     */
    private void drawChunkGrid(final MatrixStack matrices, final int mouseX, final int mouseY) {
        if (!config.fullmapChunkGrid) {
            return;
        }
        final double pxPerBlock = 1.0 / scale;
        final double chunkSpacingPx = 16.0 * pxPerBlock;
        if (chunkSpacingPx < MIN_CHUNK_GRID_SPACING_PX) {
            return;
        }

        // Floor/ceil-then-blockToChunk (arithmetic shift) keeps lines exact on chunk borders
        // for negative world coordinates too.
        final int firstChunkX = TileMath.blockToChunk((int) Math.floor(centerX - width / 2.0 * scale));
        final int lastChunkX = TileMath.blockToChunk((int) Math.ceil(centerX + width / 2.0 * scale));
        for (int cx = firstChunkX; cx <= lastChunkX + 1; cx++) {
            final float screenX = (float) (width / 2.0 + (cx * 16.0 - centerX) * pxPerBlock);
            RenderUtil.fillRect(matrices, screenX, 0f, 1f, height, CHUNK_GRID_COLOR);
        }
        final int firstChunkZ = TileMath.blockToChunk((int) Math.floor(centerZ - height / 2.0 * scale));
        final int lastChunkZ = TileMath.blockToChunk((int) Math.ceil(centerZ + height / 2.0 * scale));
        for (int cz = firstChunkZ; cz <= lastChunkZ + 1; cz++) {
            final float screenY = (float) (height / 2.0 + (cz * 16.0 - centerZ) * pxPerBlock);
            RenderUtil.fillRect(matrices, 0f, screenY, width, 1f, CHUNK_GRID_COLOR);
        }

        drawHoveredChunkHighlight(matrices, mouseX, mouseY, pxPerBlock);
    }

    /** Fills the 16x16-block chunk under the cursor and outlines it, layered on top of the grid lines drawn just above. */
    private void drawHoveredChunkHighlight(final MatrixStack matrices, final int mouseX, final int mouseY, final double pxPerBlock) {
        final double hoverWorldX = centerX + (mouseX - width / 2.0) * scale;
        final double hoverWorldZ = centerZ + (mouseY - height / 2.0) * scale;
        final int chunkX = TileMath.blockToChunk((int) Math.floor(hoverWorldX));
        final int chunkZ = TileMath.blockToChunk((int) Math.floor(hoverWorldZ));
        final float screenX = (float) (width / 2.0 + (chunkX * 16.0 - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (chunkZ * 16.0 - centerZ) * pxPerBlock);
        final float sizePx = (float) (16.0 * pxPerBlock);

        RenderUtil.fillRect(matrices, screenX, screenY, sizePx, sizePx, CHUNK_HIGHLIGHT_FILL);
        RenderUtil.fillRect(matrices, screenX, screenY, sizePx, 1f, CHUNK_HIGHLIGHT_BORDER);
        RenderUtil.fillRect(matrices, screenX, screenY + sizePx - 1f, sizePx, 1f, CHUNK_HIGHLIGHT_BORDER);
        RenderUtil.fillRect(matrices, screenX, screenY, 1f, sizePx, CHUNK_HIGHLIGHT_BORDER);
        RenderUtil.fillRect(matrices, screenX + sizePx - 1f, screenY, 1f, sizePx, CHUNK_HIGHLIGHT_BORDER);
    }

    /** Always north-locked, so only the arrow itself rotates with the player's facing (mirrors {@code MinimapHudRenderer}'s north-locked mode). */
    private void drawPlayerMarker(final MatrixStack matrices, final float tickDelta) {
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();
        final double pxPerBlock = 1.0 / scale;
        final float screenX = (float) (width / 2.0 + (player.x() - centerX) * pxPerBlock);
        final float screenY = (float) (height / 2.0 + (player.z() - centerZ) * pxPerBlock);
        matrices.push();
        matrices.translate(screenX, screenY, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(player.yawDegrees() + 180f));
        RenderUtil.fillTriangle(matrices, 0f, -7f, -5.5f, 6f, 5.5f, 6f, ARROW_OUTLINE);
        RenderUtil.fillTriangle(matrices, 0f, -5.5f, -4f, 4.5f, 4f, 4.5f, ARROW_FILL);
        matrices.pop();
    }

    /**
     * Markers for waypoints visible in the viewed dimension (see
     * {@link DimensionScale#isVisibleFrom}), coordinates converted for display. Fixed
     * on-screen size regardless of zoom - only their world position, and thus screen
     * position, changes with pan/zoom. Names are shown continuously once zoomed in past
     * {@link #NAME_LABEL_MAX_SCALE} blocks-per-pixel, or always for the single marker
     * nearest the cursor within {@link #HOVER_RADIUS_PX} (deliverable C), which also gets
     * brightened (see {@link WaypointMarkerRenderer}) and has its coordinates shown in the
     * footer by {@link #drawCursorCoords}.
     */
    private void drawWaypoints(final MatrixStack matrices, final int mouseX, final int mouseY) {
        final DimensionId currentDimension = gameBridge.session().dimension();
        final double pxPerBlock = 1.0 / scale;
        final List<ScreenMarker> markers = new ArrayList<>();
        for (final Waypoint waypoint : waypointService.list()) {
            if (!waypoint.visible || !DimensionScale.isVisibleFrom(waypoint.dimensionId, currentDimension)) {
                continue;
            }
            final double worldX = DimensionScale.convertHorizontal(waypoint.x, waypoint.dimensionId, currentDimension);
            final double worldZ = DimensionScale.convertHorizontal(waypoint.z, waypoint.dimensionId, currentDimension);
            final float screenX = (float) (width / 2.0 + (worldX - centerX) * pxPerBlock);
            final float screenY = (float) (height / 2.0 + (worldZ - centerZ) * pxPerBlock);
            if (screenX < -MARKER_HALF_SIZE || screenX > width + MARKER_HALF_SIZE
                || screenY < -MARKER_HALF_SIZE || screenY > height + MARKER_HALF_SIZE) {
                continue;
            }
            markers.add(new ScreenMarker(waypoint, screenX, screenY));
        }

        Waypoint hovered = null;
        double bestHoverDist = HOVER_RADIUS_PX;
        for (final ScreenMarker marker : markers) {
            final double hoverDist = Math.hypot(mouseX - marker.screenX(), mouseY - marker.screenY());
            if (hoverDist <= bestHoverDist) {
                bestHoverDist = hoverDist;
                hovered = marker.waypoint();
            }
        }
        hoveredWaypoint = hovered;

        for (final ScreenMarker marker : markers) {
            final Waypoint waypoint = marker.waypoint();
            final boolean isHovered = waypoint == hoveredWaypoint;
            WaypointMarkerRenderer.draw(
                matrices, client.textRenderer, waypoint, marker.screenX(), marker.screenY(), MARKER_HALF_SIZE, 1f, isHovered
            );
            if (scale <= NAME_LABEL_MAX_SCALE || isHovered) {
                textRenderer.drawWithShadow(
                    matrices, waypoint.name, marker.screenX() + MARKER_HALF_SIZE + 2, marker.screenY() - 4, TEXT_COLOR
                );
            }
        }
    }

    /** One waypoint's already-converted, already-viewport-culled screen position for this frame's {@link #drawWaypoints} pass. */
    private record ScreenMarker(Waypoint waypoint, float screenX, float screenY) {
    }

    private void drawDimensionLabel(final MatrixStack matrices) {
        final String text = dimensionDisplayName(gameBridge.session().dimension());
        textRenderer.drawWithShadow(matrices, text, MARGIN, MARGIN, TEXT_COLOR);
    }

    /** Deliverable D: the fullscreen map shows the active layer for the current dimension. */
    private void drawLayerLabel(final MatrixStack matrices) {
        final String text = new TranslatableText(
            "confluxmap.layer." + layerSelector.current().layer().type().id()
        ).getString();
        textRenderer.drawWithShadow(matrices, text, MARGIN, MARGIN + textRenderer.fontHeight + 2, TEXT_COLOR);
    }

    private void drawPredictionLabel(final MatrixStack matrices) {
        if (!config.predictionEnabled || !predictionState.seedKnown()) {
            return;
        }
        final String mode = new TranslatableText(
            "confluxmap.config.prediction.mode." + config.predictionViewMode.name().toLowerCase(java.util.Locale.ROOT)
        ).getString();
        final String text = new TranslatableText("confluxmap.config.prediction.view_mode", mode).getString();
        textRenderer.drawWithShadow(
            matrices, text, MARGIN, MARGIN + textRenderer.fontHeight * 2 + 4, TEXT_COLOR
        );
    }

    private void drawScaleLabel(final MatrixStack matrices) {
        final String text = new TranslatableText("confluxmap.map.scale", String.format("%.2f", scale)).getString();
        final int textWidth = textRenderer.getWidth(text);
        textRenderer.drawWithShadow(matrices, text, width - MARGIN - textWidth, MARGIN, TEXT_COLOR);
    }

    /**
     * Bottom-center footer: the raw cursor position, or - while hovering a marker
     * (deliverable C) - that waypoint's own X/Y/Z instead (converted for the viewed
     * dimension, per {@link DimensionScale}; Y is shown as stored, per waypoint-ux.md S3's
     * "Y is never scaled" rule). When not hovering a marker, the cursor's biome name
     * (see {@link #cursorBiomeName}) is appended if it can be resolved.
     */
    private void drawCursorCoords(final MatrixStack matrices, final int mouseX, final int mouseY) {
        final String text;
        if (hoveredWaypoint != null) {
            final DimensionId currentDimension = gameBridge.session().dimension();
            final double worldX = DimensionScale.convertHorizontal(hoveredWaypoint.x, hoveredWaypoint.dimensionId, currentDimension);
            final double worldZ = DimensionScale.convertHorizontal(hoveredWaypoint.z, hoveredWaypoint.dimensionId, currentDimension);
            text = (int) Math.floor(worldX) + ", " + (int) Math.floor(hoveredWaypoint.y) + ", " + (int) Math.floor(worldZ);
        } else {
            final int blockX = (int) Math.floor(centerX + (mouseX - width / 2.0) * scale);
            final int blockZ = (int) Math.floor(centerZ + (mouseY - height / 2.0) * scale);
            final String biomeName = cursorBiomeName(blockX, blockZ);
            text = blockX + ", " + blockZ + (biomeName == null ? "" : " · " + biomeName);
        }
        final int textWidth = textRenderer.getWidth(text);
        textRenderer.drawWithShadow(matrices, text, width / 2f - textWidth / 2f, height - MARGIN - 10, TEXT_COLOR);
    }

    /**
     * Best-effort biome name at the given column, for the footer readout. Uses the
     * local player's block Y (clamped to the world's vertical bounds) rather than a
     * true 3-D sample - close enough for a map readout, per the implementation
     * brief. Returns null - footer falls back to coordinates only - if there's no
     * world, the chunk isn't loaded, or the biome's identifier can't be resolved.
     */
    private String cursorBiomeName(final int blockX, final int blockZ) {
        final ClientWorld world = client.world;
        if (world == null) {
            return null;
        }
        final int playerY = gameBridge.player().map(p -> p.blockY()).orElse(world.getBottomY());
        final BlockPos pos = new BlockPos(blockX, MathHelper.clamp(playerY, world.getBottomY(), world.getTopY() - 1), blockZ);
        if (!world.isChunkLoaded(pos)) {
            return null;
        }
        final Identifier biomeId = world.getRegistryManager().get(Registry.BIOME_KEY).getId(world.getBiome(pos));
        return biomeId == null ? null : new TranslatableText(Util.createTranslationKey("biome", biomeId)).getString();
    }

    private static String dimensionDisplayName(final DimensionId dimension) {
        if (dimension.equals(DimensionId.OVERWORLD)) {
            return new TranslatableText("confluxmap.dimension.overworld").getString();
        }
        if (dimension.equals(DimensionId.NETHER)) {
            return new TranslatableText("confluxmap.dimension.the_nether").getString();
        }
        if (dimension.equals(DimensionId.END)) {
            return new TranslatableText("confluxmap.dimension.the_end").getString();
        }
        return dimension.path();
    }
}
