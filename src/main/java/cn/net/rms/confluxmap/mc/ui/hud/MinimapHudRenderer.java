package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.DimensionId;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.radar.RadarCategory;
import cn.net.rms.confluxmap.core.radar.RadarEntry;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.Argb;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.core.waypoint.DimensionScale;
import cn.net.rms.confluxmap.core.waypoint.Waypoint;
import cn.net.rms.confluxmap.core.waypoint.WaypointService;
import cn.net.rms.confluxmap.mc.radar.EntityIconManager;
import cn.net.rms.confluxmap.mc.radar.EntityRadarScanner;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import cn.net.rms.confluxmap.mc.ui.WaypointMarkerRenderer;
import cn.net.rms.confluxmap.mc.ui.screen.FullscreenMapScreen;
import cn.net.rms.confluxmap.mc.world.LayerSelector;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableText;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3f;
import net.minecraft.util.registry.Registry;
import net.minecraft.world.biome.Biome;

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
    private static final int MARGIN = 4;
    private static final int BORDER_THICKNESS = 1;
    private static final int BORDER_COLOR = 0xB0FFFFFF;
    private static final int BACKGROUND_COLOR = 0x80101018;
    private static final int TEXT_COLOR = 0xFFFFFFFF;
    private static final int ARROW_OUTLINE = 0xFF101010;
    private static final int ARROW_FILL = 0xFFFFFFFF;
    private static final float[] BLOCKS_PER_PIXEL = {0.5f, 1f, 2f, 4f};
    /** Half of the ~7px-across VoxelMap-style diamond/cross marker (deliverable B). */
    private static final float WAYPOINT_MARKER_HALF_SIZE = 3.5f;

    // Radar marker colors and above/below indication, per docs/reference-specs/radar-icons.md sec 3:
    // entities above the player fade toward transparent; entities at/below dim toward black,
    // floored so they never go fully dark. See #elevationColor for the exact formula.
    private static final int RADAR_PLAYER_COLOR = 0xFFFFFFFF;
    private static final int RADAR_HOSTILE_COLOR = 0xFFFF4040;
    private static final int RADAR_PASSIVE_COLOR = 0xFF50E060;
    private static final int RADAR_OTHER_COLOR = 0xFFA0A0A0;
    private static final int RADAR_VERTICAL_WINDOW = 32;
    private static final float RADAR_DIM_FLOOR = 0.3f;

    // Entity head icons (radar-icons.md sec "approach"): the icon quad is drawn at fixed pixel
    // size, with a colored ring around it indicating the category - independent of the plain
    // dot colors above, which stay unchanged for the config.radarIconsEnabled=false / no-icon
    // fallback path.
    private static final float RADAR_ICON_HALF_SIZE = 5f;
    private static final float RADAR_ICON_SIZE = RADAR_ICON_HALF_SIZE * 2f;
    /** Name labels sit just below the icon's 1px square border. */
    private static final float RADAR_NAME_OFFSET = 7f;
    private static final int RADAR_ICON_CONTOUR = 0xB0000000;
    /** No elevation treatment inside this band - nearby mobs always render fully readable. */
    private static final int RADAR_DEADZONE = 8;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final TileService tiles;
    private final TileTextureManager textures;
    private final EntityRadarScanner radarScanner;
    private final EntityIconManager iconManager;
    private final LayerSelector layerSelector;
    private final WaypointService waypointService;

    public MinimapHudRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final TileService tiles,
        final TileTextureManager textures,
        final EntityRadarScanner radarScanner,
        final EntityIconManager iconManager,
        final LayerSelector layerSelector,
        final WaypointService waypointService
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.tiles = tiles;
        this.textures = textures;
        this.radarScanner = radarScanner;
        this.iconManager = iconManager;
        this.layerSelector = layerSelector;
        this.waypointService = waypointService;
    }

    public void register() {
        HudRenderCallback.EVENT.register(this::render);
    }

    /**
     * {@link net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback} fires once
     * per rendered frame regardless of whether a {@link net.minecraft.client.gui.screen.Screen}
     * is open (the HUD layer draws before the screen layer, it just ends up covered).
     * That makes this the single per-frame call site for {@link TileTextureManager#beginFrame()} -
     * {@link FullscreenMapScreen} relies on it having already run this frame and never
     * calls it itself, so it's never invoked twice in one frame.
     */
    private void render(final MatrixStack matrices, final float tickDelta) {
        textures.beginFrame();
        if (!config.minimapEnabled || !gameBridge.session().active() || client.currentScreen instanceof FullscreenMapScreen) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player(tickDelta);
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();

        tiles.setViewpoint(player.blockX(), player.blockZ());

        final int size = config.minimapSize;
        final int x0 = originX(client.getWindow().getScaledWidth(), size);
        final int y0 = originY(client.getWindow().getScaledHeight(), size);
        final float centerX = x0 + size / 2f;
        final float centerY = y0 + size / 2f;
        final boolean circle = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final boolean rotate = config.minimapRotate;
        final float mapAngle = rotate ? 180f - player.yawDegrees() : 0f;

        if (circle) {
            RenderUtil.stampCircleAlpha(matrices, centerX, centerY, size / 2f);
            // The alpha mask only shapes pixels INSIDE the stamped square; the scissor
            // stops rotated tile quads from spilling across the rest of the screen
            // (framebuffer alpha is ~1 out there, so DST_ALPHA blending would pass).
            RenderUtil.enableScissor(client, x0, y0, size, size);
            RenderUtil.beginMaskedQuads();
        } else {
            RenderUtil.fillRect(matrices, x0, y0, size, size, BACKGROUND_COLOR);
            RenderUtil.enableScissor(client, x0, y0, size, size);
            RenderUtil.beginTexturedQuads();
        }

        matrices.push();
        matrices.translate(centerX, centerY, 0);
        if (rotate) {
            matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(mapAngle));
        }
        drawTiles(matrices, size, rotate, player);
        matrices.pop();

        if (circle) {
            RenderUtil.endMaskedQuads(matrices, x0, y0, size, size);
            RenderUtil.disableScissor();
            RenderUtil.drawRing(matrices, centerX, centerY, size / 2f, BORDER_THICKNESS, BORDER_COLOR);
        } else {
            RenderUtil.disableScissor();
            drawBorder(matrices, x0, y0, size);
        }

        drawRadar(matrices, centerX, centerY, size, mapAngle, player, tickDelta);
        drawCardinals(matrices, centerX, centerY, size, mapAngle);
        drawWaypointMarkers(matrices, centerX, centerY, size, mapAngle, player);
        drawPlayerArrow(matrices, centerX, centerY, rotate ? 0f : player.yawDegrees() + 180f);
        drawInfoText(matrices, player, x0, y0, size);
    }

    /**
     * In-range dots and out-of-range edge indicators for waypoints visible in the
     * current dimension. Reuses {@link #drawCardinal}'s exact rotation trick: the
     * marker's screen *position* is computed with the same manual cos/sin rotation
     * as the cardinal letters (this method runs after the tile-drawing push/pop, so
     * the active matrix here is unrotated - see the render() javadoc), while the
     * in-range marker glyph itself is drawn with no rotation applied (upright). The
     * out-of-range edge arrow is the one deliberate exception - it rotates to point
     * at the waypoint's true on-screen bearing, per waypoint-ux.md S7.
     */
    private void drawWaypointMarkers(
        final MatrixStack matrices,
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
        final float limit = size / 2f - 2f;
        final boolean circleFrame = config.minimapShape == ConfluxConfig.Shape.CIRCLE;
        final DimensionId currentDimension = gameBridge.session().dimension();

        for (final Waypoint waypoint : waypointService.list()) {
            if (!waypoint.visible || !DimensionScale.isVisibleFrom(waypoint.dimensionId, currentDimension)) {
                continue;
            }
            final double worldX = DimensionScale.convertHorizontal(waypoint.x, waypoint.dimensionId, currentDimension);
            final double worldZ = DimensionScale.convertHorizontal(waypoint.z, waypoint.dimensionId, currentDimension);
            final double dx = worldX - player.x();
            final double dz = worldZ - player.z();
            if (config.waypointRenderDistance > 0) {
                final double dy = waypoint.y - player.y();
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
                    matrices, waypoint, centerX + screenOffX, centerY + screenOffY, WAYPOINT_MARKER_HALF_SIZE, 1f, false
                );
            } else if (config.waypointEdgeIndicatorsEnabled) {
                final float k = circleFrame
                    ? limit / (float) Math.hypot(screenOffX, screenOffY)
                    : limit / Math.max(Math.abs(screenOffX), Math.abs(screenOffY));
                final float edgeX = screenOffX * k;
                final float edgeY = screenOffY * k;
                final float angle = (float) Math.toDegrees(Math.atan2(screenOffX, -screenOffY));
                WaypointMarkerRenderer.drawEdgeArrow(matrices, centerX + edgeX, centerY + edgeY, angle, waypoint.colorArgb, 1f);
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
        final String layerId = layerSelector.current().layer().cacheId();

        for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
            for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
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
        final MatrixStack matrices,
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
            drawRadarMarker(matrices, entry, x, y, yDelta, live);
        }
    }

    /**
     * Draws the entity head icon (VoxelMap-style: a small sub-UV crop of the entity's own skin/
     * mob texture, ringed in a category color) when available, falling back to the original
     * shaped dot otherwise - either because icons are disabled, the entity isn't currently loaded
     * (only the scan-time snapshot position survived - see {@link #drawRadar} javadoc), or its
     * species has no entry in {@link EntityIconManager}'s UV table.
     */
    private void drawRadarMarker(
        final MatrixStack matrices, final RadarEntry entry, final float x, final float y, final int yDelta, final Entity live
    ) {
        if (config.radarIconsEnabled && live != null) {
            final EntityIconManager.FaceIcon icon = iconManager.iconFor(live);
            if (icon != null) {
                drawRadarIcon(matrices, icon, x, y, yDelta);
                if (config.radarShowPlayerNames && entry.category() == RadarCategory.PLAYER && entry.name() != null) {
                    drawCenteredLine(matrices, entry.name(), x, y + RADAR_NAME_OFFSET);
                }
                return;
            }
        }
        final int color = elevationColor(baseRadarColor(entry.category()), yDelta);
        switch (entry.category()) {
            case PLAYER:
                RenderUtil.fillRect(matrices, x - 2f, y - 2f, 4f, 4f, color);
                if (config.radarShowPlayerNames && entry.name() != null) {
                    drawCenteredLine(matrices, entry.name(), x, y + 3f);
                }
                break;
            case HOSTILE:
                RenderUtil.fillTriangle(matrices, x, y - 3.5f, x - 3f, y + 2.5f, x + 3f, y + 2.5f, color);
                break;
            case PASSIVE:
                RenderUtil.drawRing(matrices, x, y, 2.5f, 2.5f, color);
                break;
            default:
                RenderUtil.fillRect(matrices, x - 1.5f, y - 1.5f, 3f, 3f, color);
                break;
        }
    }

    /**
     * Icon quad tinted by the same elevation alpha/dim as the dot markers (base color white, so
     * the entity's real texture colors show through untouched), then a category-colored ring
     * drawn just outside it - the ring also gets the elevation treatment, matching how the
     * reference spec applies the same alpha/dim multiply to its headgear overlay layer.
     */
    private void drawRadarIcon(
        final MatrixStack matrices, final EntityIconManager.FaceIcon icon, final float x, final float y, final int yDelta
    ) {
        final int tint = elevationColor(0xFFFFFFFF, yDelta);
        RenderUtil.bindTexture(client, icon.texture());
        RenderUtil.drawTintedQuad(
            matrices, x - RADAR_ICON_HALF_SIZE, y - RADAR_ICON_HALF_SIZE, RADAR_ICON_SIZE, RADAR_ICON_SIZE,
            icon.u0(), icon.v0(), icon.u1(), icon.v1(), tint
        );
        if (icon.hasOverlay()) {
            RenderUtil.bindTexture(client, icon.overlayTexture());
            RenderUtil.drawTintedQuad(
                matrices, x - RADAR_ICON_HALF_SIZE, y - RADAR_ICON_HALF_SIZE, RADAR_ICON_SIZE, RADAR_ICON_SIZE,
                icon.ou0(), icon.ov0(), icon.ou1(), icon.ov1(), tint
            );
        }
        // VoxelMap-style presentation: a clean face icon with only a thin dark contour
        // for contrast against the terrain - no faction frames (user feedback: match
        // VoxelMap; a night full of hostiles turned colored frames into visual noise).
        final int borderColor = elevationColor(RADAR_ICON_CONTOUR, yDelta);
        final float left = x - RADAR_ICON_HALF_SIZE - 1f;
        final float top = y - RADAR_ICON_HALF_SIZE - 1f;
        final float edge = RADAR_ICON_SIZE + 2f;
        RenderUtil.fillRect(matrices, left, top, edge, 1f, borderColor);
        RenderUtil.fillRect(matrices, left, top + edge - 1f, edge, 1f, borderColor);
        RenderUtil.fillRect(matrices, left, top + 1f, 1f, edge - 2f, borderColor);
        RenderUtil.fillRect(matrices, left + edge - 1f, top + 1f, 1f, edge - 2f, borderColor);
    }

    private static int baseRadarColor(final RadarCategory category) {
        switch (category) {
            case PLAYER:
                return RADAR_PLAYER_COLOR;
            case HOSTILE:
                return RADAR_HOSTILE_COLOR;
            case PASSIVE:
                return RADAR_PASSIVE_COLOR;
            default:
                return RADAR_OTHER_COLOR;
        }
    }

    /**
     * Above/below indication (radar-icons.md sec 3), simplified to a fixed 32-block window
     * (no zoom scaling, no doubled window for phantoms) rather than the spec's Full-mode icon
     * treatment: a "closeness" value is 1 right at the player's own height and falls off
     * (linearly, then squared) to 0 at the window edge. An entity above the player fades toward
     * transparent as closeness drops; an entity at or below stays opaque but its color dims
     * toward black as closeness drops, floored at 30% brightness so it never fully vanishes.
     */
    private static int elevationColor(final int base, final int yDelta) {
        final int beyond = Math.max(0, Math.abs(yDelta) - RADAR_DEADZONE);
        final float closeness = 1f - Math.min(beyond / (float) (RADAR_VERTICAL_WINDOW - RADAR_DEADZONE), 1f);
        if (yDelta > 0) {
            final int alpha = Math.round(Math.max(closeness, 0.35f) * 255f);
            return (base & 0x00FFFFFF) | (alpha << 24);
        }
        final float brightness = Math.max(closeness, 0.5f);
        return Argb.scale(base, brightness);
    }

    private void drawPlayerArrow(final MatrixStack matrices, final float centerX, final float centerY, final float angle) {
        matrices.push();
        matrices.translate(centerX, centerY, 0);
        matrices.multiply(Vec3f.POSITIVE_Z.getDegreesQuaternion(angle));
        RenderUtil.fillTriangle(matrices, 0f, -6.5f, -5f, 5.5f, 5f, 5.5f, ARROW_OUTLINE);
        RenderUtil.fillTriangle(matrices, 0f, -5f, -3.5f, 4f, 3.5f, 4f, ARROW_FILL);
        matrices.pop();
    }

    /** Cardinal letters sit on the (possibly rotated) compass ring but are always drawn upright. */
    private void drawCardinals(final MatrixStack matrices, final float centerX, final float centerY, final int size, final float mapAngle) {
        final float radius = size / 2f - 7f;
        final double rad = Math.toRadians(mapAngle);
        final float cos = (float) Math.cos(rad);
        final float sin = (float) Math.sin(rad);
        drawCardinal(matrices, "N", centerX, centerY, 0f, -radius, cos, sin);
        drawCardinal(matrices, "E", centerX, centerY, radius, 0f, cos, sin);
        drawCardinal(matrices, "S", centerX, centerY, 0f, radius, cos, sin);
        drawCardinal(matrices, "W", centerX, centerY, -radius, 0f, cos, sin);
    }

    private void drawCardinal(
        final MatrixStack matrices,
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
        client.textRenderer.drawWithShadow(matrices, letter, x - width / 2f, y - 4f, TEXT_COLOR);
    }

    private void drawInfoText(final MatrixStack matrices, final PlayerView player, final int x0, final int y0, final int size) {
        if (!config.showCoordinates && !config.showBiome && !config.showLayerIndicator) {
            return;
        }
        final boolean topCorner = config.minimapCorner == ConfluxConfig.Corner.TOP_LEFT
            || config.minimapCorner == ConfluxConfig.Corner.TOP_RIGHT;
        final int lineHeight = 10;
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
        float y = topCorner ? y0 + size + 3 : y0 - lines * lineHeight - 3;
        final float centerX = x0 + size / 2f;

        if (config.showCoordinates) {
            final String coords = player.blockX() + ", " + player.blockY() + ", " + player.blockZ();
            drawCenteredLine(matrices, coords, centerX, y);
            y += lineHeight;
        }
        if (config.showBiome) {
            final String biome = biomeName(player);
            if (!biome.isEmpty()) {
                drawCenteredLine(matrices, biome, centerX, y);
            }
            y += lineHeight;
        }
        if (config.showLayerIndicator) {
            drawCenteredLine(matrices, layerIndicatorText(), centerX, y);
        }
    }

    /** cave-nether-layers.md-driven layer name, keyed off {@link MapLayer.Type#id()} (e.g. "confluxmap.layer.cave"). */
    private String layerIndicatorText() {
        final MapLayer.Type type = layerSelector.current().layer().type();
        return new TranslatableText("confluxmap.layer." + type.id()).getString();
    }

    private void drawCenteredLine(final MatrixStack matrices, final String text, final float centerX, final float y) {
        final int width = client.textRenderer.getWidth(text);
        client.textRenderer.drawWithShadow(matrices, text, centerX - width / 2f, y, TEXT_COLOR);
    }

    private String biomeName(final PlayerView player) {
        if (client.world == null) {
            return "";
        }
        final Biome biome = client.world.getBiome(new BlockPos(player.blockX(), player.blockY(), player.blockZ()));
        final Identifier id = client.world.getRegistryManager().get(Registry.BIOME_KEY).getId(biome);
        if (id == null) {
            return "";
        }
        final Text name = new TranslatableText("biome." + id.getNamespace() + "." + id.getPath());
        return name.getString();
    }

    private void drawBorder(final MatrixStack matrices, final int x0, final int y0, final int size) {
        RenderUtil.fillRect(matrices, x0, y0, size, BORDER_THICKNESS, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0, y0 + size - BORDER_THICKNESS, size, BORDER_THICKNESS, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0, y0, BORDER_THICKNESS, size, BORDER_COLOR);
        RenderUtil.fillRect(matrices, x0 + size - BORDER_THICKNESS, y0, BORDER_THICKNESS, size, BORDER_COLOR);
    }

    private int originX(final int screenWidth, final int size) {
        switch (config.minimapCorner) {
            case TOP_LEFT:
            case BOTTOM_LEFT:
                return MARGIN;
            default:
                return screenWidth - size - MARGIN;
        }
    }

    private int originY(final int screenHeight, final int size) {
        switch (config.minimapCorner) {
            case TOP_LEFT:
            case TOP_RIGHT:
                return MARGIN;
            default:
                return screenHeight - size - MARGIN;
        }
    }
}
