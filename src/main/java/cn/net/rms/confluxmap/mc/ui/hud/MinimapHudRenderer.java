package cn.net.rms.confluxmap.mc.ui.hud;

import cn.net.rms.confluxmap.bridge.GameBridge;
import cn.net.rms.confluxmap.bridge.PlayerView;
import cn.net.rms.confluxmap.core.config.ConfluxConfig;
import cn.net.rms.confluxmap.core.model.MapLayer;
import cn.net.rms.confluxmap.core.model.TileKey;
import cn.net.rms.confluxmap.core.tile.TileService;
import cn.net.rms.confluxmap.core.util.TileMath;
import cn.net.rms.confluxmap.mc.render.RenderUtil;
import cn.net.rms.confluxmap.mc.render.TileTextureManager;
import java.util.Optional;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.math.MatrixStack;

/**
 * Renders the always-on minimap HUD: a north-locked square, 1 block/pixel at
 * LOD0, centered on the player, showing {@link MapLayer#SURFACE} (other layers
 * are a later slice). Render thread only (it's an {@link HudRenderCallback}).
 */
public final class MinimapHudRenderer {
    private static final int MARGIN = 4;
    private static final int BORDER_THICKNESS = 1;
    private static final int BORDER_COLOR = 0xB0FFFFFF;
    private static final int BACKGROUND_COLOR = 0x80101018;

    private final MinecraftClient client;
    private final ConfluxConfig config;
    private final GameBridge gameBridge;
    private final TileService tiles;
    private final TileTextureManager textures;

    public MinimapHudRenderer(
        final MinecraftClient client,
        final ConfluxConfig config,
        final GameBridge gameBridge,
        final TileService tiles,
        final TileTextureManager textures
    ) {
        this.client = client;
        this.config = config;
        this.gameBridge = gameBridge;
        this.tiles = tiles;
        this.textures = textures;
    }

    public void register() {
        HudRenderCallback.EVENT.register(this::render);
    }

    private void render(final MatrixStack matrices, final float tickDelta) {
        if (!config.minimapEnabled || !gameBridge.session().active()) {
            return;
        }
        final Optional<PlayerView> playerView = gameBridge.player();
        if (playerView.isEmpty()) {
            return;
        }
        final PlayerView player = playerView.get();

        textures.beginFrame();
        tiles.setViewpoint(player.blockX(), player.blockZ());

        final int size = config.minimapSize;
        final int screenWidth = client.getWindow().getScaledWidth();
        final int screenHeight = client.getWindow().getScaledHeight();
        final int x0 = originX(screenWidth, size);
        final int y0 = originY(screenHeight, size);

        RenderUtil.fillRect(matrices, x0, y0, size, size, BACKGROUND_COLOR);

        RenderUtil.enableScissor(client, x0, y0, size, size);
        RenderUtil.beginTexturedQuads();
        drawTiles(matrices, x0, y0, size, player.blockX(), player.blockZ());
        RenderUtil.disableScissor();

        drawBorder(matrices, x0, y0, size);
    }

    private void drawTiles(
        final MatrixStack matrices,
        final int x0,
        final int y0,
        final int size,
        final int centerBlockX,
        final int centerBlockZ
    ) {
        final int minX = centerBlockX - size / 2;
        final int minZ = centerBlockZ - size / 2;
        final int maxX = minX + size;
        final int maxZ = minZ + size;

        final int firstTileX = TileMath.blockToTile(minX);
        final int lastTileX = TileMath.blockToTile(maxX - 1);
        final int firstTileZ = TileMath.blockToTile(minZ);
        final int lastTileZ = TileMath.blockToTile(maxZ - 1);

        for (int tileX = firstTileX; tileX <= lastTileX; tileX++) {
            for (int tileZ = firstTileZ; tileZ <= lastTileZ; tileZ++) {
                drawOneTile(matrices, x0, y0, minX, minZ, maxX, maxZ, tileX, tileZ);
            }
        }
    }

    private void drawOneTile(
        final MatrixStack matrices,
        final int x0,
        final int y0,
        final int minX,
        final int minZ,
        final int maxX,
        final int maxZ,
        final int tileX,
        final int tileZ
    ) {
        final TileKey key = new TileKey(
            gameBridge.session().world(), gameBridge.session().dimension(), MapLayer.SURFACE.cacheId(), 0, tileX, tileZ
        );
        final int tileOriginX = key.originBlockX();
        final int tileOriginZ = key.originBlockZ();
        final int overlapMinX = Math.max(minX, tileOriginX);
        final int overlapMaxX = Math.min(maxX, tileOriginX + TileMath.TILE_SIZE);
        final int overlapMinZ = Math.max(minZ, tileOriginZ);
        final int overlapMaxZ = Math.min(maxZ, tileOriginZ + TileMath.TILE_SIZE);
        if (overlapMinX >= overlapMaxX || overlapMinZ >= overlapMaxZ) {
            return;
        }
        if (!textures.bind(key)) {
            // Not cached yet (already requested by bind()); background shows through until composed.
            return;
        }
        final float screenX = x0 + (overlapMinX - minX);
        final float screenY = y0 + (overlapMinZ - minZ);
        final float width = overlapMaxX - overlapMinX;
        final float height = overlapMaxZ - overlapMinZ;
        final float u0 = (overlapMinX - tileOriginX) / (float) TileMath.TILE_SIZE;
        final float u1 = (overlapMaxX - tileOriginX) / (float) TileMath.TILE_SIZE;
        final float v0 = (overlapMinZ - tileOriginZ) / (float) TileMath.TILE_SIZE;
        final float v1 = (overlapMaxZ - tileOriginZ) / (float) TileMath.TILE_SIZE;
        RenderUtil.drawQuad(matrices, screenX, screenY, width, height, u0, v0, u1, v1);
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
